package com.placeholder.queue;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.dto.QueueStatusResponse;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.cache.EventExistenceChecker;
import com.placeholder.global.exception.custom.EventNotFoundException;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QueueService 단위 테스트 (E-1 2단계: 진입/순번 조회).
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>진입 시 순번/대기 인원 반환, 입장 토큰 미보유</li>
 *   <li>중복 진입 멱등(ZADD NX) — 순번/인원 불변</li>
 *   <li>다중 사용자 FIFO 순번</li>
 *   <li>대기열에 없는 사용자 status → position null</li>
 *   <li>존재하지 않는 이벤트 → EventNotFoundException</li>
 * </ul>
 *
 * <p>싱글톤 Redis 컨테이너는 JVM 전체에서 키를 공유하므로, 테스트마다 keys 패턴으로 정리한다.
 *
 * <p>enter()는 enqueue만 하고 입장은 스케줄러가 담당하므로, 진입 직후 status는 항상 대기 상태(admitted=false)다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueServiceTest extends RedisIntegrationTest {

    @Autowired QueueService queueService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired CacheManager cacheManager;
    @MockitoSpyBean EventExistenceChecker eventExistenceChecker;

    @AfterEach
    void cleanup() {
        deleteByPattern("queue:*");
        deleteByPattern("entry:*");
        deleteByPattern("rate:*");
        redis.delete("active:all");
        cacheManager.getCache("eventExists").clear();
        Mockito.reset(eventExistenceChecker);
    }

    private void deleteByPattern(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    @DisplayName("진입: position 1, waiting 1, 입장 토큰 미보유")
    void enter_firstUser() {
        Long eventId = persistEvent().getId();
        Long userId = persistBooker().getId();

        QueueStatusResponse res = queueService.enter(eventId, userId);

        assertThat(res.getEventId()).isEqualTo(eventId);
        assertThat(res.getPosition()).isEqualTo(1L);
        assertThat(res.getWaiting()).isEqualTo(1L);
        assertThat(res.isAdmitted()).isFalse();
    }

    @Test
    @DisplayName("중복 진입 멱등: 순번/인원 불변 (ZADD NX)")
    void enter_idempotent() {
        Long eventId = persistEvent().getId();
        Long userId = persistBooker().getId();

        queueService.enter(eventId, userId);
        QueueStatusResponse second = queueService.enter(eventId, userId);

        assertThat(second.getPosition()).isEqualTo(1L);
        assertThat(second.getWaiting()).isEqualTo(1L);
    }

    @Test
    @DisplayName("다중 사용자: 진입 순서대로 FIFO 순번")
    void enter_fifoOrdering() {
        Long eventId = persistEvent().getId();
        Long first = persistBooker().getId();
        Long second = persistBooker().getId();

        QueueStatusResponse r1 = queueService.enter(eventId, first);
        QueueStatusResponse r2 = queueService.enter(eventId, second);

        assertThat(r1.getPosition()).isEqualTo(1L);
        assertThat(r2.getPosition()).isEqualTo(2L);
        assertThat(r2.getWaiting()).isEqualTo(2L);
    }

    @Test
    @DisplayName("대기열에 없는 사용자 status: position null")
    void status_notInQueue() {
        Long eventId = persistEvent().getId();
        Long userId = persistBooker().getId();

        QueueStatusResponse res = queueService.status(eventId, userId);

        assertThat(res.getPosition()).isNull();
        assertThat(res.getWaiting()).isEqualTo(0L);
        assertThat(res.isAdmitted()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 이벤트: EventNotFoundException")
    void enter_eventNotFound() {
        Long userId = persistBooker().getId();

        assertThatThrownBy(() -> queueService.enter(999_999L, userId))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    @DisplayName("이벤트 존재 확인: enter 두 번 호출해도 existsById DB 조회는 1회 (캐시 히트)")
    void enter_eventExistsCached_afterFirstHit() {
        Long eventId = persistEvent().getId();
        Long userId = persistBooker().getId();

        queueService.enter(eventId, userId);  // 1차: DB 조회 → 캐시 저장
        queueService.enter(eventId, userId);  // 2차: 캐시 히트, DB 조회 없음

        Mockito.verify(eventExistenceChecker, Mockito.times(1)).exists(eventId);
    }

    @Test
    @DisplayName("존재하지 않는 이벤트: 캐시 미저장 — 재시도 시 여전히 DB 조회")
    void enter_nonExistent_notCached() {
        Long userId = persistBooker().getId();
        long bogusId = 999_998L;

        assertThatThrownBy(() -> queueService.enter(bogusId, userId))
                .isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> queueService.enter(bogusId, userId))
                .isInstanceOf(EventNotFoundException.class);

        // false는 캐시 저장 안 하므로(unless) 두 번 모두 DB 조회
        Mockito.verify(eventExistenceChecker, Mockito.times(2)).exists(bogusId);
    }

    @Test
    @DisplayName("nextPollDelayMs: 앞 인원/rate 예상 대기시간을 [min,max]로 클램프")
    void status_nextPollDelay_scalesWithPosition() {
        Long eventId = persistEvent().getId();
        // 합성 id로 81명 대기열 구성 (ceiling=0이라 입장 없음). test rate=8, min=2000, max=10000.
        for (long u = 1; u <= 81; u++) {
            queueRepository.enqueue(eventId, u, 1_000L + u);
        }

        // 맨 앞(ahead 0) → 0ms → 하한 2000으로 클램프
        assertThat(queueService.status(eventId, 1L).getNextPollDelayMs()).isEqualTo(2000L);
        // ahead 40 → 40*1000/8 = 5000 (범위 내)
        assertThat(queueService.status(eventId, 41L).getNextPollDelayMs()).isEqualTo(5000L);
        // ahead 80 → 80*1000/8 = 10000 (상한 경계)
        assertThat(queueService.status(eventId, 81L).getNextPollDelayMs()).isEqualTo(10000L);
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
}
