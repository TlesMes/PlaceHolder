package com.placeholder.domain.queue.scheduler;

import com.placeholder.domain.queue.service.QueueAdmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;

/**
 * 배치 입장 스케줄러 (ADR-013, E-1 4단계).
 *
 * <p>스케줄러는 얇게 유지하고 실제 처리는 QueueAdmissionService에 위임한다(SeatExpiryScheduler와 동일 패턴).
 * Redis 장애 시에는 대기열 없이 hold가 그대로 통과(ADR-013 graceful degradation)하므로, 여기서
 * 발생하는 Redis 접근 예외는 삼키고 다음 틱에 재시도한다 — 스케줄러 스레드를 죽이지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAdmissionScheduler {

    private final QueueAdmissionService queueAdmissionService;

    @Scheduled(fixedDelayString = "${queue.admission.scan-interval-ms:1000}")
    public void admitWaiting() {
        try {
            queueAdmissionService.admitWaiting();
        } catch (DataAccessException e) {
            // Redis 접근 실패(다운 등): 대기열 없이 hold 직접 통과로 강등된 상태. 다음 틱에 재시도.
            log.warn("대기열 배치 입장 처리 실패 (Redis 접근 오류) — 다음 틱 재시도: {}", e.getMessage());
        }
    }
}
