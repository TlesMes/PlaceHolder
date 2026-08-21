package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.PaymentCancelResponse;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentSettlementService.CancelPreparation;
import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.placeholder.global.exception.custom.PaymentOrderNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 결제 취소·환불 — <b>보상 트랜잭션</b> (ADR-019).
 *
 * <p>결제(적립)보다 취소가 어렵다. 결제는 "외부 성공 + 내부 실패"를 재시도로 복구할 수 있지만
 * (멱등 적립), 취소는 <b>돈을 돌려준 뒤 포인트 회수에 실패하면 그대로 순손실</b>이다. 그래서 순서를
 * 뒤집는다 — 되돌릴 수 있는 쪽(우리 DB)을 먼저 움직이고, 되돌릴 수 없는 쪽(토스)을 나중에 친다.
 *
 * <pre>
 *   ① [트랜잭션] 검증 → 포인트 회수 → REFUND 기록 → 주문 취소 기록
 *   ②            토스 취소 호출              ← 트랜잭션 밖 (ADR-018 3번)
 *   ③ [트랜잭션] 성공: 취소 확정 기록 / 실패: ①을 통째로 되돌림(포인트 복구)
 * </pre>
 *
 * <p><b>이 클래스에 {@code @Transactional}이 없는 것이 설계다.</b> 외부 호출을 트랜잭션 안에 넣으면
 * 네트워크 지연만큼 커넥션과 행 락을 붙잡는다. 트랜잭션 메서드는 전부 {@link PaymentSettlementService}
 * (별도 빈)에 있다 — self-invocation은 프록시를 안 거쳐 트랜잭션이 무효가 되기 때문.
 *
 * <p><b>남는 창(알려진 한계):</b> ① 커밋 직후 ②를 부르기 전에 서버가 죽으면 "우리는 취소, 토스는
 * 승인" 상태로 남는다(포인트만 회수, 환불 없음). 프로세스 내 보상으로는 닫을 수 없는 창이라
 * {@code cancelConfirmedAt IS NULL}인 주문을 배치가 재시도하는 역방향 대사가 필요하다 — 후속 PR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelService {

    private final PaymentSettlementService settlementService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final TossPaymentClient tossClient;

    /** 취소 가능 기간(일). 전자상거래법 청약철회 기간 7일 (ADR-019). */
    @Value("${payment.cancel.period-days:7}")
    private int cancelPeriodDays;

    public PaymentCancelResponse cancel(String orderId, Long userId, String reason) {
        // ① 포인트 선회수 + 취소 기록 (짧은 단일 트랜잭션, 주문·계정 비관적 락)
        CancelPreparation prepared = settlementService.prepareCancel(orderId, userId, cancelPeriodDays);

        // ② 외부 토스 취소 — 어떤 트랜잭션에도 들어가지 않는다
        try {
            TossPaymentResult result = tossClient.cancel(prepared.paymentKey(), reason,
                    prepared.refundAmount(),
                    CancelIdempotencyKey.of(orderId, prepared.totalCanceledAmount()));

            // 200을 받았다는 사실만으로 돈이 돌아갔다고 볼 수 없다. 상태를 직접 확인하지 않으면
            // 취소되지 않은 결제를 취소로 장부에 남기게 된다 — 아래 catch가 보상해준다.
            if (!result.isCanceled()) {
                throw new PaymentCancelFailedException(
                        "토스 취소가 반영되지 않았습니다: status=" + result.status());
            }
        } catch (RuntimeException e) {
            // ③-실패: 보상. 돈이 안 돌아갔으므로 회수한 포인트를 되돌려 원상복구한다.
            settlementService.revertCancel(orderId, prepared.refundAmount());
            log.error("토스 취소 실패 — 포인트 복구 완료 (orderId={}, refundAmount={}): {}",
                    orderId, prepared.refundAmount(), e.getMessage());
            throw new PaymentCancelFailedException(
                    "결제 취소에 실패했습니다. 포인트는 원래대로 복구되었습니다.");
        }

        // ③-성공: 여기까지 와야 "돈이 돌아갔다"고 기록할 수 있다
        settlementService.confirmCancel(orderId);

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        log.info("결제 취소 완료 (orderId={}, 이번 취소액={}, 누적 취소액={}, status={})",
                orderId, prepared.refundAmount(), order.getCanceledAmount(), order.getStatus());

        return PaymentCancelResponse.builder()
                .orderId(orderId)
                .canceledAmount(prepared.refundAmount())
                .totalCanceledAmount(order.getCanceledAmount())
                .balance(prepared.balance())
                .status(order.getStatus().name())
                .build();
    }

}
