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
     * 이벤트의 좌석 목록 조회.
     *
     * <p>queueEnabled 이벤트는 입장 토큰이 있어야 좌석 그리드를 반환한다(A안, ADR-013 개정) —
     * 좌석 폴링 부하도 ceiling으로 바운드하기 위함. 경로는 permitAll이라 비로그인은 principal이
     * null이며, 게이트는 SeatService가 판정한다(토큰 없으면 429). 비-큐 이벤트는 종전대로 자유 조회.
     */
    @GetMapping("/{eventId}/seats")
    public ResponseEntity<SeatResponse> getSeats(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long eventId) {
        Long bookerId = (userDetails != null) ? userDetails.getUserId() : null;
        SeatResponse response = seatService.getSeatsResponse(eventId, bookerId);
        return ResponseEntity.ok(response);
    }
}
