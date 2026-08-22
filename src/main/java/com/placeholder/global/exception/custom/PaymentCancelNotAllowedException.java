package com.placeholder.global.exception.custom;

/**
 * 결제 취소 불가 — 취소 가능 상태가 아니거나(READY/FAILED/이미 전액 취소), 취소 기한이 지났거나,
 * 환불 가능한 잔액이 남아 있지 않은 경우 (ADR-019).
 */
public class PaymentCancelNotAllowedException extends RuntimeException {
    public PaymentCancelNotAllowedException(String message) {
        super(message);
    }
}
