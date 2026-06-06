package com.placeholder.global.exception.custom;

public class CouponAlreadyRedeemedByUserException extends RuntimeException {
    public CouponAlreadyRedeemedByUserException(String message) {
        super(message);
    }
}
