package com.placeholder.domain.reservation.repository;

import com.placeholder.domain.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByBookerId(Long bookerId);

    Optional<Reservation> findBySeatId(Long seatId);

    List<Reservation> findByBookerIdOrderByConfirmedAtDesc(Long bookerId);

    List<Reservation> findByConfirmedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 예약 내역 조회용 fetch join — Reservation → Seat → Event 한 번에 로딩 (N+1 회피).
     * 사용자당 예약 수가 작은 도메인이라 List 반환으로 충분 (페이징 미적용, ADR-012).
     */
    @Query("select r from Reservation r " +
           "join fetch r.seat s " +
           "join fetch s.event " +
           "where r.booker.id = :bookerId " +
           "order by r.confirmedAt desc")
    List<Reservation> findMyReservationsWithSeatAndEvent(@Param("bookerId") Long bookerId);
}
