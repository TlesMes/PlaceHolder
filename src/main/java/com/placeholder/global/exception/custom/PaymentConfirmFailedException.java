package com.placeholder.global.exception.custom;

/**
 * 토스 결제 승인(confirm) 실패 — 토스가 승인을 거절했거나 호출이 실패함 (ADR-018).
 * 이 예외가 던져지면 주문은 FAILED로 전이하고 포인트는 적립되지 않는다.
 */
public class PaymentConfirmFailedException extends RuntimeException {
    public PaymentConfirmFailedException(String message) {
        super(message);
    }

    public PaymentConfirmFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
