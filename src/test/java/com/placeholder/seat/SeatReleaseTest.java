package com.placeholder.seat;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.seat.dto.SeatReleaseResponse;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.SeatNotFoundException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이탈 시 좌석 hold 반환(SeatService.releaseSeat)의 단위 정합성 테스트 (ADR-016).
 *
 * 본인 홀드만 반환하고, 그 외(타인 홀드·CONFIRMED·이미 AVAILABLE)는 예외 없이 멱등 no-op임을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatReleaseTest extends MySQLIntegrationTest {

    @Autowired SeatService seatService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("본인이 홀드한 좌석 반환: AVAILABLE로 복귀하고 released=true")
    void release_ownHeldSeat_success() {
        Event event = persistEvent();
        Long bookerId = persistBooker();
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                LocalDateTime.now().plusMinutes(5), 10_000);

        SeatReleaseResponse res = seatService.releaseSeat(seat.getId(), bookerId);

        assertThat(res.isReleased()).isTrue();
        assertThat(res.getStatus()).isEqualTo("AVAILABLE");
        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Seat.SeatStatus.AVAILABLE);
        assertThat(reloaded.getHeldBy()).isNull();
        assertThat(reloaded.getHeldUntil()).isNull();
    }

    @Test
    @DisplayName("타인이 홀드한 좌석 반환 요청: no-op (남의 좌석 불가침, released=false)")
    void release_othersHeldSeat_noop() {
        Event event = persistEvent();
        Long ownerId = persistBooker();
        Long strangerId = persistBooker();
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, ownerId,
                LocalDateTime.now().plusMinutes(5), 10_000);

        SeatReleaseResponse res = seatService.releaseSeat(seat.getId(), strangerId);

        assertThat(res.isReleased()).isFalse();
        assertThat(res.getStatus()).isEqualTo("HELD");
        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Seat.SeatStatus.HELD);
        assertThat(reloaded.getHeldBy().getId()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("CONFIRMED 좌석 반환 요청: no-op (확정 좌석 불가침)")
    void release_confirmedSeat_noop() {
        Event event = persistEvent();
        Seat seat = persistSeat(event, Seat.SeatStatus.CONFIRMED, null, null, 10_000);

        SeatReleaseResponse res = seatService.releaseSeat(seat.getId(), persistBooker());

        assertThat(res.isReleased()).isFalse();
        assertThat(res.getStatus()).isEqualTo("CONFIRMED");
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(Seat.SeatStatus.CONFIRMED);
    }

    @Test
    @DisplayName("이미 AVAILABLE인 좌석 반환 요청: no-op (멱등)")
    void release_availableSeat_idempotentNoop() {
        Event event = persistEvent();
        Seat seat = persistSeat(event, Seat.SeatStatus.AVAILABLE, null, null, 10_000);

        SeatReleaseResponse res = seatService.releaseSeat(seat.getId(), persistBooker());

        assertThat(res.isReleased()).isFalse();
        assertThat(res.getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("만료된 본인 홀드 반환: heldBy 검사를 통과하므로 정상 반환된다")
    void release_ownExpiredHeldSeat_success() {
        Event event = persistEvent();
        Long bookerId = persistBooker();
        Seat seat = persistSeat(event, Seat.SeatStatus.HELD, bookerId,
                LocalDateTime.now().minusSeconds(1), 10_000);

        SeatReleaseResponse res = seatService.releaseSeat(seat.getId(), bookerId);

        assertThat(res.isReleased()).isTrue();
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(Seat.SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("존재하지 않는 좌석 반환: SeatNotFoundException")
    void release_missingSeat_throws() {
        assertThatThrownBy(() -> seatService.releaseSeat(999_999_999L, persistBooker()))
                .isInstanceOf(SeatNotFoundException.class);
    }

    // --- 테스트 데이터 셋업 헬퍼 ---

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
                .title("좌석 반환 테스트")
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
    }

    @Transactional
    Long persistBooker() {
        User booker = userRepository.save(User.builder()
                .email("booker-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder()
                .user(booker)
                .paidBalance(0)
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
