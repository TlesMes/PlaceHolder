package com.placeholder.global.exception.custom;

/**
 * confirm 요청 금액이 주문 시 서버가 저장한 금액과 다름 = 금액 위변조 의심 (ADR-018).
 */
public class PaymentAmountMismatchException extends RuntimeException {
    public PaymentAmountMismatchException(String message) {
        super(message);
    }
}
