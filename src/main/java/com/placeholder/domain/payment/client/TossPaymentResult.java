package com.placeholder.domain.payment.client;

/**
 * 토스 결제 조회/승인 응답의 최소 표현. 우리가 실제로 쓰는 필드만 담는다.
 * {@code status}가 "DONE"이면 결제가 최종 승인된 상태다.
 */
public record TossPaymentResult(String paymentKey, String orderId, String status, int totalAmount) {

    public boolean isDone() {
        return "DONE".equals(status);
    }
}
