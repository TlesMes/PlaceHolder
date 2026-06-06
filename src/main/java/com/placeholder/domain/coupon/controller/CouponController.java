package com.placeholder.domain.coupon.controller;

import com.placeholder.domain.coupon.dto.CouponRedeemRequest;
import com.placeholder.domain.coupon.dto.CouponRedeemResponse;
import com.placeholder.domain.coupon.service.CouponRedeemService;
import com.placeholder.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class CouponController {

    private final CouponRedeemService couponRedeemService;

    /**
     * 캠페인 쿠폰 상환 → 포인트 적립 (예약자만 가능)
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/redeem")
    public ResponseEntity<CouponRedeemResponse> redeem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CouponRedeemRequest request) {

        CouponRedeemResponse response =
                couponRedeemService.redeem(request.getCode(), userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}
