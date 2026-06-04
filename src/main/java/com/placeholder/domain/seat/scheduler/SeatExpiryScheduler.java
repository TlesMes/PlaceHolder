package com.placeholder.domain.seat.scheduler;

import com.placeholder.domain.seat.service.SeatExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 HELD 좌석을 주기적으로 해제하는 스케줄러 (Phase C-3, ADR-009).
 *
 * 스케줄러는 얇게 유지하고 실제 처리는 SeatExpiryService(@Transactional)에 위임한다.
 * 기존 lazy 만료(Seat.isHoldable)는 스캔 주기 사이의 빈틈을 보정하는 안전망으로 유지된다.
 */
@Component
@RequiredArgsConstructor
public class SeatExpiryScheduler {

    private final SeatExpiryService seatExpiryService;

    @Scheduled(fixedDelayString = "${seat.expiry.scan-interval-ms:60000}")
    public void releaseExpiredSeats() {
        seatExpiryService.releaseExpiredSeats();
    }
}
