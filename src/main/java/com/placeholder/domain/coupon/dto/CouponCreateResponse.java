package com.placeholder.domain.coupon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CouponCreateResponse {
    private Long couponId;
    private String code;
    private int amount;
    private int maxUses;
}
