package com.placeholder.domain.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class MyReservationsResponse {
    private List<ReservationSummary> reservations;

    @Getter
    @Builder
    public static class ReservationSummary {
        private Long reservationId;
        private Long eventId;
        private String eventTitle;
        private String eventVenue;
        private LocalDateTime eventAt;
        private Long seatId;
        private String seatLabel;
        private int paidAmount;
        private LocalDateTime confirmedAt;
    }
}
