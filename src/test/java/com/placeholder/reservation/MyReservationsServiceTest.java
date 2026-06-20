package com.placeholder.reservation;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.reservation.dto.MyReservationsResponse;
import com.placeholder.domain.reservation.entity.Reservation;
import com.placeholder.domain.reservation.repository.ReservationRepository;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReservationService.getMyReservations (GET /api/reservations/my) 단위 테스트.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>Reservation → Seat → Event 매핑 필드 정확성 (fetch join, N+1 회피)</li>
 *   <li>confirmedAt DESC 정렬</li>
 *   <li>본인 예약만 반환 (booker_id 격리)</li>
 *   <li>예약 없을 때 빈 리스트</li>
 * </ul>
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class MyReservationsServiceTest extends MySQLIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("본인 예약 2건: 매핑 정확, confirmedAt DESC 정렬")
    void getMyReservations_success_and_ordering() {
        // given
        User provider = persistProvider();
        Event eventA = persistEvent(provider, "콘서트 A", "올림픽홀");
        Event eventB = persistEvent(provider, "콘서트 B", "체조경기장");
        User booker = persistBooker();

        Seat seatA = persistSeat(eventA, "A-1", 10_000);
        Seat seatB = persistSeat(eventB, "B-2", 20_000);

        // 먼저 확정된 것(오래된 confirmedAt)과 나중 확정된 것
        LocalDateTime earlier = LocalDateTime.now().minusDays(2);
        LocalDateTime later = LocalDateTime.now().minusDays(1);
        persistReservation(booker, seatA, 10_000, earlier);
        Reservation rb = persistReservation(booker, seatB, 20_000, later);

        // when
        MyReservationsResponse response = reservationService.getMyReservations(booker.getId());

        // then — 2건, confirmedAt DESC (later 먼저)
        List<MyReservationsResponse.ReservationSummary> list = response.getReservations();
        assertThat(list).hasSize(2);

        MyReservationsResponse.ReservationSummary first = list.get(0);
        assertThat(first.getReservationId()).isEqualTo(rb.getId());
        assertThat(first.getEventId()).isEqualTo(eventB.getId());
        assertThat(first.getEventTitle()).isEqualTo("콘서트 B");
        assertThat(first.getEventVenue()).isEqualTo("체조경기장");
        assertThat(first.getEventAt()).isEqualTo(eventB.getEventAt());
        assertThat(first.getSeatId()).isEqualTo(seatB.getId());
        assertThat(first.getSeatLabel()).isEqualTo("B-2");
        assertThat(first.getPaidAmount()).isEqualTo(20_000);
        assertThat(first.getConfirmedAt()).isNotNull();

        // 정렬 검증: 첫 항목 confirmedAt >= 둘째 항목
        assertThat(list.get(0).getConfirmedAt()).isAfterOrEqualTo(list.get(1).getConfirmedAt());
        assertThat(list.get(1).getEventTitle()).isEqualTo("콘서트 A");
    }

    @Test
    @DisplayName("예약 없는 booker: 빈 리스트")
    void getMyReservations_empty() {
        // given
        User booker = persistBooker();

        // when
        MyReservationsResponse response = reservationService.getMyReservations(booker.getId());

        // then
        assertThat(response.getReservations()).isEmpty();
    }

    @Test
    @DisplayName("타인 예약 제외: 본인 booker_id 예약만 반환")
    void getMyReservations_isolation() {
        // given - 두 booker가 각각 예약
        User provider = persistProvider();
        Event event = persistEvent(provider, "공유 이벤트", "공용홀");
        User me = persistBooker();
        User other = persistBooker();

        Seat mySeat = persistSeat(event, "M-1", 10_000);
        Seat otherSeat = persistSeat(event, "O-1", 10_000);
        persistReservation(me, mySeat, 10_000, LocalDateTime.now().minusHours(1));
        persistReservation(other, otherSeat, 10_000, LocalDateTime.now().minusHours(1));

        // when
        MyReservationsResponse response = reservationService.getMyReservations(me.getId());

        // then — 내 예약 1건만
        assertThat(response.getReservations()).hasSize(1);
        assertThat(response.getReservations().get(0).getSeatLabel()).isEqualTo("M-1");
    }

    // --- 셋업 헬퍼 ---

    @Transactional
    User persistProvider() {
        return userRepository.save(User.builder()
                .email("provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
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
                // eventAt을 초 단위로 절삭: DB datetime(6) 반올림과 인메모리 nanos 값의
                // 불일치(getEventAt 동등 비교 실패)를 막는다.
                .eventAt(LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS))
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

    /**
     * confirmedAt은 @PrePersist로 now() 고정이므로, 저장 후 JdbcTemplate으로 덮어써
     * 정렬 경계를 결정론적으로 만든다.
     */
    @Transactional
    Reservation persistReservation(User booker, Seat seat, int paidAmount, LocalDateTime confirmedAt) {
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .booker(booker)
                .seat(seat)
                .paidAmount(paidAmount)
                .build());
        jdbcTemplate.update(
                "UPDATE reservations SET confirmed_at = ? WHERE id = ?",
                Timestamp.valueOf(confirmedAt), reservation.getId());
        return reservation;
    }
}
