package com.placeholder.queue;

import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueAdmissionService;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 입장 서비스 검증 (ADR-013). admission은 순수 Redis 연산이라 합성 id로 검증한다.
 *
 * <p>test 프로파일 기본값: max-active-sessions=8, rate-per-second=8(단일 틱에서 8명 입장). 검증 포인트:
 * <ul>
 *   <li>대기 인원 ≤ ceiling: 전원 입장 토큰 발급, 대기열 비움</li>
 *   <li>대기 인원 > ceiling: FIFO 앞에서 ceiling만큼만 발급, 나머지 대기 유지</li>
 *   <li>in-flight 활성 세션이 ceiling을 차감(occupancy 반영)</li>
 *   <li>빈 대기열은 활성 집합에서 정리</li>
 *   <li>초과 → 한 명 이탈 → 다음 대기자 입장 + 뒷사람 대기순 −1</li>
 *   <li>이벤트 간 분배(ADR-017): 균등 분할·work-conserving·크레딧/대출 부재. 장기 양상은
 *       {@code QueueStarvationScenarioTest}가 실시간 30틱+로 검증</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueAdmissionServiceTest extends RedisIntegrationTest {

    private static final int CEILING = 8; // test 프로파일 queue.admission.max-active-sessions

    @Autowired QueueAdmissionService admissionService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;

    // 테스트 간 Redis·deficit 장부 정리는 RedisIntegrationTest가 일괄 수행한다.

    @Test
    @DisplayName("대기 인원 ≤ ceiling: 전원 토큰 발급, 대기열 비움")
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
    @DisplayName("대기 인원 > ceiling: FIFO 앞에서 ceiling만큼만 발급, 나머지 대기")
    void admit_capByCeiling_fifo() {
        long eventId = uniqueId();
        enqueue(eventId, 10);

        int admitted = admissionService.admitWaiting();

        assertThat(admitted).isEqualTo(CEILING);
        // 앞선 8명(1..8) 토큰 발급 + 대기열 이탈
        for (long u = 1; u <= CEILING; u++) {
            assertThat(queueRepository.hasEntryToken(eventId, u)).isTrue();
            assertThat(queueRepository.rank(eventId, u)).isNull();
        }
        // 나머지 9,10은 토큰 없고 대기 유지
        for (long u = CEILING + 1; u <= 10; u++) {
            assertThat(queueRepository.hasEntryToken(eventId, u)).isFalse();
            assertThat(queueRepository.rank(eventId, u)).isNotNull();
        }
        assertThat(queueRepository.size(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("in-flight 활성 세션이 ceiling 차감: 남은 자리만큼만 발급")
    void admit_occupancyReducesCeiling() {
        long eventId = uniqueId();
        // 이미 3명 입장 중(활성 세션 보유)
        for (long u = 101; u <= 103; u++) {
            queueRepository.issueEntryToken(eventId, u, Duration.ofMinutes(5));
        }
        enqueue(eventId, 10);

        int admitted = admissionService.admitWaiting();

        // 8 - 3 = 5만 발급
        assertThat(admitted).isEqualTo(CEILING - 3);
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

    @Test
    @DisplayName("초과 → 한 명 이탈 → 다음 대기자 입장 + 뒷사람 대기순 −1")
    void admit_thenLeave_promotesNextAndShiftsRank() {
        long eventId = uniqueId();
        enqueue(eventId, 10);

        admissionService.admitWaiting();          // 1..8 입장, 9·10 대기
        assertThat(queueRepository.rank(eventId, 9L)).isZero();   // 8명 빠져 맨 앞
        assertThat(queueRepository.rank(eventId, 10L)).isEqualTo(1L);
        assertThat(queueRepository.activeCount()).isEqualTo(CEILING);

        // 입장자 1명 이탈 → ceiling 한 자리 회수 (활성 세션 + 게이트 토큰 제거)
        redis.opsForZSet().remove("active:all", eventId + ":1");
        redis.delete("entry:" + eventId + ":1");
        deleteByPattern("rate:*");                 // 다음 초 모사 (직전 틱이 rate 소진)

        admissionService.admitWaiting();          // 자리 1개 → 9번 입장

        assertThat(queueRepository.hasEntryToken(eventId, 9L)).isTrue();
        assertThat(queueRepository.rank(eventId, 9L)).isNull();   // 입장 → 대기열 이탈
        assertThat(queueRepository.rank(eventId, 10L)).isZero();  // 1 → 0, 대기순 −1
    }

    // --- 이벤트 간 분배 (ADR-017: 균등 RR + deficit 이월) ---

    @Test
    @DisplayName("다중 이벤트: 틱 예산 균등 분할 + 짧은 큐 잉여는 남은 큐로 (독식 방지)")
    void admit_splitsBudgetAcrossEvents_workConserving() {
        long hot = uniqueId();
        long small = uniqueId();
        enqueue(hot, 20);
        enqueue(small, 3);

        int admitted = admissionService.admitWaiting();

        assertThat(admitted).isEqualTo(8); // rate 전부 사용 — 분배가 처리량을 깎지 않음
        // small: 균등 몫 4 중 대기 3명 전원 입장 (기존이라면 hot이 rate 8을 독식할 수 있던 상황)
        for (long u = 1; u <= 3; u++) {
            assertThat(queueRepository.hasEntryToken(small, u)).isTrue();
        }
        assertThat(queueRepository.size(small)).isZero();
        // hot: 균등 몫 4 + small 잉여 1 = 5명, FIFO 앞에서부터
        for (long u = 1; u <= 5; u++) {
            assertThat(queueRepository.hasEntryToken(hot, u)).isTrue();
        }
        assertThat(queueRepository.hasEntryToken(hot, 6L)).isFalse();
        assertThat(queueRepository.size(hot)).isEqualTo(15);
    }

    @Test
    @DisplayName("소진된 큐의 몫은 다음 틱에 남은 큐로 복귀 (work-conserving)")
    void admit_returnsShareAfterQueueDrained() {
        long hot = uniqueId();
        long small = uniqueId();
        enqueue(hot, 20);
        enqueue(small, 3);

        admissionService.admitWaiting();           // hot 5 + small 3 (위 테스트와 동일 분배)

        // 다음 틱 모사: rate 윈도 리셋 + 입장자 전원 이탈로 ceiling 회수
        deleteByPattern("rate:*");
        redis.delete("active:all");

        int second = admissionService.admitWaiting();

        assertThat(second).isEqualTo(8);            // small 큐가 비어 rate 전부 hot으로 복귀
        for (long u = 6; u <= 13; u++) {
            assertThat(queueRepository.hasEntryToken(hot, u)).isTrue();
        }
        assertThat(queueRepository.size(hot)).isEqualTo(7);
    }

    @Test
    @DisplayName("짧은 큐 잉여는 공짜(대출 아님)·빈 큐 크레딧 축적 없음 — 재충원 시 균등 몫 복귀")
    void admit_noStaleCreditNorDebt_afterRefill() {
        long hot = uniqueId();
        long small = uniqueId();
        enqueue(hot, 20);
        enqueue(small, 2);                          // small 균등 몫(4)보다 짧은 큐

        admissionService.admitWaiting();            // hot 6(몫 4+잉여 2) + small 2
        assertThat(queueRepository.size(hot)).isEqualTo(14);
        assertThat(queueRepository.size(small)).isZero();

        // small 재충원 + 다음 틱 모사
        for (long u = 3; u <= 12; u++) {
            queueRepository.enqueue(small, u, 2_000L + u);
        }
        deleteByPattern("rate:*");
        redis.delete("active:all");

        admissionService.admitWaiting();

        // 정확히 4:4 균등 — hot이 잉여 2를 되갚지도 않고(대출 아님),
        // small이 첫 틱에 못 쓴 몫을 몰아 받지도 않는다(빈 큐 크레딧 소멸)
        assertThat(queueRepository.size(hot)).isEqualTo(10);   // 14 − 4
        assertThat(queueRepository.size(small)).isEqualTo(6);  // 10 − 4
    }

    @Test
    @DisplayName("핫 이벤트가 rate 전량 처리 중 신규 이벤트 등장 → 다음 틱부터 즉시 균등 몫")
    void admit_newEventJoinsMidStream_getsFairShareImmediately() {
        long hot = uniqueId();
        enqueue(hot, 20);

        int first = admissionService.admitWaiting();    // hot 단독 → rate 8 전량 사용
        assertThat(first).isEqualTo(8);
        assertThat(queueRepository.size(hot)).isEqualTo(12);

        // 처리 도중 신규 소형 이벤트 오픈 — 5명이 줄 서기 시작. 다음 틱 모사(rate 리셋 + ceiling 회수)
        long small = uniqueId();
        enqueue(small, 5);
        deleteByPattern("rate:*");
        redis.delete("active:all");

        int second = admissionService.admitWaiting();

        assertThat(second).isEqualTo(8);
        // 신규 이벤트는 등장한 바로 다음 틱에 균등 몫 4 — 핫 이벤트 소진을 기다리며 굶지 않는다
        for (long u = 1; u <= 4; u++) {
            assertThat(queueRepository.hasEntryToken(small, u)).isTrue();
        }
        assertThat(queueRepository.size(small)).isEqualTo(1);
        // 핫은 독점(8)에서 균등 몫(4)으로 재적응 — 9..12번째 입장, FIFO 유지
        for (long u = 9; u <= 12; u++) {
            assertThat(queueRepository.hasEntryToken(hot, u)).isTrue();
        }
        assertThat(queueRepository.hasEntryToken(hot, 13L)).isFalse();
        assertThat(queueRepository.size(hot)).isEqualTo(8);
    }

    // --- 헬퍼 ---

    /** userId 1..n을 진입 순서(증가 score)대로 대기열에 넣는다. */
    private void enqueue(long eventId, int n) {
        for (long u = 1; u <= n; u++) {
            queueRepository.enqueue(eventId, u, 1_000L + u);
        }
    }

    private void deleteByPattern(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
