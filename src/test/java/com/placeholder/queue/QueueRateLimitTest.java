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
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입장 rate 레버 검증 (ADR-013). ceiling을 넉넉히(100), rate를 작게(3) 두어 <b>rate가 단독으로</b>
 * 틱당 입장 수를 제한함을 본다. 같은 1초 윈도 안에선 rate 소진 후 추가 입장이 막히고, 윈도가 바뀌면(여기선
 * rate 키를 비워 모사) 회복된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "queue.admission.max-active-sessions=100",
        "queue.admission.rate-per-second=3"
})
class QueueRateLimitTest extends RedisIntegrationTest {

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
    @DisplayName("rate 단독: 한 윈도에 rate만큼만 입장, 윈도 갱신 후 회복")
    void rate_capsPerWindow_thenRecovers() {
        long eventId = uniqueId();
        for (long u = 1; u <= 10; u++) {
            queueRepository.enqueue(eventId, u, 1_000L + u);
        }

        // 1틱: ceiling(100) 여유에도 rate(3)에 막혀 3명만
        assertThat(admissionService.admitWaiting()).isEqualTo(3);
        assertThat(queueRepository.size(eventId)).isEqualTo(7);

        // 같은 윈도 재호출: rate 소진 → 0명
        assertThat(admissionService.admitWaiting()).isZero();
        assertThat(queueRepository.size(eventId)).isEqualTo(7);

        // 다음 초 모사(rate 윈도 갱신) → 다시 3명
        deleteByPattern("rate:*");
        assertThat(admissionService.admitWaiting()).isEqualTo(3);
        assertThat(queueRepository.size(eventId)).isEqualTo(4);
    }

    private void deleteByPattern(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
