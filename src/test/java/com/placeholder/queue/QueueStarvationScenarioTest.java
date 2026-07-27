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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기아 방지 장기 시나리오 실측 (ADR-017).
 *
 * <p>단위 테스트({@code QueueAdmissionServiceTest})가 한두 틱의 분배 정확성을 검증한다면, 여기서는
 * <b>실제 시계로 30틱+를 돌려</b> 입장 인원수의 시간 양상을 관측한다 — 핫 이벤트(200명)가 전역 rate를
 * 압도하는 동안에도 소형 이벤트(각 15명)가 매 윈도마다 전진하고(기아 부재), 소진 후에는 핫이 rate
 * 전량을 회수(work-conserving)하며, 어느 순간도 전역 캡을 넘지 않음을 시계열로 확인한다.
 *
 * <p>rate:{epochSec} 버킷이 실제 시계 기반이므로 각 틱을 초 경계에 정렬해 구동한다(틱당 1초,
 * 총 실행 ~33초 — 의도된 장기 테스트). ceiling은 1000으로 열어 rate 분배만 관측한다.
 */
@SpringBootTest(properties = {
        "queue.admission.rate-per-second=4",
        "queue.admission.max-active-sessions=1000"
})
@ActiveProfiles("test")
class QueueStarvationScenarioTest extends RedisIntegrationTest {

    private static final int RATE = 4;
    private static final int TICKS = 33;
    private static final int HOT_WAITING = 200;
    private static final int SMALL_WAITING = 15;

    @Autowired QueueAdmissionService admissionService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;

    // 테스트 간 Redis·deficit 장부 정리는 RedisIntegrationTest가 일괄 수행한다.
    // (이 테스트는 33틱을 돌며 장부에 끝수를 쌓으므로 장부 초기화가 특히 중요하다 — ADR-017)

    @Test
    @DisplayName("30초+ 실측: 핫 이벤트 압도 하에서도 소형 이벤트 기아 없음, 소진 후 rate 전량 회수")
    void starvationFree_over30SecondsRealTime() throws InterruptedException {
        long hot = uniqueId();
        long small1 = uniqueId();
        long small2 = uniqueId();
        enqueue(hot, HOT_WAITING);
        enqueue(small1, SMALL_WAITING);
        enqueue(small2, SMALL_WAITING);

        long[][] admittedPerTick = new long[TICKS][3]; // [tick][hot, small1, small2]
        long[][] remaining = new long[TICKS][3];
        long prevHot = HOT_WAITING;
        long prevS1 = SMALL_WAITING;
        long prevS2 = SMALL_WAITING;

        System.out.println("=== 기아 방지 시나리오: rate=4, hot 200명 vs small 15명×2 ===");
        for (int t = 0; t < TICKS; t++) {
            waitForNextSecond();
            admissionService.admitWaiting();

            long h = queueRepository.size(hot);
            long s1 = queueRepository.size(small1);
            long s2 = queueRepository.size(small2);
            admittedPerTick[t][0] = prevHot - h;
            admittedPerTick[t][1] = prevS1 - s1;
            admittedPerTick[t][2] = prevS2 - s2;
            remaining[t][0] = h;
            remaining[t][1] = s1;
            remaining[t][2] = s2;
            prevHot = h;
            prevS1 = s1;
            prevS2 = s2;
            System.out.printf("tick %2d | hot +%d (잔여 %3d) | small1 +%d (잔여 %2d) | small2 +%d (잔여 %2d)%n",
                    t, admittedPerTick[t][0], h, admittedPerTick[t][1], s1, admittedPerTick[t][2], s2);
        }

        // (캡 보장) 어느 틱도 전체 입장 합이 rate를 넘지 않는다
        for (int t = 0; t < TICKS; t++) {
            long tickTotal = admittedPerTick[t][0] + admittedPerTick[t][1] + admittedPerTick[t][2];
            assertThat(tickTotal).as("tick %d 전체 입장 ≤ rate", t).isLessThanOrEqualTo(RATE);
        }

        // (처리량 무손실) 대기자가 남아도는 동안 분배가 슬롯을 낭비하지 않는다: 총 입장 = rate × 틱수
        long total = 0;
        for (long[] tick : admittedPerTick) {
            total += tick[0] + tick[1] + tick[2];
        }
        assertThat(total).as("총 입장 = rate × 틱수").isEqualTo((long) RATE * TICKS);

        // (기아 부재) 소형 이벤트는 소진 전까지 어떤 3틱 윈도에서도 입장이 멈추지 않는다
        int s1Drained = drainedAt(remaining, 1);
        int s2Drained = drainedAt(remaining, 2);
        assertNoStarvationBeforeDrain(admittedPerTick, 1, s1Drained, "small1");
        assertNoStarvationBeforeDrain(admittedPerTick, 2, s2Drained, "small2");

        // 소형(15명, 몫 ≈4/3명/틱)은 16틱 안에 전원 입장 — 압도당해도 유한 대기
        assertThat(s1Drained).as("small1 소진 틱").isBetween(0, 16);
        assertThat(s2Drained).as("small2 소진 틱").isBetween(0, 16);

        // (work-conserving) 소형이 모두 소진된 뒤에는 핫이 rate 전량을 회수한다
        int bothDrained = Math.max(s1Drained, s2Drained);
        for (int t = bothDrained + 1; t < TICKS; t++) {
            assertThat(admittedPerTick[t][0]).as("tick %d 핫 전량 회수", t).isEqualTo(RATE);
        }
    }

    /** 해당 이벤트의 잔여가 처음 0이 된 틱. 소진 안 됐으면 실패 처리용 큰 값 대신 단언에서 걸리게 TICKS 반환. */
    private int drainedAt(long[][] remaining, int idx) {
        for (int t = 0; t < TICKS; t++) {
            if (remaining[t][idx] == 0) return t;
        }
        return TICKS;
    }

    /** 소진 이전 구간에서 3틱 연속 입장 0이면 기아로 판정한다. */
    private void assertNoStarvationBeforeDrain(long[][] perTick, int idx, int drainedTick, String name) {
        for (int start = 0; start + 3 <= drainedTick; start++) {
            long window = perTick[start][idx] + perTick[start + 1][idx] + perTick[start + 2][idx];
            assertThat(window)
                    .as("%s tick %d~%d 윈도 입장(기아 부재)", name, start, start + 2)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    /** 다음 초 경계까지 대기 — rate:{epochSec} 버킷을 틱마다 새로 시작시키기 위함. */
    private void waitForNextSecond() throws InterruptedException {
        long sec = System.currentTimeMillis() / 1000;
        while (System.currentTimeMillis() / 1000 == sec) {
            Thread.sleep(20);
        }
    }

    private void enqueue(long eventId, int n) {
        for (long u = 1; u <= n; u++) {
            queueRepository.enqueue(eventId, u, 1_000L + u);
        }
    }

}
