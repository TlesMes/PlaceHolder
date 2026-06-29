package com.placeholder.queue;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.dto.QueueStatusResponse;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
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

/**
 * enter() fast-path 검증 (ADR-013). 진입 직후 즉시 입장 1건을 시도하므로, 빈자리가 있으면 스케줄러 틱을
 * 기다리지 않고 호출자가 바로 입장(admitted=true)하고, ceiling이 차 있으면 대기 상태로 남는다.
 * test 프로파일 기본값(max-active-sessions=8) 사용.
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueEnterFastPathTest extends RedisIntegrationTest {

    @Autowired QueueService queueService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void flush() {
        deleteByPattern("queue:*");
        deleteByPattern("entry:*");
        deleteByPattern("rate:*");
        redis.delete("active:all");
    }

    @Test
    @DisplayName("빈자리 있으면 enter()가 즉시 입장시킨다 (admitted=true)")
    void enter_admitsImmediately_whenCapacityAvailable() {
        Long eventId = persistEvent().getId();
        Long userId = persistBooker().getId();

        QueueStatusResponse res = queueService.enter(eventId, userId);

        assertThat(res.isAdmitted()).isTrue();
        assertThat(res.getPosition()).isNull();          // 즉시 입장 → 대기열 이탈
        assertThat(res.getWaiting()).isZero();
        assertThat(queueRepository.hasEntryToken(eventId, userId)).isTrue();
    }

    @Test
    @DisplayName("ceiling이 차 있으면 enter()는 대기 상태로 남는다 (admitted=false)")
    void enter_staysWaiting_whenCeilingFull() {
        Long eventId = persistEvent().getId();
        Long userId = persistBooker().getId();
        // ceiling(8)을 더미 활성 세션으로 가득 채움
        for (long u = 1001; u <= 1008; u++) {
            queueRepository.issueEntryToken(eventId, u, Duration.ofMinutes(5));
        }

        QueueStatusResponse res = queueService.enter(eventId, userId);

        assertThat(res.isAdmitted()).isFalse();
        assertThat(res.getPosition()).isEqualTo(1L);     // 자리 없어 맨 앞에서 대기
        assertThat(res.getWaiting()).isEqualTo(1L);
        assertThat(queueRepository.hasEntryToken(eventId, userId)).isFalse();
    }

    // --- 셋업 헬퍼 ---

    @Transactional
    Event persistEvent() {
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

    private void deleteByPattern(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
