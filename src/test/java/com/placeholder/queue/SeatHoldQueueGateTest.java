package com.placeholder.queue;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.seat.dto.SeatHoldResponse;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.QueueAdmissionRequiredException;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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
 * hold 진입점의 대기열 게이트 검증 (E-1 3단계).
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>대기열 활성 이벤트 + 토큰 없음 → QueueAdmissionRequiredException (락 잡기 전 fast-fail)</li>
 *   <li>대기열 활성 이벤트 + 토큰 있음 → hold 성공(HELD)</li>
 *   <li>대기열 비활성 이벤트 → 토큰 없이도 hold 통과 (게이트 우회)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatHoldQueueGateTest extends RedisIntegrationTest {

    @Autowired SeatService seatService;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired UserRepository userRepository;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void flushEntryTokens() {
        var keys = redis.keys("entry:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("대기열 활성 + 토큰 없음: QueueAdmissionRequiredException")
    void hold_queueEnabled_noToken_rejected() {
        Event event = persistEvent(true);
        Seat seat = persistSeat(event);
        User booker = persistBooker();

        assertThatThrownBy(() -> seatService.holdSeat(seat.getId(), booker.getId()))
                .isInstanceOf(QueueAdmissionRequiredException.class);

        // 거절됐으니 좌석은 여전히 AVAILABLE
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(Seat.SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("대기열 활성 + 입장 토큰 보유: hold 성공")
    void hold_queueEnabled_withToken_success() {
        Event event = persistEvent(true);
        Seat seat = persistSeat(event);
        User booker = persistBooker();
        queueRepository.issueEntryToken(event.getId(), booker.getId(), Duration.ofMinutes(5));

        SeatHoldResponse res = seatService.holdSeat(seat.getId(), booker.getId());

        assertThat(res.getStatus()).isEqualTo(Seat.SeatStatus.HELD.name());
        assertThat(res.getHeldBy()).isEqualTo(booker.getId());
    }

    @Test
    @DisplayName("대기열 비활성: 토큰 없이도 hold 통과")
    void hold_queueDisabled_noToken_success() {
        Event event = persistEvent(false);
        Seat seat = persistSeat(event);
        User booker = persistBooker();

        SeatHoldResponse res = seatService.holdSeat(seat.getId(), booker.getId());

        assertThat(res.getStatus()).isEqualTo(Seat.SeatStatus.HELD.name());
    }

    // --- 셋업 헬퍼 ---

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
