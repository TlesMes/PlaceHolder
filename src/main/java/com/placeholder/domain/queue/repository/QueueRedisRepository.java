package com.placeholder.domain.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

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

    private static String queueKey(Long eventId) {
        return "queue:" + eventId;
    }

    private static String entryTokenKey(Long eventId, Long userId) {
        return "entry:" + eventId + ":" + userId;
    }

    /**
     * 대기열 진입. ZADD NX이므로 이미 줄 서 있으면 score(진입 시각)를 덮어쓰지 않아 FIFO 순번이 보존된다.
     *
     * @return 새로 진입했으면 true, 이미 대기 중이었으면 false
     */
    public boolean enqueue(Long eventId, Long userId, long timestampMillis) {
        Boolean added = redis.opsForZSet()
                .addIfAbsent(queueKey(eventId), String.valueOf(userId), timestampMillis);
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
}
