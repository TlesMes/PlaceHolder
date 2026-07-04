package com.placeholder.domain.seat.controller;

import com.placeholder.domain.seat.dto.SeatHoldResponse;
import com.placeholder.domain.seat.dto.SeatReleaseResponse;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    /**
     * 좌석 홀드(점유) - 예약자만 가능
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/{seatId}/hold")
    public ResponseEntity<SeatHoldResponse> holdSeat(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long seatId) {

        SeatHoldResponse response = seatService.holdSeat(seatId, userDetails.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * 좌석 hold 반환(이탈 시 즉시 회수) - 예약자만 가능. 본인 홀드만 반환, 그 외는 멱등 no-op (ADR-016).
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/{seatId}/release")
    public ResponseEntity<SeatReleaseResponse> releaseSeat(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long seatId) {

        SeatReleaseResponse response = seatService.releaseSeat(seatId, userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}
