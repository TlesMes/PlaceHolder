package com.placeholder.domain.payment.client;

/**
 * 토스 결제 조회/승인 응답의 최소 표현. 우리가 실제로 쓰는 필드만 담는다.
 * {@code status}가 "DONE"이면 결제가 최종 승인된 상태다.
 *
 * @param balanceAmount 토스 기준 <b>취소 가능 잔액</b>. 취소가 없으면 {@code totalAmount}와 같고,
 *                      취소될 때마다 그만큼 줄어든다. 역방향 대사가 "토스는 얼마나 취소했나"를
 *                      아는 유일한 경로다 (ADR-019).
 */
public record TossPaymentResult(String paymentKey, String orderId, String status,
                                int totalAmount, int balanceAmount) {

    /**
     * 취소 이력이 없는 결제용 편의 생성자 — {@code balanceAmount = totalAmount}.
     *
     * <p>승인(confirm) 직후 응답처럼 아직 취소가 있을 수 없는 지점에서 쓰라고 둔 것이다.
     * 취소 상태를 다루는 쪽은 5-인자 생성자로 실제 잔액을 명시해야 한다.
     */
    public TossPaymentResult(String paymentKey, String orderId, String status, int totalAmount) {
        this(paymentKey, orderId, status, totalAmount, totalAmount);
    }

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

    /**
     * 토스 기준 누적 취소 금액 = 총액 − 취소가능 잔액.
     *
     * <p>역방향 대사가 우리 장부({@code PaymentOrder.canceledAmount})와 대조하는 값이다.
     * 우리 쪽이 더 크면 그 차액만큼 취소 요청이 토스에 도달하지 못했다는 뜻이다.
     */
    public int canceledAmount() {
        return totalAmount - balanceAmount;
    }
}
