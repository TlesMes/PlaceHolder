package com.placeholder.domain.reservation.controller;

import com.placeholder.domain.reservation.dto.MyReservationsResponse;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class MyReservationsController {

    private final ReservationService reservationService;

    @PreAuthorize("hasRole('BOOKER')")
    @GetMapping("/my")
    public ResponseEntity<MyReservationsResponse> getMyReservations(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        MyReservationsResponse response =
                reservationService.getMyReservations(userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}
