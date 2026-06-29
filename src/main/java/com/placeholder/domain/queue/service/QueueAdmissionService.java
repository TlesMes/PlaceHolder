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
 * 배치 입장 처리 서비스 (ADR-013).
 *
 * <p>활성 대기열을 순회하며 원자 입장 스크립트({@link QueueRedisRepository#admit})를 호출한다. 입장 제어는
 * 두 레버를 함께 쓴다 — <b>ceiling</b>(동시 활성 세션 전역 상한, 앱 용량 기준)과 <b>rate</b>(초당 입장 수).
 * 캡 판정·ZPOPMIN·토큰 발급은 Lua가 원자적으로 처리하므로, 다중 인스턴스가 동시에 돌아도 상한이 정확하다.
 *
 * <p>스케줄러(QueueAdmissionScheduler)와 타이밍 경계를 분리하기 위해 별도 서비스로 둔다. 순수 Redis 연산.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final QueueRedisRepository queueRepository;

    /** 동시 활성 세션(입장 토큰 보유) 전역 상한 (C). DB 풀이 아니라 앱 세션 용량 기준. */
    @Value("${queue.admission.max-active-sessions:200}")
    private int maxActiveSessions;

    /** 초당 입장 허용 수 (R). 유입 스파이크 평탄화. */
    @Value("${queue.admission.rate-per-second:20}")
    private int ratePerSecond;

    @Value("${queue.entry-token-ttl-minutes:5}")
    private int entryTokenTtlMinutes;

    /**
     * 활성 대기열 전체에 대해 ceiling·rate 한도 내에서 입장 토큰을 발급한다.
     *
     * @return 이번 호출에서 발급한 입장 토큰 수
     */
    public int admitWaiting() {
        Set<Long> eventIds = queueRepository.activeQueueEventIds();
        long now = System.currentTimeMillis();
        long ttlMs = Duration.ofMinutes(entryTokenTtlMinutes).toMillis();
        int admitted = 0;
        for (Long eventId : eventIds) {
            // 빈 대기열은 활성 집합에서 정리.
            if (queueRepository.size(eventId) == 0) {
                queueRepository.unmarkActiveQueue(eventId);
                continue;
            }
            // ceiling·rate는 Lua가 전역 기준으로 판정한다. max는 이 호출의 상한(틱당 rate 이내).
            List<Long> users = queueRepository.admit(
                    eventId, now, maxActiveSessions, ratePerSecond, ttlMs, ratePerSecond);
            admitted += users.size();
        }
        if (admitted > 0) {
            log.info("대기열 입장 토큰 {}건 발급 (활성 이벤트 {}개)", admitted, eventIds.size());
        }
        return admitted;
    }
}
