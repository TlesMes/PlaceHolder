package com.placeholder.domain.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 주문 생성 응답. 프론트는 이 orderId·amount와 clientKey(공개)로 토스 결제창을 연다.
 */
@Getter
@Builder
public class PaymentOrderCreateResponse {
    private String orderId;
    private int amount;
    private String clientKey;
}
