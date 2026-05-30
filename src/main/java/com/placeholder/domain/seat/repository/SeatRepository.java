package com.placeholder.domain.seat.repository;

import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.entity.Seat.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventId(Long eventId);

    Optional<Seat> findByEventIdAndLabel(Long eventId, String label);

    List<Seat> findByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByStatusAndHeldUntilBefore(SeatStatus status, LocalDateTime dateTime);

    List<Seat> findByHeldByIdAndStatus(Long userId, SeatStatus status);
}
