package com.placeholder.domain.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 대기열 Redis 접근 계층 (ADR-013).
 *
 * <p>키 설계:
 * <ul>
 *   <li>{@code queue:{eventId}} — 대기열 Sorted Set. score=진입 시각(ms), member=userId. FIFO 보장</li>
 *   <li>{@code entry:{eventId}:{userId}} — 입장 토큰(String, TTL). 게이트가 읽는 source</li>
 *   <li>{@code active:all} — 전역 활성 세션 Sorted Set. score=만료시각(ms), member="{eventId}:{userId}".
 *       ceiling 판정을 위한 원자 카운팅용</li>
 *   <li>{@code rate:{epochSec}} — 전역 초당 입장 카운터(admit.lua가 관리)</li>
 * </ul>
 *
 * <p>입장 제어(ceiling + rate)는 {@code admit.lua}가 원자적으로 수행한다(다중 인스턴스에서도 캡 정확).
 * 대기열은 트래픽 셰이핑만, 좌석 단위 정합성은 비관적 락(ADR-008)이 유지한다.
 */
@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final StringRedisTemplate redis;

    /** 전역 활성 세션 ZSET. ceiling 판정의 원자 카운팅 대상. */
    private static final String ACTIVE_ALL_KEY = "active:all";

    /** 대기열이 비어있지 않은(활성) 이벤트 id 집합. 스케줄러가 순회 대상으로 삼는다. */
    private static final String ACTIVE_QUEUES_KEY = "queue:active-events";

    /** 원자 입장 스크립트(admit.lua) — check-then-act 전체를 EVAL 1회로 원자화. */
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> admitScript = buildAdmitScript();

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> buildAdmitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/admit.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static String queueKey(Long eventId) {
        return "queue:" + eventId;
    }

    private static String entryTokenKey(Long eventId, Long userId) {
        return "entry:" + eventId + ":" + userId;
    }

    private static String activeMember(Long eventId, Long userId) {
        return eventId + ":" + userId;
    }

    // --- 진입 / 조회 ---

    /**
     * 대기열 진입. ZADD NX이므로 이미 줄 서 있으면 score(진입 시각)를 덮어쓰지 않아 FIFO 순번이 보존된다.
     *
     * @return 새로 진입했으면 true, 이미 대기 중이었으면 false
     */
    public boolean enqueue(Long eventId, Long userId, long timestampMillis) {
        Boolean added = redis.opsForZSet()
                .addIfAbsent(queueKey(eventId), String.valueOf(userId), timestampMillis);
        // 스케줄러가 KEYS 스캔 없이 활성 대기열만 순회하도록 활성 집합에 등록한다.
        redis.opsForSet().add(ACTIVE_QUEUES_KEY, String.valueOf(eventId));
        return Boolean.TRUE.equals(added);
    }

    /** 현재 대기 순번(0-based rank). 대기열에 없으면 null. */
    public Long rank(Long eventId, Long userId) {
        return redis.opsForZSet().rank(queueKey(eventId), String.valueOf(userId));
    }

    /** 전체 대기 인원. */
    public long size(Long eventId) {
        Long count = redis.opsForZSet().zCard(queueKey(eventId));
        return count == null ? 0L : count;
    }

    // --- 입장 토큰 (게이트 source) ---

    /**
     * 입장 토큰 발급. 일반 입장 경로는 {@link #admit}가 원자적으로 처리하고, 이 메서드는
     * 테스트/사전상태 구성용이다. 게이트용 String 토큰과 전역 활성 ZSET을 함께 기록해 일관성을 유지한다.
     */
    public void issueEntryToken(Long eventId, Long userId, Duration ttl) {
        long expiry = System.currentTimeMillis() + ttl.toMillis();
        redis.opsForZSet().add(ACTIVE_ALL_KEY, activeMember(eventId, userId), expiry);
        redis.opsForValue().set(entryTokenKey(eventId, userId), "1", ttl);
    }

    /** 입장 토큰 보유 여부. 게이트가 이 값으로 진입을 판정한다(존재 확인, 단발 소비 아님). */
    public boolean hasEntryToken(Long eventId, Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(entryTokenKey(eventId, userId)));
    }

    /** 현재 유효한 전역 활성 세션 수(만료 score 제외). ceiling 검증/관측용. */
    public long activeCount() {
        Long c = redis.opsForZSet()
                .count(ACTIVE_ALL_KEY, System.currentTimeMillis() + 1, Double.POSITIVE_INFINITY);
        return c == null ? 0L : c;
    }

    // --- 원자 입장 (admit.lua) ---

    /**
     * 대기열 맨 앞에서 ceiling·rate 한도 내 최대 {@code max}명을 원자적으로 입장시킨다.
     * 스케줄러와 enter() fast-path가 공유한다 — 동시 호출/다중 인스턴스에서도 캡이 정확하다.
     *
     * @return 입장시킨 userId 목록(진입 순서)
     */
    @SuppressWarnings("unchecked")
    public List<Long> admit(Long eventId, long now, int ceiling, int ratePerSec, long ttlMs, int max) {
        List<String> keys = List.of(queueKey(eventId), ACTIVE_ALL_KEY);
        List<Object> raw = redis.execute(admitScript, keys,
                String.valueOf(eventId), String.valueOf(now), String.valueOf(ceiling),
                String.valueOf(ratePerSec), String.valueOf(ttlMs), String.valueOf(max));
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(raw.size());
        for (Object o : raw) {
            ids.add(Long.valueOf(o.toString()));
        }
        return ids;
    }

    // --- 배치 입장 스케줄러용 ---

    /** 대기열이 비어있지 않은(활성) 이벤트 id 목록. 스케줄러 순회 대상. */
    public Set<Long> activeQueueEventIds() {
        Set<String> members = redis.opsForSet().members(ACTIVE_QUEUES_KEY);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        return members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /** 활성 집합에서 이벤트를 제거한다. 대기열이 빈 이벤트를 스케줄러가 정리할 때 호출한다. */
    public void unmarkActiveQueue(Long eventId) {
        redis.opsForSet().remove(ACTIVE_QUEUES_KEY, String.valueOf(eventId));
    }
}
