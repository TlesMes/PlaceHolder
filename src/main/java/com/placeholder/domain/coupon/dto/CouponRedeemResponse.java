package com.placeholder.domain.coupon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CouponRedeemResponse {
    private int chargedAmount;
    private int balance;
}
