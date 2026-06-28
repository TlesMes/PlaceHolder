package com.placeholder.queue;

import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueAdmissionService;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 입장 서비스 검증 (E-1 4단계). admission은 순수 Redis 연산이라 합성 id로 검증한다.
 *
 * <p>test 프로파일은 max-concurrent-holds=8. 검증 포인트:
 * <ul>
 *   <li>대기 인원 ≤ 슬롯: 전원 입장 토큰 발급, 대기열 비움</li>
 *   <li>대기 인원 > 슬롯: FIFO 앞에서 슬롯만큼만 발급, 나머지 대기 유지</li>
 *   <li>in-flight 토큰이 슬롯을 차감(occupancy 반영)</li>
 *   <li>빈 대기열은 활성 집합에서 정리</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueAdmissionServiceTest extends RedisIntegrationTest {

    private static final int CAPACITY = 8;

    @Autowired QueueAdmissionService admissionService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void flush() {
        var keys = redis.keys("queue:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        var tokens = redis.keys("entry:*");
        if (tokens != null && !tokens.isEmpty()) redis.delete(tokens);
    }

    @Test
    @DisplayName("대기 인원 ≤ 슬롯: 전원 토큰 발급, 대기열 비움")
    void admit_allWhenUnderCapacity() {
        long eventId = uniqueId();
        enqueue(eventId, 3);

        int admitted = admissionService.admitWaiting();

        assertThat(admitted).isEqualTo(3);
        for (long u = 1; u <= 3; u++) {
            assertThat(queueRepository.hasEntryToken(eventId, u)).isTrue();
            assertThat(queueRepository.rank(eventId, u)).isNull(); // 대기열에서 빠짐
        }
        assertThat(queueRepository.size(eventId)).isZero();
    }

    @Test
    @DisplayName("대기 인원 > 슬롯: FIFO 앞에서 슬롯만큼만 발급, 나머지 대기")
    void admit_capByCapacity_fifo() {
        long eventId = uniqueId();
        enqueue(eventId, 10);

        int admitted = admissionService.admitWaiting();

        assertThat(admitted).isEqualTo(CAPACITY);
        // 앞선 8명(1..8) 토큰 발급 + 대기열 이탈
        for (long u = 1; u <= CAPACITY; u++) {
            assertThat(queueRepository.hasEntryToken(eventId, u)).isTrue();
            assertThat(queueRepository.rank(eventId, u)).isNull();
        }
        // 나머지 9,10은 토큰 없고 대기 유지
        for (long u = CAPACITY + 1; u <= 10; u++) {
            assertThat(queueRepository.hasEntryToken(eventId, u)).isFalse();
            assertThat(queueRepository.rank(eventId, u)).isNotNull();
        }
        assertThat(queueRepository.size(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("in-flight 토큰이 슬롯 차감: 남은 슬롯만큼만 발급")
    void admit_occupancyReducesSlots() {
        long eventId = uniqueId();
        // 이미 3명 입장 중(토큰 보유)
        for (long u = 101; u <= 103; u++) {
            queueRepository.issueEntryToken(eventId, u, Duration.ofMinutes(5));
        }
        enqueue(eventId, 10);

        int admitted = admissionService.admitWaiting();

        // 8 - 3 = 5만 발급
        assertThat(admitted).isEqualTo(CAPACITY - 3);
        assertThat(queueRepository.size(eventId)).isEqualTo(10 - 5);
    }

    @Test
    @DisplayName("빈 대기열은 활성 집합에서 정리")
    void admit_cleansEmptyQueue() {
        long eventId = uniqueId();
        enqueue(eventId, 2);

        admissionService.admitWaiting();          // 2명 입장 → 대기열 빔
        assertThat(queueRepository.size(eventId)).isZero();

        admissionService.admitWaiting();          // 다음 틱: size 0 감지 → 활성 집합 정리
        assertThat(queueRepository.activeQueueEventIds()).doesNotContain(eventId);
    }

    /** userId 1..n을 진입 순서(증가 score)대로 대기열에 넣는다. */
    private void enqueue(long eventId, int n) {
        for (long u = 1; u <= n; u++) {
            queueRepository.enqueue(eventId, u, 1_000L + u);
        }
    }
}
