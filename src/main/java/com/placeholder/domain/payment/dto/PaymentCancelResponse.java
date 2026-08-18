package com.placeholder.domain.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 결제 취소 응답 (ADR-019).
 *
 * <p>{@code canceledAmount}(이번 취소액)와 {@code totalCanceledAmount}(누적)를 나눠 준다 —
 * 부분 취소를 여러 번 할 수 있어 "이번에 얼마 돌려받았나"와 "이 결제에서 지금까지 얼마가 취소됐나"가
 * 다른 값이기 때문이다.
 */
@Getter
@Builder
public class PaymentCancelResponse {
    private String orderId;
    /** 이번 호출에서 취소된 금액(= 회수된 포인트). */
    private int canceledAmount;
    /** 이 주문의 누적 취소 금액. */
    private int totalCanceledAmount;
    /** 취소 후 포인트 잔액. */
    private int balance;
    /** CANCELED(전액) 또는 PARTIAL_CANCELED(일부). */
    private String status;
}
