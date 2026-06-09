package com.placeholder.domain.coupon.controller;

import com.placeholder.domain.coupon.dto.CouponCreateRequest;
import com.placeholder.domain.coupon.dto.CouponCreateResponse;
import com.placeholder.domain.coupon.service.CouponAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 측정 전용 쿠폰 생성 엔드포인트.
 *
 * <p><b>@Profile("loadtest")</b> — 이 빈은 loadtest 프로파일에서만 등록된다. 운영/기본 프로파일에서는
 * 빈 자체가 없어 경로가 존재하지 않으므로(404) 보안 경계가 유지된다. ADMIN 인증 경로는 후속 Phase.
 *
 * <p>용도: Phase D-2 confirm 부하 측정 시 booker 잔액을 시드하기 위한 고액 쿠폰 발급
 * (k6 setup이 booker마다 전용 코드를 만들고 각자 1회 상환 → 유저당 1회 제약을 건드리지 않음).
 */
@Profile("loadtest")
@RestController
@RequestMapping("/api/loadtest/coupons")
@RequiredArgsConstructor
public class LoadTestCouponController {

    private final CouponAdminService couponAdminService;

    @PostMapping
    public ResponseEntity<CouponCreateResponse> create(
            @Valid @RequestBody CouponCreateRequest request) {

        CouponCreateResponse response =
                couponAdminService.create(request.getCode(), request.getAmount(), request.getMaxUses());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
