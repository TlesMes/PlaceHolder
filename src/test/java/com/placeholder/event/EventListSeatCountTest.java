package com.placeholder.event;

import com.placeholder.domain.event.dto.EventListResponse;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.event.service.EventService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D-1: 이벤트 목록 좌석 통계(GROUP BY 집계, ADR-011)의 정확성 검증.
 *
 * k6 부하 테스트는 "쿼리 수가 줄고 빠르다"(성능)만 증명한다. 이 테스트는 그 집계가
 * total/available을 status별로 정확히 세는지(정확성)를 단언한다 — sum(case when AVAILABLE)
 * 조건, projection alias-메서드명 매칭, 좌석 0개 이벤트의 null 처리 등이 깨지면 잡힌다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class EventListSeatCountTest extends MySQLIntegrationTest {

    @Autowired EventService eventService;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("이벤트별 total/available 좌석 수가 status 기준으로 정확히 집계된다")
    void seat_counts_are_aggregated_by_status() {
        // given - 좌석 5개: AVAILABLE 3 + HELD 1 + CONFIRMED 1 → total=5, available=3
        Event event = persistEvent();
        Long holderId = persistBooker();
        persistSeat(event, Seat.SeatStatus.AVAILABLE, null, null);
        persistSeat(event, Seat.SeatStatus.AVAILABLE, null, null);
        persistSeat(event, Seat.SeatStatus.AVAILABLE, null, null);
        persistSeat(event, Seat.SeatStatus.HELD, holderId, LocalDateTime.now().plusMinutes(5));
        persistSeat(event, Seat.SeatStatus.CONFIRMED, null, null);

        // when
        EventListResponse.EventSummary summary = findSummary(event.getId());

        // then
        assertThat(summary.getTotalSeats()).isEqualTo(5);
        assertThat(summary.getAvailableSeats()).isEqualTo(3);
    }

    @Test
    @DisplayName("좌석이 없는 이벤트는 total/available 모두 0으로 집계된다 (GROUP BY 결과 누락 방어)")
    void event_with_no_seats_reports_zero() {
        // given - 좌석을 하나도 만들지 않은 이벤트.
        // GROUP BY는 이런 이벤트를 결과에 내놓지 않으므로 null → 0 처리가 동작해야 한다.
        Event event = persistEvent();

        // when
        EventListResponse.EventSummary summary = findSummary(event.getId());

        // then
        assertThat(summary.getTotalSeats()).isZero();
        assertThat(summary.getAvailableSeats()).isZero();
    }

    @Test
    @DisplayName("여러 이벤트의 좌석 통계가 서로 섞이지 않고 각각 집계된다")
    void counts_do_not_leak_across_events() {
        // given - 이벤트 A(available 2), 이벤트 B(available 1, confirmed 1)
        Event eventA = persistEvent();
        persistSeat(eventA, Seat.SeatStatus.AVAILABLE, null, null);
        persistSeat(eventA, Seat.SeatStatus.AVAILABLE, null, null);

        Event eventB = persistEvent();
        persistSeat(eventB, Seat.SeatStatus.AVAILABLE, null, null);
        persistSeat(eventB, Seat.SeatStatus.CONFIRMED, null, null);

        // when
        EventListResponse.EventSummary a = findSummary(eventA.getId());
        EventListResponse.EventSummary b = findSummary(eventB.getId());

        // then - A와 B가 서로의 좌석을 세지 않는다
        assertThat(a.getTotalSeats()).isEqualTo(2);
        assertThat(a.getAvailableSeats()).isEqualTo(2);
        assertThat(b.getTotalSeats()).isEqualTo(2);
        assertThat(b.getAvailableSeats()).isEqualTo(1);
    }

    private EventListResponse.EventSummary findSummary(Long eventId) {
        // 통합 테스트는 데이터를 정리하지 않아 목록에 다른 테스트의 이벤트가 섞이므로,
        // 이 테스트가 만든 eventId 기준으로만 찾는다.
        Map<Long, EventListResponse.EventSummary> byId = eventService.getEvents().getEvents().stream()
                .collect(Collectors.toMap(EventListResponse.EventSummary::getEventId, Function.identity()));
        return byId.get(eventId);
    }

    // --- 테스트 데이터 셋업 헬퍼 ---

    @Transactional
    Event persistEvent() {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniq() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title("좌석 통계 테스트")
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
        return booker.getId();
    }

    @Transactional
    Seat persistSeat(Event event, Seat.SeatStatus status, Long heldById, LocalDateTime heldUntil) {
        User heldBy = heldById == null ? null : userRepository.findById(heldById).orElseThrow();
        return seatRepository.save(Seat.builder()
                .event(event)
                .label("A-" + uniq())
                .price(10_000)
                .status(status)
                .heldBy(heldBy)
                .heldUntil(heldUntil)
                .build());
    }

    private static int uniq() {
        return uniqueId();
    }
}
