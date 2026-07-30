package com.placeholder.domain.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 토스 결제 웹훅 페이로드 (필요한 필드만 매핑).
 *
 * <p>이 페이로드 자체는 신뢰하지 않는다 — orderId/paymentKey만 꺼내 실제 상태는 토스에 재조회해
 * 확인한다(위조 웹훅 방어, ADR-018).
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossWebhookPayload {

    private String eventType;
    private Data data;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        private String paymentKey;
        private String orderId;
        private String status;
    }
}
