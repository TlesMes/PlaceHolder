package com.placeholder.domain.event.controller;

import com.placeholder.domain.event.dto.EventCreateRequest;
import com.placeholder.domain.event.dto.EventCreateResponse;
import com.placeholder.domain.event.dto.EventDetailResponse;
import com.placeholder.domain.event.dto.EventListResponse;
import com.placeholder.domain.event.service.EventService;
import com.placeholder.domain.seat.dto.SeatResponse;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final SeatService seatService;

    @PostMapping
    public ResponseEntity<EventCreateResponse> createEvent(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody EventCreateRequest request) {

        EventCreateResponse response = eventService.createEvent(userDetails.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 이벤트 목록 조회 (예약자)
     */
    @GetMapping
    public ResponseEntity<EventListResponse> getEvents() {
        EventListResponse response = eventService.getEvents();
        return ResponseEntity.ok(response);
    }

    /**
     * 이벤트 상세 조회
     */
    @GetMapping("/{id}")
    public ResponseEntity<EventDetailResponse> getEventDetail(@PathVariable Long id) {
        EventDetailResponse response = eventService.getEventDetail(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 이벤트의 좌석 목록 조회
     */
    @GetMapping("/{eventId}/seats")
    public ResponseEntity<SeatResponse> getSeats(@PathVariable Long eventId) {
        SeatResponse response = seatService.getSeatsResponse(eventId);
        return ResponseEntity.ok(response);
    }
}
