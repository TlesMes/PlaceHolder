package com.placeholder.domain.seat.repository;

import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.entity.Seat.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * 좌석 행에 비관적 쓰기 락(SELECT ... FOR UPDATE)을 걸고 조회한다.
     * 동시 홀드 요청 시 한 트랜잭션만 락을 보유하고 나머지는 대기한다 (ADR-008).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :seatId")
    Optional<Seat> findByIdForUpdate(@Param("seatId") Long seatId);

    List<Seat> findByEventId(Long eventId);

    /**
     * [Phase D-1 before] 이벤트의 전체 좌석 수. 목록 조회에서 이벤트마다 호출 시 N+1 유발.
     */
    int countByEventId(Long eventId);

    /**
     * [Phase D-1 before] 이벤트의 특정 상태 좌석 수. 목록 조회에서 이벤트마다 호출 시 N+1 유발.
     */
    int countByEventIdAndStatus(Long eventId, SeatStatus status);

    Optional<Seat> findByEventIdAndLabel(Long eventId, String label);

    List<Seat> findByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByStatusAndHeldUntilBefore(SeatStatus status, LocalDateTime dateTime);

    /**
     * 만료된 HELD 좌석의 ID만 가볍게 조회한다 (락 없음).
     * 스케줄러가 후보 ID를 모은 뒤 각 행을 findByIdForUpdate로 개별 재잠금한다 (ADR-009).
     * 인덱스 idx_seats_status_held_until 활용.
     */
    @Query("select s.id from Seat s where s.status = :status and s.heldUntil < :now")
    List<Long> findExpiredHeldSeatIds(@Param("status") SeatStatus status,
                                      @Param("now") LocalDateTime now);

    List<Seat> findByHeldByIdAndStatus(Long userId, SeatStatus status);
}
