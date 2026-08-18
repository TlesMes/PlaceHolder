package com.placeholder.global.exception.custom;

/**
 * 토스 결제 취소 호출 실패 (ADR-019).
 *
 * <p>이 예외가 던져지면 선회수한 포인트를 반드시 복구해야 한다 — 돈은 안 돌아갔는데 포인트만
 * 사라진 상태를 남기면 사용자 순손실이다. 보상은 {@code PaymentCancelService}가 수행한다.
 */
public class PaymentCancelFailedException extends RuntimeException {
    public PaymentCancelFailedException(String message) {
        super(message);
    }

    public PaymentCancelFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
