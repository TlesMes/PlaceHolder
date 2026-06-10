package com.placeholder.domain.point.controller;

import com.placeholder.domain.point.dto.PointHistoryResponse;
import com.placeholder.domain.point.service.PointHistoryService;
import com.placeholder.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointHistoryController {

    private final PointHistoryService pointHistoryService;

    @PreAuthorize("hasAnyRole('BOOKER', 'PROVIDER')")
    @GetMapping("/history")
    public ResponseEntity<PointHistoryResponse> getHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(required = false) Integer size) {

        PointHistoryResponse response = pointHistoryService.getHistory(
                userDetails.getUserId(), from, to, cursor, size);
        return ResponseEntity.ok(response);
    }
}
