package com.placeholder.queue;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.seat.dto.SeatResponse;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.QueueAdmissionRequiredException;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조회 경로(좌석 그리드)의 대기열 게이트 검증 (A안, ADR-013 개정).
 *
 * <p>좌석 폴링 부하도 대기열이 지키는 D-2 부하이므로, queueEnabled 이벤트는 입장 토큰 없이는
 * {@code getSeatsResponse}가 좌석을 반환하지 않는다. 이로써 ceiling이 MySQL 좌석 폴링을
 * 실제로 바운드한다.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>대기열 활성 + 토큰 없음 → QueueAdmissionRequiredException</li>
 *   <li>대기열 활성 + 토큰 보유 → 좌석 목록 반환</li>
 *   <li>대기열 활성 + 익명(bookerId null) → QueueAdmissionRequiredException</li>
 *   <li>대기열 비활성 + 익명 → 좌석 목록 반환(게이트 우회)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatQueryQueueGateTest extends RedisIntegrationTest {

    @Autowired SeatService seatService;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired UserRepository userRepository;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;

    // 테스트 간 Redis·deficit 장부 정리는 RedisIntegrationTest가 일괄 수행한다.
    // (이 클래스가 entry:만 지우고 active:all을 남겨 다음 클래스의 ceiling을 잠식한 것이
    //  정리를 베이스로 올린 계기다.)

    @Test
    @DisplayName("대기열 활성 + 토큰 없음: QueueAdmissionRequiredException")
    void getSeats_queueEnabled_noToken_rejected() {
        Event event = persistEvent(true);
        persistSeat(event);
        User booker = persistBooker();

        assertThatThrownBy(() -> seatService.getSeatsResponse(event.getId(), booker.getId()))
                .isInstanceOf(QueueAdmissionRequiredException.class);
    }

    @Test
    @DisplayName("대기열 활성 + 입장 토큰 보유: 좌석 목록 반환")
    void getSeats_queueEnabled_withToken_success() {
        Event event = persistEvent(true);
        persistSeat(event);
        User booker = persistBooker();
        queueRepository.issueEntryToken(event.getId(), booker.getId(), Duration.ofMinutes(5));

        SeatResponse res = seatService.getSeatsResponse(event.getId(), booker.getId());

        assertThat(res.getSeats()).hasSize(1);
    }

    @Test
    @DisplayName("대기열 활성 + 익명(bookerId null): 거절")
    void getSeats_queueEnabled_anonymous_rejected() {
        Event event = persistEvent(true);
        persistSeat(event);

        assertThatThrownBy(() -> seatService.getSeatsResponse(event.getId(), null))
                .isInstanceOf(QueueAdmissionRequiredException.class);
    }

    @Test
    @DisplayName("대기열 비활성 + 익명: 토큰 없이도 좌석 목록 반환")
    void getSeats_queueDisabled_anonymous_success() {
        Event event = persistEvent(false);
        persistSeat(event);

        SeatResponse res = seatService.getSeatsResponse(event.getId(), null);

        assertThat(res.getSeats()).hasSize(1);
    }

    // --- 셋업 헬퍼 (SeatHoldQueueGateTest와 동일 패턴) ---

    @Transactional
    Event persistEvent(boolean queueEnabled) {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title("콘서트 " + uniqueId())
                .venue("올림픽홀")
                .eventAt(LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS))
                .queueEnabled(queueEnabled)
                .build());
    }

    @Transactional
    Seat persistSeat(Event event) {
        return seatRepository.save(Seat.builder()
                .event(event)
                .label("A-" + uniqueId())
                .price(10_000)
                .status(Seat.SeatStatus.AVAILABLE)
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
}
