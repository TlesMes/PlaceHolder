package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 웹훅 — 결제의 보조 경로 (ADR-018). confirm 누락(예: 사용자가 successUrl 도달 전 이탈)을 보정한다.
 *
 * <p><b>페이로드를 신뢰하지 않는다.</b> 웹훅은 인증 없이(permitAll) 열려 있으므로 누구나 위조 페이로드를
 * 보낼 수 있다. 따라서 페이로드에서 paymentKey만 꺼내 토스에 <b>실제 상태를 재조회</b>하고, 진짜로
 * 결제 완료(DONE)일 때만 적립한다. 적립은 confirm과 동일한 멱등 {@link PaymentSettlementService#settle}로
 * 수렴하므로 confirm이 이미 처리했으면 no-op이다.
 *
 * <p>이 메서드도 {@code @Transactional}이 아니다 — 외부 재조회 호출을 트랜잭션 밖에 두기 위함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final PaymentSettlementService settlementService;
    private final TossPaymentClient tossClient;

    public void handle(TossWebhookPayload payload) {
        if (payload.getData() == null
                || payload.getData().getPaymentKey() == null
                || payload.getData().getOrderId() == null) {
            log.warn("웹훅 페이로드에 paymentKey/orderId 없음 — 무시 (eventType={})", payload.getEventType());
            return;
        }

        String paymentKey = payload.getData().getPaymentKey();
        String orderId = payload.getData().getOrderId();

        // 페이로드 불신 → 토스에 실제 상태 재조회 (위조 웹훅 방어)
        TossPaymentResult actual = tossClient.getPayment(paymentKey);
        if (!actual.isDone()) {
            log.info("웹훅 재조회 결과 미완료 상태 — 적립 생략 (orderId={}, status={})",
                    orderId, actual.status());
            return;
        }

        // 멱등 적립 — confirm이 이미 처리했으면 no-op
        settlementService.settle(orderId, paymentKey);
    }
}
