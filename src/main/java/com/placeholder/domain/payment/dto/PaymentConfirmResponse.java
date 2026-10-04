package com.placeholder.domain.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 동기 승인 응답. {@code status}는 주문의 실제 상태다.
 *
 * <p>{@code IN_PROGRESS}면 승인 결과를 아직 모른다 — 토스 응답을 못 받았거나 다른 요청이 처리 중이다.
 * 이때 {@code chargedAmount}는 0이며, 적립은 웹훅·대사가 확정한다 (ADR-022). 실패가 아니다.
 */
@Getter
@Builder
public class PaymentConfirmResponse {
    private String orderId;
    private int chargedAmount;
    private int balance;
    private String status;
}
