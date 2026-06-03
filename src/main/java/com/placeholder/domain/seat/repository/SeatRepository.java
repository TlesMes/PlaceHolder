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

    Optional<Seat> findByEventIdAndLabel(Long eventId, String label);

    List<Seat> findByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByStatusAndHeldUntilBefore(SeatStatus status, LocalDateTime dateTime);

    List<Seat> findByHeldByIdAndStatus(Long userId, SeatStatus status);
}
