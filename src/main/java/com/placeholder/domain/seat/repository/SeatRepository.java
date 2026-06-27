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
     * 대기열 게이트용: 좌석이 속한 이벤트의 id와 대기열 활성화 여부만 비잠금으로 조회한다 (E-1 3단계).
     * hold가 비관적 락(findByIdForUpdate)을 잡기 전에 fast-fail 판정하기 위함 — 락 보유 중
     * Redis I/O를 피한다.
     */
    @Query("select s.event.id as eventId, s.event.queueEnabled as queueEnabled " +
           "from Seat s where s.id = :seatId")
    Optional<SeatGateProjection> findGateInfoBySeatId(@Param("seatId") Long seatId);

    /**
     * 여러 이벤트의 좌석 통계(전체/AVAILABLE)를 GROUP BY로 한 번에 집계한다.
     * 이벤트 수에 무관하게 쿼리 1개 (ADR-011).
     * fetch join이 아니라 집계를 쓰는 이유: 카운트 목적에 좌석 전체 로딩은 낭비이고,
     * 단방향 설계(Event에 seats 컬렉션 없음)를 유지하기 위함.
     */
    @Query("select s.event.id as eventId, " +
           "count(s) as total, " +
           "sum(case when s.status = com.placeholder.domain.seat.entity.Seat.SeatStatus.AVAILABLE then 1L else 0L end) as available " +
           "from Seat s where s.event.id in :eventIds group by s.event.id")
    List<SeatCountProjection> countSeatsByEventIds(@Param("eventIds") List<Long> eventIds);

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
