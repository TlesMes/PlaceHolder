package com.placeholder.domain.event.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class EventDetailResponse {
    private Long eventId;
    private String title;
    private String venue;
    private LocalDateTime eventAt;
    private boolean queueEnabled;
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
