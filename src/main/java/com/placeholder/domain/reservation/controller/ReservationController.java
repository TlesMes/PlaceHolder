package com.placeholder.domain.reservation.controller;

import com.placeholder.domain.reservation.dto.ReservationConfirmResponse;
import com.placeholder.domain.reservation.service.ReservationService;
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
public class ReservationController {

    private final ReservationService reservationService;

    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/{seatId}/confirm")
    public ResponseEntity<ReservationConfirmResponse> confirmReservation(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long seatId) {

        ReservationConfirmResponse response =
                reservationService.confirmReservation(seatId, userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}
