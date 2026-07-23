package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.PaymentConfirmResponse;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.service.PaymentSettlementService.SettleResult;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 동기 승인 — 결제의 주 경로 (ADR-018).
 *
 * <p><b>트랜잭션 경계 설계가 핵심.</b> 이 메서드 자체는 {@code @Transactional}이 아니다 — 외부 토스
 * 호출을 트랜잭션 안에 넣으면 네트워크 지연만큼 DB 커넥션을 점유하고 락 보유 중 데드락 위험이 생기기
 * 때문. 대신 짧은 트랜잭션(검증 → 외부 호출 밖 → 멱등 적립)을 {@link PaymentSettlementService}의
 * 개별 트랜잭션 메서드로 나눠 호출한다.
 *
 * <p>순서: ① 사전 검증(금액·본인·존재) → ② 트랜잭션 밖에서 토스 confirm → ③ 멱등 settle.
 * 이미 확정된 주문(멱등 재요청)은 토스 호출을 건너뛰고 바로 settle로 현재 잔액을 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmService {

    private final PaymentSettlementService settlementService;
    private final TossPaymentClient tossClient;

    public PaymentConfirmResponse confirm(String orderId, String paymentKey, int amount, Long userId) {
        // ① 사전 검증 (외부 호출 전, 락 없이 fail-fast). 이미 DONE이면 토스 호출 생략.
        boolean alreadyDone = settlementService.validateBeforeConfirm(orderId, amount, userId);

        // ② 외부 토스 승인 — 어떤 트랜잭션에도 들어가지 않는다
        if (!alreadyDone) {
            TossPaymentResult result;
            try {
                result = tossClient.confirm(paymentKey, orderId, amount);
            } catch (PaymentConfirmFailedException e) {
                settlementService.markFailed(orderId);
                throw e;
            }
            if (!result.isDone()) {
                settlementService.markFailed(orderId);
                throw new PaymentConfirmFailedException(
                        "토스 결제가 승인 상태가 아닙니다: status=" + result.status());
            }
        }

        // ③ 멱등 적립 (confirm·webhook 공통 수렴점)
        SettleResult settled = settlementService.settle(orderId, paymentKey);

        return PaymentConfirmResponse.builder()
                .orderId(orderId)
                .chargedAmount(settled.chargedAmount())
                .balance(settled.balance())
                .status(PaymentStatus.DONE.name())
                .build();
    }
}
