package com.placeholder.domain.payment.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 결제·환불 내역 (ADR-019).
 *
 * <p>이 엔드포인트가 생기기 전까지 결제 상태를 볼 수 있는 유일한 창구는 취소 API의 <b>동기 응답</b>
 * 이었다. 그런데 우리가 메우려는 크래시 창은 정의상 그 응답이 사용자에게 도달하지 못한 경우라,
 * 동기 응답으로는 "환불이 아직 안 나갔다"를 결코 알릴 수 없었다. 나중에 다시 봤을 때 보이는
 * 화면이 필요하다.
 */
@Getter
@Builder
public class MyPaymentsResponse {

    private List<PaymentSummary> payments;

    @Getter
    @Builder
    public static class PaymentSummary {
        private String orderId;
        private int amount;
        /** 주문 상태 (READY/DONE/FAILED/EXPIRED/PARTIAL_CANCELED/CANCELED). */
        private String status;
        private int canceledAmount;
        private LocalDateTime createdAt;
        private LocalDateTime approvedAt;
        /** 우리가 취소를 기록한 시각 — 포인트가 회수된 시점. */
        private LocalDateTime canceledAt;
        /**
         * 환불 진행 상태 (NONE/PENDING/COMPLETED) — 저장된 값이 아니라 두 시각에서 파생한다.
         *
         * <p>{@code PENDING}은 "포인트는 빠져나갔는데 현금은 아직 안 돌아갔다"는 뜻이다.
         * 이 값이 없으면 화면은 포인트 이력만 보고 "환불 완료"라고 말하게 된다.
         */
        private String refundStatus;
    }
}
