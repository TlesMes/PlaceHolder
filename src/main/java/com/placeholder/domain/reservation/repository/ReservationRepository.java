package com.placeholder.domain.reservation.repository;

import com.placeholder.domain.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByBookerId(Long bookerId);

    Optional<Reservation> findBySeatId(Long seatId);

    List<Reservation> findByBookerIdOrderByConfirmedAtDesc(Long bookerId);

    List<Reservation> findByConfirmedAtBetween(LocalDateTime start, LocalDateTime end);
}
