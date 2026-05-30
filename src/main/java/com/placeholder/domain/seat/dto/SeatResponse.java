package com.placeholder.domain.seat.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class SeatResponse {
    private Long eventId;
    private List<SeatInfo> seats;

    @Getter
    @Builder
    public static class SeatInfo {
        private Long seatId;
        private String label;
        private int price;
        private String status;
    }
}
