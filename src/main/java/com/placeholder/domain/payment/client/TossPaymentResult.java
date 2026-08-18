package com.placeholder.domain.payment.client;

/**
 * 토스 결제 조회/승인 응답의 최소 표현. 우리가 실제로 쓰는 필드만 담는다.
 * {@code status}가 "DONE"이면 결제가 최종 승인된 상태다.
 */
public record TossPaymentResult(String paymentKey, String orderId, String status, int totalAmount) {

    public boolean isDone() {
        return "DONE".equals(status);
    }

    /**
     * 취소가 반영된 상태인가 (전액 {@code CANCELED} 또는 일부 {@code PARTIAL_CANCELED}).
     *
     * <p>취소 호출이 예외 없이 200을 돌려줬다는 사실만으로 "돈이 돌아갔다"고 볼 수 없다 —
     * 상태를 직접 확인해야 한다(ADR-019). 확인 없이 성공으로 기록하면 취소되지 않은 결제를
     * 취소로 장부에 남기게 된다.
     */
    public boolean isCanceled() {
        return "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status);
    }
}
