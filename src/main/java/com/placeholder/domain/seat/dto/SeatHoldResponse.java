package com.placeholder.domain.seat.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SeatHoldResponse {
    private Long seatId;
    private String status;
    private Long heldBy;
    private LocalDateTime heldUntil;
}
