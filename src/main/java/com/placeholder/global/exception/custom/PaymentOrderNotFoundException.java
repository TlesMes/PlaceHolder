package com.placeholder.global.exception.custom;

public class PaymentOrderNotFoundException extends RuntimeException {
    public PaymentOrderNotFoundException(String message) {
        super(message);
    }
}
