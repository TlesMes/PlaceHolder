package com.placeholder.domain.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CouponRedeemRequest {

    @NotBlank(message = "쿠폰 코드는 필수입니다")
    private String code;
}
