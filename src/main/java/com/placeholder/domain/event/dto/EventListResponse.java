package com.placeholder.domain.event.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class EventListResponse {
    private List<EventSummary> events;

    @Getter
    @Builder
    public static class EventSummary {
        private Long eventId;
        private String title;
        private String venue;
        private LocalDateTime eventAt;
        private int totalSeats;
        private int availableSeats;
    }
}
