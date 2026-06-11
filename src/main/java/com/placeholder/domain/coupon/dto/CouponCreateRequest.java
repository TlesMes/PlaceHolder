package com.placeholder.domain.coupon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 캠페인 쿠폰 생성 요청.
 *
 * <p>본래 ADMIN 경로의 입력이지만, 현재는 부하 측정용 잔액 시드 목적으로만 쓰인다
 * (LoadTestCouponController, @Profile("loadtest")). 운영 ADMIN 인증은 Phase 후속.
 */
@Getter
@NoArgsConstructor
public class CouponCreateRequest {

    @NotBlank(message = "쿠폰 코드는 필수입니다")
    private String code;

    @Min(value = 1, message = "적립 금액은 1 이상이어야 합니다")
    private int amount;

    @Min(value = 1, message = "최대 사용 횟수는 1 이상이어야 합니다")
    private int maxUses;
}
