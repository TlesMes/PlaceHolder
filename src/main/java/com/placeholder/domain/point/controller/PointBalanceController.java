package com.placeholder.domain.point.controller;

import com.placeholder.domain.point.dto.PointBalanceResponse;
import com.placeholder.domain.point.service.PointBalanceService;
import com.placeholder.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보유 포인트 조회 (ADR-020).
 *
 * <p>PROVIDER는 대상이 아니다 — 제공자 잔액은 {@code settlement_balance}(정산예정액)이고
 * 재원 계층이라는 축이 없어 {@code /api/providers/my/settlement}가 따로 담당한다.
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointBalanceController {

    private final PointBalanceService pointBalanceService;

    @PreAuthorize("hasRole('BOOKER')")
    @GetMapping("/balance")
    public ResponseEntity<PointBalanceResponse> getBalance(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        return ResponseEntity.ok(pointBalanceService.getBalance(userDetails.getUserId()));
    }
}
