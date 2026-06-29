package com.placeholder.domain.queue.service;

import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.dto.QueueStatusResponse;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.global.exception.custom.EventNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 대기열 트래픽 셰이핑 서비스 (ADR-013).
 *
 * <p>이벤트별 독립 대기열({@code queue:{eventId}})에 진입/순번 조회를 제공한다. 입장 토큰 발급은
 * 배치 입장 스케줄러(E-1 4단계)가 담당하고, 여기서는 토큰 보유 여부만 조회한다.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueRedisRepository queueRepository;
    private final QueueAdmissionService admissionService;
    private final EventRepository eventRepository;

    /** 입장 속도(초당). 예상 대기시간 = 앞 인원 / rate 계산에 사용. */
    @Value("${queue.admission.rate-per-second:20}")
    private int ratePerSecond;

    /** 폴링 주기 하한(ms) — 앞쪽 대기자가 과도하게 자주 폴링하지 않도록. */
    @Value("${queue.status.min-poll-ms:2000}")
    private long minPollMs;

    /** 폴링 주기 상한(ms) — 뒤쪽 대기자도 화면이 멈춘 느낌이 들지 않도록. */
    @Value("${queue.status.max-poll-ms:10000}")
    private long maxPollMs;

    /**
     * 대기열 진입 후 현재 상태를 반환한다. 이미 대기 중이면 순번은 유지된다(ZADD NX).
     *
     * <p>진입 직후 fast-path로 즉시 입장 1건을 시도한다 — 빈자리가 있으면 다음 스케줄러 틱(최대 1초)을
     * 기다리지 않고 대기열 맨 앞이 바로 입장한다. 호출자가 맨 앞이면 응답의 {@code admitted=true}로
     * 즉시 hold 진행이 가능하다. ceiling·rate 판정은 Lua가 원자 처리하므로 스케줄러와 동시 호출돼도 안전.
     */
    public QueueStatusResponse enter(Long eventId, Long userId) {
        requireEventExists(eventId);
        queueRepository.enqueue(eventId, userId, System.currentTimeMillis());
        admissionService.admitForEvent(eventId, 1);
        return buildStatus(eventId, userId);
    }

    /**
     * 현재 대기 순번 + 입장 토큰 보유 여부 조회.
     */
    public QueueStatusResponse status(Long eventId, Long userId) {
        requireEventExists(eventId);
        return buildStatus(eventId, userId);
    }

    private QueueStatusResponse buildStatus(Long eventId, Long userId) {
        Long rank = queueRepository.rank(eventId, userId);
        Long position = (rank == null) ? null : rank + 1;
        return QueueStatusResponse.builder()
                .eventId(eventId)
                .position(position)
                .waiting(queueRepository.size(eventId))
                .admitted(queueRepository.hasEntryToken(eventId, userId))
                .nextPollDelayMs(pollDelayMs(position))
                .build();
    }

    /**
     * 다음 폴링까지 권장 대기(ms). 내 앞 인원을 입장 속도(rate)로 나눈 "최소 예상 대기시간"을 쓴다 —
     * rate가 입장 상한이라 그 전엔 들어갈 수 없으므로 그동안 폴링은 낭비다. position이 줄수록 간격도
     * 자동으로 짧아져 입장 임박 시 촘촘해진다. [minPollMs, maxPollMs]로 클램프.
     */
    private long pollDelayMs(Long position) {
        if (position == null) {
            return minPollMs; // 대기열에 없음(입장/이탈) — 의미 없는 값
        }
        long ahead = Math.max(0, position - 1);
        long estMs = ahead * 1000L / Math.max(1, ratePerSecond);
        return Math.min(maxPollMs, Math.max(minPollMs, estMs));
    }

    private void requireEventExists(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException("이벤트를 찾을 수 없습니다");
        }
    }
}
