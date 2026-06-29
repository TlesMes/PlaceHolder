package com.placeholder.domain.queue.service;

import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.queue.dto.QueueStatusResponse;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.global.exception.custom.EventNotFoundException;
import lombok.RequiredArgsConstructor;
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
                .build();
    }

    private void requireEventExists(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException("이벤트를 찾을 수 없습니다");
        }
    }
}
