package com.placeholder.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 취소 요청 (ADR-019). 취소 금액은 받지 않는다 — 서버가 "주문 잔여액과 포인트 잔액 중 작은 쪽"으로
 * 확정한다(미사용분만 환불). 클라이언트가 보낸 금액을 신뢰하지 않는 원칙은 결제(ADR-018)와 동일하다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancelRequest {

    /** 취소 사유 — 토스에 그대로 전달되어 결제 내역에 남는다. */
    @NotBlank(message = "취소 사유는 필수입니다")
    @Size(max = 200, message = "취소 사유는 200자 이내여야 합니다")
    private String reason;
}
