package com.placeholder.domain.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 동기 승인 요청 — 프론트가 토스 결제창 성공 후(successUrl) 넘기는 값.
 * amount는 위변조 검증을 위해 서버 저장액과 대조된다(신뢰하지 않는다).
 */
@Getter
@NoArgsConstructor
public class PaymentConfirmRequest {

    @NotBlank(message = "orderId는 필수입니다")
    private String orderId;

    @NotBlank(message = "paymentKey는 필수입니다")
    private String paymentKey;

    @Min(value = 1, message = "amount는 1 이상이어야 합니다")
    private int amount;
}
