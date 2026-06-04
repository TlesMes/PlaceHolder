package com.placeholder.domain.seat.service;

import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.entity.Seat.SeatStatus;
import com.placeholder.domain.seat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 만료된 HELD 좌석을 AVAILABLE로 되돌리는 만료 처리 서비스 (Phase C-3, ADR-009).
 *
 * 스케줄러(SeatExpiryScheduler)가 주기적으로 releaseExpiredSeats()를 호출한다.
 * 트랜잭션 경계를 스케줄러와 분리해 @Transactional self-invocation 함정을 피한다.
 *
 * 동시성 전략: 후보 ID를 락 없이 가볍게 조회한 뒤, 각 좌석을 findByIdForUpdate로
 * 개별 재잠금하고 락 보유 상태에서 만료 여부를 재확인한 다음 전이한다.
 * 만료 직전 들어온 confirm/재점유가 락을 먼저 잡으면 스케줄러는 변경된 상태를 보고
 * 건너뛰므로 CONFIRMED 좌석을 실수로 풀지 않는다 (ADR-008 비관적 락 기조와 일관).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatExpiryService {

    private final SeatRepository seatRepository;

    /**
     * 만료된 HELD 좌석을 AVAILABLE로 되돌린다.
     *
     * @return 실제로 해제된 좌석 수
     */
    @Transactional
    public int releaseExpiredSeats() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidateIds = seatRepository.findExpiredHeldSeatIds(SeatStatus.HELD, now);

        int released = 0;
        for (Long id : candidateIds) {
            Seat seat = seatRepository.findByIdForUpdate(id).orElse(null);
            if (seat == null) {
                continue;
            }
            // 락 획득 사이 confirm/재점유로 상태가 바뀌었을 수 있어 만료를 다시 판정한다.
            LocalDateTime lockedNow = LocalDateTime.now();
            if (seat.getStatus() == SeatStatus.HELD
                    && seat.getHeldUntil() != null
                    && seat.getHeldUntil().isBefore(lockedNow)) {
                seat.release();
                released++;
            }
        }

        if (released > 0) {
            log.info("만료된 HELD 좌석 {}건을 AVAILABLE로 해제했습니다 (후보 {}건)",
                    released, candidateIds.size());
        }
        return released;
    }
}
