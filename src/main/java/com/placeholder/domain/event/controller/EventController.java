package com.placeholder.domain.event.controller;

import com.placeholder.domain.event.dto.EventCreateRequest;
import com.placeholder.domain.event.dto.EventCreateResponse;
import com.placeholder.domain.event.dto.EventDetailResponse;
import com.placeholder.domain.event.dto.EventListResponse;
import com.placeholder.domain.event.service.EventService;
import com.placeholder.domain.seat.dto.SeatResponse;
import com.placeholder.domain.seat.service.SeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final SeatService seatService;

    /**
     * 이벤트 등록 (제공자)
     * Phase B-4: 임시로 providerId를 요청 파라미터로 받음
     * Phase B-2 이후: @AuthenticationPrincipal로 변경 예정
     */
    @PostMapping
    public ResponseEntity<EventCreateResponse> createEvent(
            @RequestParam Long providerId,
            @Valid @RequestBody EventCreateRequest request) {

        EventCreateResponse response = eventService.createEvent(providerId, request);
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
