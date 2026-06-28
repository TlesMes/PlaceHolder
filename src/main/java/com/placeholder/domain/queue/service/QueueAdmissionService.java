package com.placeholder.domain.queue.service;

import com.placeholder.domain.queue.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 배치 입장 처리 서비스 (ADR-013, E-1 4단계).
 *
 * <p>활성 대기열을 순회하며 이벤트별 슬롯 여유분(max-concurrent-holds − in-flight 입장 토큰)만큼
 * 대기열 맨 앞에서 사용자를 꺼내(ZPOPMIN) 입장 토큰을 발급한다. 토큰을 받은 사용자는 status 폴링으로
 * admitted=true를 보고 hold로 진입한다(폴링 통지 — 추후 SSE 교체 가능).
 *
 * <p>스케줄러(QueueAdmissionScheduler)와 트랜잭션/타이밍 경계를 분리하기 위해 별도 서비스로 둔다.
 * 순수 Redis 연산이라 DB 트랜잭션은 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final QueueRedisRepository queueRepository;

    @Value("${queue.max-concurrent-holds:8}")
    private int maxConcurrentHolds;

    @Value("${queue.entry-token-ttl-minutes:5}")
    private int entryTokenTtlMinutes;

    /**
     * 활성 대기열 전체에 대해 슬롯 여유분만큼 입장 토큰을 발급한다.
     *
     * @return 이번 호출에서 발급한 입장 토큰 수
     */
    public int admitWaiting() {
        Set<Long> eventIds = queueRepository.activeQueueEventIds();
        int admitted = 0;
        for (Long eventId : eventIds) {
            admitted += admitForEvent(eventId);
        }
        if (admitted > 0) {
            log.info("대기열 입장 토큰 {}건 발급 (활성 이벤트 {}개)", admitted, eventIds.size());
        }
        return admitted;
    }

    private int admitForEvent(Long eventId) {
        // 대기열이 비었으면 활성 집합에서 정리하고 종료.
        if (queueRepository.size(eventId) == 0) {
            queueRepository.unmarkActiveQueue(eventId);
            return 0;
        }

        long slots = maxConcurrentHolds - queueRepository.countActiveEntryTokens(eventId);
        if (slots <= 0) {
            return 0;
        }

        List<Long> users = queueRepository.popNextWaiting(eventId, slots);
        Duration ttl = Duration.ofMinutes(entryTokenTtlMinutes);
        for (Long userId : users) {
            queueRepository.issueEntryToken(eventId, userId, ttl);
        }
        return users.size();
    }
}
