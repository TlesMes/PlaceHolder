package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * <b>역방향 대사</b> — 우리는 취소했는데 토스는 모르는 결제를 찾아 바로잡는다 (ADR-019).
 *
 * <p>취소 보상 트랜잭션은 되돌릴 수 있는 쪽(우리 DB)을 먼저, 되돌릴 수 없는 쪽(토스)을 나중에 친다:
 *
 * <pre>
 *   ① [tx] 포인트 회수 + 취소 기록(canceledAt)
 *   ②      토스 취소 호출                       ← 여기서 서버가 죽으면
 *   ③ [tx] 확인 기록(cancelConfirmedAt)
 * </pre>
 *
 * <p>①만 커밋된 채 죽으면 <b>포인트는 회수됐는데 현금은 안 돌아간</b> 상태가 영구히 남는다.
 * 프로세스 안의 보상으로는 닫을 수 없는 창이라 배치가 밖에서 메운다. 정방향 대사
 * ({@link PaymentReconciliationService})가 "돈은 받았는데 포인트를 안 준" 경우를 메우는 것과 대칭이다.
 *
 * <p><b>재시도할 금액을 로컬에서 복원할 수 없다.</b> 우리가 저장한 건 누적 취소액뿐이고 이번 시도분은
 * 어디에도 없다. 그래서 대사답게 <b>토스에 실제 상태를 묻고 차액</b>을 취소한다 — 덤으로 "②는
 * 성공했는데 ③만 미커밋"인 경우를 구분해 불필요한 재호출을 피한다.
 *
 * <p><b>트랜잭션 경계(ADR-018 3번 유지):</b> 후보 조회와 토스 호출은 어떤 트랜잭션에도 넣지 않고,
 * 상태 전이만 {@link PaymentSettlementService}의 짧은 트랜잭션에 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelReconciliationService {

    /** 재시도로 나가는 취소의 사유 — 사용자가 처음 적은 사유는 저장하지 않으므로 대사임을 명시한다. */
    private static final String RETRY_REASON = "환불 재시도 (역방향 대사)";

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentSettlementService settlementService;
    private final TossPaymentClient tossClient;

    @Value("${payment.cancel-reconciliation.batch-size:100}")
    private int batchSize;

    /**
     * 지정 구간에 취소 기록된 주문 중 토스 확인이 안 된 건을 대조·보정한다.
     *
     * @param from        스캔 시작 ({@code canceledAt} 기준)
     * @param to          스캔 끝 — 진행 중인 취소를 제외하는 유예선
     * @param allowRevert 끝내 실패한 취소의 포인트를 되돌릴 권한. <b>새벽 잡만 true</b>
     */
    public CancelReconcileResult reconcile(LocalDateTime from, LocalDateTime to, boolean allowRevert) {
        List<PaymentOrder> candidates =
                paymentOrderRepository.findUnconfirmedCancels(from, to, PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return CancelReconcileResult.empty();
        }

        int confirmed = 0;
        int recovered = 0;
        int reverted = 0;
        int skipped = 0;

        for (PaymentOrder order : candidates) {
            // 락 없이 읽은 스냅샷. 아래 상태 전이는 이 값이 그대로인지 확인하고서야 적용된다
            // (그 사이 사용자가 새 부분 취소를 했다면 건드리면 안 된다).
            String orderId = order.getOrderId();
            int expected = order.getCanceledAmount();
            String paymentKey = order.getPaymentKey();

            if (paymentKey == null) {
                // 취소된 주문은 승인을 거쳤으므로 paymentKey가 있어야 한다. 없다면 우리 데이터가 깨진 것.
                log.error("역방향 대사 — paymentKey 없는 취소 주문 (orderId={})", orderId);
                skipped++;
                continue;
            }

            // 외부 호출 — 트랜잭션 밖
            Optional<TossPaymentResult> found;
            try {
                found = tossClient.findByOrderId(orderId);
            } catch (RuntimeException e) {
                // 한 건의 조회 실패가 배치를 멈추면 안 된다. 다음 주기에 다시 시도된다.
                log.warn("역방향 대사 조회 실패 — 다음 주기 재시도 (orderId={}): {}", orderId, e.getMessage());
                skipped++;
                continue;
            }

            if (found.isEmpty()) {
                // 승인까지 끝난 주문을 토스가 모른다 = 일어나서는 안 되는 상태. 자동 판단 대상이 아니다.
                log.error("역방향 대사 — 토스에 기록이 없는 취소 주문 (orderId={})", orderId);
                skipped++;
                continue;
            }

            int delta = expected - found.get().canceledAmount();

            if (delta < 0) {
                // 토스가 우리 장부보다 더 취소했다(외부 개입 등). 맞추려면 포인트를 더 회수해야 하는데,
                // 그건 코드가 자동으로 내릴 결정이 아니다 — 운영자에게 넘긴다.
                log.error("역방향 대사 — 토스 취소액이 장부보다 큼, 수동 확인 필요 "
                        + "(orderId={}, 장부={}, 토스={})", orderId, expected, found.get().canceledAmount());
                skipped++;
                continue;
            }

            if (delta == 0) {
                // ②까지는 성공했고 ③만 커밋되지 못한 경우. 토스를 다시 부를 이유가 없다.
                if (settlementService.confirmCancelIfUnchanged(orderId, expected)) {
                    confirmed++;
                    log.info("역방향 대사 — 취소 확인 기록 보정 (orderId={}, 누적취소액={})", orderId, expected);
                } else {
                    skipped++;
                }
                continue;
            }

            // delta > 0 — 이 금액만큼 취소 요청이 토스에 도달하지 못했다. 본 크래시 창.
            if (retryCancel(orderId, paymentKey, delta, expected)) {
                if (settlementService.confirmCancelIfUnchanged(orderId, expected)) {
                    recovered++;
                    log.info("역방향 대사 — 누락된 취소 재전송 성공 (orderId={}, 취소액={})", orderId, delta);
                } else {
                    skipped++;
                }
            } else if (allowRevert) {
                // 오래 재시도했는데도 실패 — 현금 환불을 포기하고 포인트를 되돌려 상태를 확정한다.
                // delta는 "토스가 확실히 취소하지 않은 금액"이므로 이만큼만 복구해야 한다.
                if (settlementService.revertCancelIfUnchanged(orderId, delta, expected)) {
                    reverted++;
                    log.error("역방향 대사 — 취소 재시도 포기, 포인트 복구 (orderId={}, 복구액={})", orderId, delta);
                } else {
                    skipped++;
                }
            } else {
                skipped++;
            }
        }

        CancelReconcileResult result =
                new CancelReconcileResult(candidates.size(), confirmed, recovered, reverted, skipped);
        if (confirmed > 0 || recovered > 0 || reverted > 0) {
            log.info("역방향 대사 완료 — {}", result);
        }
        return result;
    }

    /**
     * 누락된 취소를 다시 보낸다. 멱등 키가 <b>누적 취소액</b> 기준이라, 크래시한 시도가 이미 토스에
     * 도달했었다면 같은 키가 되어 토스가 첫 결과를 재생한다(중복 환불 없음).
     *
     * @return 토스가 취소를 반영했으면 true
     */
    private boolean retryCancel(String orderId, String paymentKey, int delta, int totalCanceledAmount) {
        try {
            TossPaymentResult result = tossClient.cancel(paymentKey, RETRY_REASON, delta,
                    CancelIdempotencyKey.of(orderId, totalCanceledAmount));
            // 200을 받았다는 사실만으로 돈이 돌아갔다고 볼 수 없다 — 상태를 직접 확인한다(ADR-019).
            if (!result.isCanceled()) {
                log.warn("역방향 대사 — 취소가 반영되지 않음 (orderId={}, status={})", orderId, result.status());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("역방향 대사 — 취소 재전송 실패 (orderId={}, 취소액={}): {}", orderId, delta, e.getMessage());
            return false;
        }
    }

    /**
     * @param scanned   조회한 후보 수
     * @param confirmed ②는 성공했었고 확인 기록만 보정한 건수
     * @param recovered 누락된 취소를 실제로 재전송해 성공한 건수 (이 기능의 존재 이유)
     * @param reverted  끝내 실패해 포인트를 되돌린 건수 — 0이 아니면 살펴봐야 한다
     * @param skipped   조회 실패·장부 변경·수동 확인 필요 등으로 이번엔 건드리지 않은 건수
     */
    public record CancelReconcileResult(int scanned, int confirmed, int recovered,
                                        int reverted, int skipped) {
        static CancelReconcileResult empty() {
            return new CancelReconcileResult(0, 0, 0, 0, 0);
        }
    }
}
