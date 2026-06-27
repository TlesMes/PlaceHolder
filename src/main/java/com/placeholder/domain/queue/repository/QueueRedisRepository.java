package com.placeholder.domain.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
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
 *   <li>{@code queue:{eventId}} — Sorted Set. score=진입 시각(ms), member=userId. FIFO 보장</li>
 *   <li>{@code entry:{eventId}:{userId}} — 입장 토큰(String, TTL). 스케줄러가 발급(E-1 4단계)</li>
 * </ul>
 *
 * 대기열은 정합성이 아니라 트래픽 셰이핑만 담당한다. 좌석 단위 정합성은 비관적 락(ADR-008)이 유지한다.
 */
@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final StringRedisTemplate redis;

    /** 대기열이 비어있지 않은(활성) 이벤트 id 집합. 스케줄러가 순회 대상으로 삼는다. */
    private static final String ACTIVE_QUEUES_KEY = "queue:active-events";

    private static String queueKey(Long eventId) {
        return "queue:" + eventId;
    }

    private static String entryTokenKey(Long eventId, Long userId) {
        return "entry:" + eventId + ":" + userId;
    }

    private static String entryTokenPattern(Long eventId) {
        return "entry:" + eventId + ":*";
    }

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

    /**
     * 현재 대기 순번(0-based rank). 대기열에 없으면 null.
     */
    public Long rank(Long eventId, Long userId) {
        return redis.opsForZSet().rank(queueKey(eventId), String.valueOf(userId));
    }

    /**
     * 전체 대기 인원.
     */
    public long size(Long eventId) {
        Long count = redis.opsForZSet().zCard(queueKey(eventId));
        return count == null ? 0L : count;
    }

    /**
     * 입장 토큰 발급. 입장 차례가 된 사용자에게 스케줄러가 호출한다(E-1 4단계).
     * 시간 박스형 — TTL 동안만 hold 진입을 허용하고, 만료되면 대기열 재진입이 필요하다(ADR-013).
     */
    public void issueEntryToken(Long eventId, Long userId, Duration ttl) {
        redis.opsForValue().set(entryTokenKey(eventId, userId), "1", ttl);
    }

    /**
     * 입장 토큰 보유 여부. hold 게이트가 이 값으로 진입을 판정한다(존재 확인, 단발 소비 아님).
     */
    public boolean hasEntryToken(Long eventId, Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(entryTokenKey(eventId, userId)));
    }

    // --- 배치 입장 스케줄러용 (E-1 4단계) ---

    /**
     * 대기열이 비어있지 않은(활성) 이벤트 id 목록. 스케줄러 순회 대상.
     */
    public Set<Long> activeQueueEventIds() {
        Set<String> members = redis.opsForSet().members(ACTIVE_QUEUES_KEY);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        return members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /**
     * 활성 집합에서 이벤트를 제거한다. 대기열이 빈 이벤트를 스케줄러가 정리할 때 호출한다.
     */
    public void unmarkActiveQueue(Long eventId) {
        redis.opsForSet().remove(ACTIVE_QUEUES_KEY, String.valueOf(eventId));
    }

    /**
     * 현재 유효한 입장 토큰 수(in-flight 입장자). SCAN으로 비차단 집계한다.
     *
     * <p>ADR-013 의사코드의 {@code current_held_count}를 "유효 입장 토큰 수"로 구체화했다.
     * HELD 좌석 수로 세면 토큰만 받고 아직 hold 안 한 입장자가 누락돼 한 틱 내 과다 입장이
     * 생길 수 있다. 입장 토큰 자체를 세면 hold 영역에 들어온 in-flight 인원을 정확히 반영한다.
     */
    public long countActiveEntryTokens(Long eventId) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(entryTokenPattern(eventId)).count(100).build();
        long count = 0;
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    /**
     * 대기열 맨 앞에서 최대 count명을 원자적으로 꺼낸다(ZPOPMIN). FIFO 보장.
     * 꺼낸 사용자는 대기열에서 제거되므로 이후 status 조회 시 position이 사라진다.
     *
     * @return 꺼낸 userId 목록(진입 순서)
     */
    public List<Long> popNextWaiting(Long eventId, long count) {
        if (count <= 0) {
            return List.of();
        }
        Set<TypedTuple<String>> popped = redis.opsForZSet().popMin(queueKey(eventId), count);
        if (popped == null || popped.isEmpty()) {
            return List.of();
        }
        // popMin은 score 오름차순(=진입 순서)을 보장한다.
        List<Long> userIds = new ArrayList<>(popped.size());
        for (TypedTuple<String> tuple : popped) {
            String value = tuple.getValue();
            if (value != null) {
                userIds.add(Long.valueOf(value));
            }
        }
        return userIds;
    }
}
