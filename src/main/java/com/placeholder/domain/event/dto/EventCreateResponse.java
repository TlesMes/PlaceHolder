package com.placeholder.domain.event.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class EventCreateResponse {
    private Long eventId;
    private String title;
    private int createdSeatCount;
}
