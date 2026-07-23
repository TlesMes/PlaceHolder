package com.placeholder.domain.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 동기 승인 응답. 멱등 재요청 시에도 현재 잔액과 DONE 상태를 그대로 반환한다.
 */
@Getter
@Builder
public class PaymentConfirmResponse {
    private String orderId;
    private int chargedAmount;
    private int balance;
    private String status;
}
