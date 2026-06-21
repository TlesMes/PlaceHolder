package com.placeholder.provider;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.dto.SettlementResponse;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.provider.service.ProviderAccountService;
import com.placeholder.domain.reservation.entity.Reservation;
import com.placeholder.domain.reservation.repository.ReservationRepository;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.UserNotFoundException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProviderAccountService.getMySettlement (GET /api/providers/my/settlement) 단위 테스트.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>settlementBalance + SETTLE 거래 목록 매핑 정확성</li>
 *   <li>SETTLE 타입만 반환 (CHARGE/DEDUCT 제외)</li>
 *   <li>createdAt DESC 정렬</li>
 *   <li>거래 없을 때 잔액 + 빈 목록</li>
 *   <li>provider 계정 없으면 UserNotFoundException</li>
 *   <li>provider_id 격리</li>
 * </ul>
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class ProviderSettlementServiceTest extends MySQLIntegrationTest {

    @Autowired ProviderAccountService providerAccountService;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("정상: 잔액 + SETTLE 2건 매핑, createdAt DESC 정렬")
    void getMySettlement_success_andOrdering() {
        // given
        User provider = persistProviderWithAccount(30_000);
        Event event = persistEvent(provider, "정산 이벤트", "정산홀");
        User booker = persistBooker();

        Seat seatA = persistSeat(event, "S-1", 10_000);
        Seat seatB = persistSeat(event, "S-2", 20_000);
        Reservation rA = persistReservation(booker, seatA, 10_000);
        Reservation rB = persistReservation(booker, seatB, 20_000);

        LocalDateTime base = LocalDateTime.now().minusDays(1);
        persistSettle(provider, 10_000, rA, base.minusMinutes(1)); // 오래된
        PointTransaction txB = persistSettle(provider, 20_000, rB, base); // 최신

        // when
        SettlementResponse response = providerAccountService.getMySettlement(provider.getId());

        // then - 잔액
        assertThat(response.getSettlementBalance()).isEqualTo(30_000);

        // then - SETTLE 2건, DESC(최신 txB 먼저)
        List<SettlementResponse.SettlementItem> items = response.getSettlements();
        assertThat(items).hasSize(2);

        SettlementResponse.SettlementItem first = items.get(0);
        assertThat(first.getTransactionId()).isEqualTo(txB.getId());
        assertThat(first.getAmount()).isEqualTo(20_000);
        assertThat(first.getReservationId()).isEqualTo(rB.getId());
        assertThat(first.getEventTitle()).isEqualTo("정산 이벤트");
        assertThat(first.getSeatLabel()).isEqualTo("S-2");
        assertThat(first.getConfirmedAt()).isNotNull();

        assertThat(items).extracting(SettlementResponse.SettlementItem::getAmount)
                .containsExactly(20_000, 10_000);
    }

    @Test
    @DisplayName("SETTLE 타입만: CHARGE/DEDUCT 거래는 정산 목록에서 제외")
    void getMySettlement_onlySettleType() {
        // given - provider 계정에 SETTLE 1건 + (노이즈) CHARGE/DEDUCT
        User provider = persistProviderWithAccount(10_000);
        Event event = persistEvent(provider, "노이즈 이벤트", "노이즈홀");
        User booker = persistBooker();
        Seat seat = persistSeat(event, "N-1", 10_000);
        Reservation reservation = persistReservation(booker, seat, 10_000);

        LocalDateTime base = LocalDateTime.now().minusDays(1);
        persistSettle(provider, 10_000, reservation, base);
        // provider User에 직접 매달린 CHARGE/DEDUCT (도메인상 흔치 않지만 필터 검증용)
        persistTx(provider, TransactionType.CHARGE, 5_000, null, base.minusMinutes(1));
        persistTx(provider, TransactionType.DEDUCT, 3_000, reservation, base.minusMinutes(2));

        // when
        SettlementResponse response = providerAccountService.getMySettlement(provider.getId());

        // then - SETTLE 1건만
        assertThat(response.getSettlements()).hasSize(1);
        assertThat(response.getSettlements().get(0).getAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("정산 거래 없음: 잔액 반환 + 빈 목록")
    void getMySettlement_empty() {
        // given - 계정만 있고 SETTLE 없음
        User provider = persistProviderWithAccount(0);

        // when
        SettlementResponse response = providerAccountService.getMySettlement(provider.getId());

        // then
        assertThat(response.getSettlementBalance()).isEqualTo(0);
        assertThat(response.getSettlements()).isEmpty();
    }

    @Test
    @DisplayName("provider 계정 없음: UserNotFoundException")
    void getMySettlement_providerNotFound() {
        // when & then - 존재하지 않는 id
        assertThatThrownBy(() -> providerAccountService.getMySettlement(99_999_999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("provider_id 격리: 타 provider의 SETTLE 제외")
    void getMySettlement_isolation() {
        // given - 두 provider 각각 SETTLE
        User me = persistProviderWithAccount(10_000);
        User other = persistProviderWithAccount(20_000);
        User booker = persistBooker();

        Event myEvent = persistEvent(me, "내 이벤트", "내홀");
        Event otherEvent = persistEvent(other, "타인 이벤트", "타인홀");
        Reservation myRes = persistReservation(booker, persistSeat(myEvent, "ME-1", 10_000), 10_000);
        Reservation otherRes = persistReservation(booker, persistSeat(otherEvent, "OT-1", 20_000), 20_000);

        LocalDateTime base = LocalDateTime.now().minusDays(1);
        persistSettle(me, 10_000, myRes, base);
        persistSettle(other, 20_000, otherRes, base);

        // when
        SettlementResponse response = providerAccountService.getMySettlement(me.getId());

        // then - 내 SETTLE 1건만
        assertThat(response.getSettlements()).hasSize(1);
        assertThat(response.getSettlements().get(0).getEventTitle()).isEqualTo("내 이벤트");
    }

    // --- 셋업 헬퍼 ---

    @Transactional
    User persistProviderWithAccount(int settlementBalance) {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        providerAccountRepository.save(ProviderAccount.builder()
                .user(provider)
                .settlementBalance(settlementBalance)
                .build());
        return provider;
    }

    @Transactional
    User persistBooker() {
        return userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
    }

    @Transactional
    Event persistEvent(User provider, String title, String venue) {
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title(title)
                .venue(venue)
                .eventAt(LocalDateTime.now().plusDays(7))
                .build());
    }

    @Transactional
    Seat persistSeat(Event event, String label, int price) {
        return seatRepository.save(Seat.builder()
                .event(event)
                .label(label)
                .price(price)
                .status(Seat.SeatStatus.CONFIRMED)
                .build());
    }

    @Transactional
    Reservation persistReservation(User booker, Seat seat, int paidAmount) {
        return reservationRepository.save(Reservation.builder()
                .booker(booker)
                .seat(seat)
                .paidAmount(paidAmount)
                .build());
    }

    @Transactional
    PointTransaction persistSettle(User provider, int amount, Reservation reservation, LocalDateTime createdAt) {
        return persistTx(provider, TransactionType.SETTLE, amount, reservation, createdAt);
    }

    /** createdAt은 @PrePersist로 now() 고정이므로 저장 후 JdbcTemplate으로 덮어쓴다. */
    @Transactional
    PointTransaction persistTx(User user, TransactionType type, int amount,
                               Reservation reservation, LocalDateTime createdAt) {
        PointTransaction tx = pointTransactionRepository.save(PointTransaction.builder()
                .user(user)
                .type(type)
                .amount(amount)
                .reservation(reservation)
                .build());
        jdbcTemplate.update(
                "UPDATE point_transactions SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(createdAt), tx.getId());
        return tx;
    }
}
