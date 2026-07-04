package com.placeholder.queue;

import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueAdmissionService;
import com.placeholder.domain.queue.service.QueueService;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 대기열 이탈(leave) 검증 (ADR-015).
 *
 * <p>leave는 대기 순번(queue ZSET)과 입장 토큰(entry + active:all)을 함께 정리한다 —
 * 대기 중이든 입장 후든 어느 상태에서 호출해도 멱등. 검증 포인트:
 * <ul>
 *   <li>대기 중 이탈: 순번 제거 + 뒷사람 순번 −1</li>
 *   <li>대기 중 이탈자는 이후 배치 입장에서 토큰을 받지 않음 (유령 토큰 발급 방지)</li>
 *   <li>입장 후 이탈: 토큰 + 전역 활성 세션 쌍 회수</li>
 *   <li>무상태 유저의 반복 호출도 예외 없음 (멱등)</li>
 *   <li>이탈로 빈 ceiling 슬롯에 다음 대기자 입장 (회전)</li>
 * </ul>
 *
 * <p>leave는 순수 Redis 연산(이벤트 존재 검증 없음)이라 합성 id로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueLeaveTest extends RedisIntegrationTest {

    private static final int CEILING = 8; // test 프로파일 queue.admission.max-active-sessions

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void flush() {
        deleteByPattern("queue:*");
        deleteByPattern("entry:*");
        deleteByPattern("rate:*");
        redis.delete("active:all");
    }

    @Test
    @DisplayName("대기 중 이탈: 순번 제거 + 뒷사람 순번 −1")
    void leave_whileWaiting_removesRankAndShifts() {
        long eventId = uniqueId();
        queueRepository.enqueue(eventId, 1L, 1_001L);
        queueRepository.enqueue(eventId, 2L, 1_002L);
        queueRepository.enqueue(eventId, 3L, 1_003L);

        queueService.leave(eventId, 2L);

        assertThat(queueRepository.rank(eventId, 2L)).isNull();
        assertThat(queueRepository.size(eventId)).isEqualTo(2);
        assertThat(queueRepository.rank(eventId, 1L)).isZero();
        assertThat(queueRepository.rank(eventId, 3L)).isEqualTo(1L); // 2 → 1
    }

    @Test
    @DisplayName("대기 중 이탈자는 배치 입장에서 토큰을 받지 않음 (유령 토큰 방지)")
    void leave_whileWaiting_preventsGhostAdmission() {
        long eventId = uniqueId();
        queueRepository.enqueue(eventId, 1L, 1_001L);
        queueRepository.enqueue(eventId, 2L, 1_002L);

        queueService.leave(eventId, 1L); // 맨 앞 대기자가 이탈
        int admitted = admissionService.admitWaiting();

        assertThat(admitted).isEqualTo(1);
        assertThat(queueRepository.hasEntryToken(eventId, 1L)).isFalse(); // 이탈자 토큰 없음
        assertThat(queueRepository.hasEntryToken(eventId, 2L)).isTrue();  // 남은 대기자만 입장
    }

    @Test
    @DisplayName("입장 후 이탈: 토큰 + 전역 활성 세션 쌍 회수")
    void leave_afterAdmission_releasesToken() {
        long eventId = uniqueId();
        queueRepository.issueEntryToken(eventId, 1L, Duration.ofMinutes(5));

        queueService.leave(eventId, 1L);

        assertThat(queueRepository.hasEntryToken(eventId, 1L)).isFalse();
        assertThat(redis.opsForZSet().score("active:all", eventId + ":" + 1L)).isNull();
    }

    @Test
    @DisplayName("무상태 유저의 반복 leave: 예외 없음 (멱등)")
    void leave_noState_idempotent() {
        long eventId = uniqueId();

        assertThatCode(() -> {
            queueService.leave(eventId, 1L);
            queueService.leave(eventId, 1L);
        }).doesNotThrowAnyException();

        assertThat(queueRepository.size(eventId)).isZero();
        assertThat(queueRepository.hasEntryToken(eventId, 1L)).isFalse();
    }

    @Test
    @DisplayName("ceiling 회전: 입장자 이탈로 빈 슬롯에 다음 대기자 입장")
    void leave_freesSlot_nextWaiterAdmitted() {
        long eventId = uniqueId();
        // ceiling 가득: 합성 활성 세션 8명
        for (long u = 900_001; u < 900_001 + CEILING; u++) {
            queueRepository.issueEntryToken(eventId, u, Duration.ofMinutes(5));
        }
        long waiterId = 900_100;
        queueRepository.enqueue(eventId, waiterId, System.currentTimeMillis());

        assertThat(admissionService.admitWaiting()).isZero(); // 가득 → 입장 불가

        queueService.leave(eventId, 900_001L); // 입장자 1명 이탈
        deleteByPattern("rate:*");             // 다음 초 모사 (rate 윈도 초기화)

        assertThat(admissionService.admitWaiting()).isEqualTo(1);
        assertThat(queueRepository.hasEntryToken(eventId, waiterId)).isTrue();
        assertThat(queueRepository.rank(eventId, waiterId)).isNull();
    }

    private void deleteByPattern(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
