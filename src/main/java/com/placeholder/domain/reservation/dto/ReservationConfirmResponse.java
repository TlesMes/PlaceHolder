package com.placeholder.domain.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ReservationConfirmResponse {
    private Long reservationId;
    private Long seatId;
    private int paidAmount;
    private LocalDateTime confirmedAt;
    private int remainingBalance;
}
