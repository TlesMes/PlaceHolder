package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.PaymentConfirmResponse;
import com.placeholder.domain.payment.service.PaymentSettlementService.ConfirmStart;
import com.placeholder.domain.payment.service.PaymentSettlementService.SettleResult;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import com.placeholder.global.exception.custom.PaymentResultUnknownException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 동기 승인 — 결제의 주 경로 (ADR-018).
 *
 * <p><b>트랜잭션 경계 설계가 핵심.</b> 이 메서드 자체는 {@code @Transactional}이 아니다 — 외부 토스
 * 호출을 트랜잭션 안에 넣으면 네트워크 지연만큼 DB 커넥션을 점유하고 락 보유 중 데드락 위험이 생기기
 * 때문. 대신 짧은 트랜잭션을 {@link PaymentSettlementService}의 개별 트랜잭션 메서드로 나눠 호출한다.
 *
 * <p>순서 (ADR-022):
 * <ol>
 *   <li><b>[tx] 승인 시작</b> — 잠그고 검증한 뒤 READY → IN_PROGRESS를 커밋. 동시에 온 요청 중
 *       하나만 통과한다.</li>
 *   <li><b>트랜잭션 밖</b> — 토스 승인 호출.</li>
 *   <li><b>[tx] 결과 기록</b> — 승인이면 멱등 적립, 토스가 거절했으면 FAILED,
 *       <b>결과를 모르면 아무것도 쓰지 않는다</b>(IN_PROGRESS 유지 → 웹훅·대사가 토스에 물어 확정).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmService {

    private final PaymentSettlementService settlementService;
    private final TossPaymentClient tossClient;

    public PaymentConfirmResponse confirm(String orderId, String paymentKey, int amount, Long userId) {
        // ① 승인 시작 — 이미 처리됐거나 다른 요청이 처리 중이면 토스를 부르지 않는다
        ConfirmStart start = settlementService.beginConfirm(orderId, amount, userId);
        if (start != ConfirmStart.PROCEED) {
            return toResponse(orderId, settlementService.currentState(orderId));
        }

        // ② 외부 토스 승인 — 어떤 트랜잭션에도 들어가지 않는다
        TossPaymentResult result;
        try {
            result = tossClient.confirm(paymentKey, orderId, amount);
        } catch (PaymentResultUnknownException e) {
            // 토스에선 승인됐을 수 있다. 실패로 단정하지 않고 IN_PROGRESS로 남긴다 — 웹훅·대사가 확정한다.
            log.warn("승인 결과 모름 — IN_PROGRESS 유지, 웹훅·대사에 위임 (orderId={}): {}",
                    orderId, e.getMessage());
            return toResponse(orderId, settlementService.currentState(orderId));
        } catch (PaymentConfirmFailedException e) {
            settlementService.markFailed(orderId);
            throw e;
        }
        if (!result.isDone()) {
            settlementService.markFailed(orderId);
            throw new PaymentConfirmFailedException(
                    "토스 결제가 승인 상태가 아닙니다: status=" + result.status());
        }

        // ③ 멱등 적립 (confirm·webhook·대사 공통 수렴점)
        return toResponse(orderId, settlementService.settle(orderId, paymentKey));
    }

    private PaymentConfirmResponse toResponse(String orderId, SettleResult settled) {
        return PaymentConfirmResponse.builder()
                .orderId(orderId)
                .chargedAmount(settled.chargedAmount())
                .balance(settled.balance())
                .status(settled.status().name())
                .build();
    }
}
