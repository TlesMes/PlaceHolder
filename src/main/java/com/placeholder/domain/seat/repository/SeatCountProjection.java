package com.placeholder.domain.seat.repository;

/**
 * 이벤트별 좌석 집계 결과 (Phase D-1 after).
 * countSeatsByEventIds의 GROUP BY 결과를 담는 projection.
 */
public interface SeatCountProjection {
    Long getEventId();
    long getTotal();
    long getAvailable();
}
