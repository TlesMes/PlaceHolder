package com.placeholder.reservation;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.reservation.dto.ReservationConfirmResponse;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.InsufficientPointException;
import com.placeholder.global.exception.custom.SeatNotAvailableException;
import com.placeholder.global.exception.custom.SeatNotHeldByUserException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class ReservationConfirmServiceTest extends MySQLIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("정상 확정: CONFIRMED 전이, 잔액 차감, PointTransaction 2행 생성")
    void confirm_success() {
        // given
        int price = 10_000;
        int initialBalance = 50_000;
        TestFixture f = persistFixture(price, initialBalance, LocalDateTime.now().plusMinutes(5));

        // when
        ReservationConfirmResponse response =
                reservationService.confirmReservation(f.seat.getId(), f.bookerId);

        // then — Reservation 응답
        assertThat(response.getPaidAmount()).isEqualTo(price);
        assertThat(response.getRemainingBalance()).isEqualTo(initialBalance - price);
        assertThat(response.getConfirmedAt()).isNotNull();

        // then — Seat CONFIRMED 전이
        Seat confirmed = seatRepository.findById(f.seat.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(Seat.SeatStatus.CONFIRMED);
        assertThat(confirmed.getHeldBy()).isNull();
        assertThat(confirmed.getHeldUntil()).isNull();

        // then — BookerAccount 잔액 차감
        BookerAccount bookerAccount = bookerAccountRepository.findByUserId(f.bookerId).orElseThrow();
        assertThat(bookerAccount.getBalance()).isEqualTo(initialBalance - price);

        // then — ProviderAccount 정산액 적립
        ProviderAccount providerAccount = providerAccountRepository.findByUserId(f.providerId).orElseThrow();
        assertThat(providerAccount.getSettlementBalance()).isEqualTo(price);

        // then — PointTransaction 2행 (DEDUCT + SETTLE)
        List<PointTransaction> txList =
                pointTransactionRepository.findByReservationId(response.getReservationId());
        assertThat(txList).hasSize(2);
        assertThat(txList).extracting(PointTransaction::getType)
                .containsExactlyInAnyOrder(
                        PointTransaction.TransactionType.DEDUCT,
                        PointTransaction.TransactionType.SETTLE);
    }

    @Test
    @DisplayName("잔액 부족: InsufficientPointException, 좌석은 여전히 HELD")
    void confirm_fail_insufficient_point() {
        // given - 잔액(500) < 가격(10_000)
        TestFixture f = persistFixture(10_000, 500, LocalDateTime.now().plusMinutes(5));

        // when & then
        assertThatThrownBy(() -> reservationService.confirmReservation(f.seat.getId(), f.bookerId))
                .isInstanceOf(InsufficientPointException.class);

        // 롤백 확인 — 좌석 여전히 HELD
        Seat seat = seatRepository.findById(f.seat.getId()).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(Seat.SeatStatus.HELD);
    }

    @Test
    @DisplayName("AVAILABLE 좌석 확정 시도: SeatNotAvailableException")
    void confirm_fail_seat_not_held() {
        // given - AVAILABLE 좌석
        Event event = persistEvent();
        Long bookerId = persistBookerWithBalance(event, 50_000);
        Seat seat = persistSeat(event, Seat.SeatStatus.AVAILABLE, null, null, 10_000);

        // when & then
        assertThatThrownBy(() -> reservationService.confirmReservation(seat.getId(), bookerId))
                .isInstanceOf(SeatNotAvailableException.class);
    }

    @Test
    @DisplayName("타인 홀드 좌석 확정 시도: SeatNotHeldByUserException")
    void confirm_fail_not_owned_hold() {
        // given - 다른 예약자가 홀드한 좌석
        Event event = persistEvent();
        Long holderBookerId = persistBookerWithBalance(event, 50_000);
        Long otherBookerId = persistBookerWithBalance(event, 50_000);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, holderBookerId,
                LocalDateTime.now().plusMinutes(5), 10_000);

        // when & then
        assertThatThrownBy(() -> reservationService.confirmReservation(seat.getId(), otherBookerId))
                .isInstanceOf(SeatNotHeldByUserException.class);
    }

    @Test
    @DisplayName("만료된 홀드 확정 시도: SeatNotAvailableException")
    void confirm_fail_hold_expired() {
        // given - 1시간 전에 만료
        Event event = persistEvent();
        Long bookerId = persistBookerWithBalance(event, 50_000);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                LocalDateTime.now().minusHours(1), 10_000);

        // when & then
        assertThatThrownBy(() -> reservationService.confirmReservation(seat.getId(), bookerId))
                .isInstanceOf(SeatNotAvailableException.class);
    }

    // --- 테스트 데이터 셋업 헬퍼 ---

    record TestFixture(Seat seat, Long bookerId, Long providerId) {}

    @Transactional
    TestFixture persistFixture(int price, int initialBalance, LocalDateTime heldUntil) {
        Event event = persistEvent();
        Long bookerId = persistBookerWithBalance(event, initialBalance);
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId, heldUntil, price);
        Long providerId = event.getProvider().getId();
        return new TestFixture(seat, bookerId, providerId);
    }

    @Transactional
    Event persistEvent() {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        providerAccountRepository.save(ProviderAccount.builder()
                .user(provider)
                .build());
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title("확정 테스트 이벤트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
    }

    @Transactional
    Long persistBookerWithBalance(Event event, int balance) {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder()
                .user(booker)
                .balance(balance)
                .build());
        return booker.getId();
    }

    @Transactional
    Seat persistSeat(Event event, Seat.SeatStatus status, Long heldById,
                     LocalDateTime heldUntil, int price) {
        User heldBy = heldById == null ? null : userRepository.findById(heldById).orElseThrow();
        return seatRepository.save(Seat.builder()
                .event(event)
                .label("A-" + uniq())
                .price(price)
                .status(status)
                .heldBy(heldBy)
                .heldUntil(heldUntil)
                .build());
    }

    private static int uniq() {
        return uniqueId();
    }
}
