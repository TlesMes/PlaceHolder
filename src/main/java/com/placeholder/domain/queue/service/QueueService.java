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
    private final EventRepository eventRepository;

    /**
     * 대기열 진입 후 현재 상태를 반환한다. 이미 대기 중이면 순번은 유지된다(ZADD NX).
     */
    public QueueStatusResponse enter(Long eventId, Long userId) {
        requireEventExists(eventId);
        queueRepository.enqueue(eventId, userId, System.currentTimeMillis());
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
