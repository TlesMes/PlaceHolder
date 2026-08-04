package com.placeholder.domain.payment.service;

import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
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
 * 결제 대사 — 동기 승인(confirm)과 웹훅이 <b>둘 다</b> 실패한 결제를 찾아 바로잡는 세 번째 층 (ADR-018).
 *
 * <p>이중화만으로는 구멍이 남는다: 토스는 승인을 마쳤는데 confirm 응답 전 서버가 죽고 웹훅마저
 * 유실되면, 주문은 READY로 남고 사용자는 돈만 낸 상태가 된다 — 그리고 시스템은 그 사실을 영원히
 * 모른다. 대사는 주기적으로 토스에 되물어 이 불일치를 스스로 발견해 보정한다.
 *
 * <p><b>orderId 조회가 축이다.</b> 누락 주문은 paymentKey가 null이라 기존 조회로는 접근할 수 없고,
 * 우리가 발급해 항상 보유한 orderId로 물어야 한다({@link TossPaymentClient#findByOrderId}).
 *
 * <p><b>트랜잭션 경계(ADR-018 3번 유지):</b> 후보 조회는 짧은 읽기, 토스 호출은 어떤 트랜잭션에도
 * 넣지 않으며, 상태 전이만 {@link PaymentSettlementService}의 짧은 트랜잭션에 위임한다.
 *
 * <p><b>만료 권한 분리:</b> {@code allowExpire=false}인 보정 잡은 주문을 종결시키지 못한다.
 * 토스는 결제창이 열리기 전(주문 생성 직후 수 초)에는 그 orderId를 몰라 404를 주는데, 이때
 * 성급히 만료시키면 곧이어 결제를 마친 사용자의 confirm이 거부되어 <b>막으려던 사고를 대사가
 * 일으킨다.</b> 그래서 404→만료는 충분히 오래된 주문만 다루는 새벽 배치에만 허용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentSettlementService settlementService;
    private final TossPaymentClient tossClient;

    @Value("${payment.reconciliation.batch-size:100}")
    private int batchSize;

    /**
     * 지정 구간에 생성된 READY 주문을 토스와 대조해 보정한다.
     *
     * @param from        스캔 시작 (이 시각 이후 생성분)
     * @param to          스캔 끝 (이 시각 이전 생성분) — 진행 중인 최신 주문을 제외하는 유예선
     * @param allowExpire 토스에 기록이 없는(404) 주문을 EXPIRED로 종결할 권한. 새벽 배치만 true
     */
    public ReconcileResult reconcile(LocalDateTime from, LocalDateTime to, boolean allowExpire) {
        List<PaymentOrder> candidates = findCandidates(from, to);
        if (candidates.isEmpty()) {
            return ReconcileResult.empty();
        }

        int credited = 0;
        int failed = 0;
        int expired = 0;
        int skipped = 0;

        for (PaymentOrder order : candidates) {
            // 외부 호출 — 트랜잭션 밖
            Optional<TossPaymentResult> found;
            try {
                found = tossClient.findByOrderId(order.getOrderId());
            } catch (RuntimeException e) {
                // 한 건의 조회 실패가 배치 전체를 멈추면 안 된다. 다음 주기에 다시 시도된다.
                log.warn("대사 조회 실패 — 다음 주기 재시도 (orderId={}): {}",
                        order.getOrderId(), e.getMessage());
                skipped++;
                continue;
            }

            if (found.isEmpty()) {
                // 토스에 기록 없음 = 결제창을 띄우지 않고 이탈. 종결은 새벽 배치에만 허용한다.
                if (allowExpire) {
                    settlementService.markExpired(order.getOrderId());
                    expired++;
                } else {
                    skipped++;
                }
                continue;
            }

            TossPaymentResult actual = found.get();
            if (actual.isDone()) {
                // 금액 위변조 방어 — 주문 시 서버가 확정한 금액과 다르면 적립하지 않는다(confirm과 동일 원칙).
                if (actual.totalAmount() != order.getAmount()) {
                    log.error("대사 금액 불일치 — 보정 생략 (orderId={}, 주문액={}, 토스액={})",
                            order.getOrderId(), order.getAmount(), actual.totalAmount());
                    skipped++;
                    continue;
                }
                // 누락된 결제 발견 → 멱등 적립(이미 처리됐다면 no-op)
                settlementService.settle(order.getOrderId(), actual.paymentKey());
                credited++;
                log.info("대사 보정 — 누락 결제 적립 (orderId={}, amount={})",
                        order.getOrderId(), order.getAmount());
            } else if (isTerminalFailure(actual.status())) {
                settlementService.markFailed(order.getOrderId());
                failed++;
            } else {
                // IN_PROGRESS / WAITING_FOR_DEPOSIT 등 — 아직 진행 중이므로 건드리지 않는다.
                skipped++;
            }
        }

        ReconcileResult result = new ReconcileResult(candidates.size(), credited, failed, expired, skipped);
        if (credited > 0 || failed > 0 || expired > 0) {
            log.info("대사 완료 — {}", result);
        }
        return result;
    }

    /**
     * 후보 조회. 여기에 {@code @Transactional}을 붙이지 않는다 — 같은 빈 안에서 호출하면
     * self-invocation이라 프록시를 안 거쳐 무효이고(ADR-018), 애초에 Spring Data 리포지토리 메서드가
     * 자체 읽기 트랜잭션으로 실행되므로 불필요하다. 이 메서드를 감싸는 트랜잭션이 없어야
     * 뒤이은 토스 호출이 트랜잭션 밖에 놓인다.
     */
    private List<PaymentOrder> findCandidates(LocalDateTime from, LocalDateTime to) {
        return paymentOrderRepository.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                PaymentStatus.READY, from, to, PageRequest.of(0, batchSize));
    }

    /** 토스 측에서 이미 종결된 실패 상태. 되돌아올 일이 없으므로 FAILED로 확정한다. */
    private boolean isTerminalFailure(String status) {
        return "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status)
                || "ABORTED".equals(status) || "EXPIRED".equals(status);
    }

    /**
     * @param scanned  조회한 후보 수
     * @param credited 누락 결제를 발견해 적립한 건수 (이 기능의 존재 이유)
     * @param failed   토스에서 실패·취소로 종결돼 FAILED 처리한 건수
     * @param expired  토스에 기록이 없어 만료 처리한 고아 주문 수
     * @param skipped  진행 중·금액 불일치·조회 실패 등으로 이번엔 건드리지 않은 건수
     */
    public record ReconcileResult(int scanned, int credited, int failed, int expired, int skipped) {
        static ReconcileResult empty() {
            return new ReconcileResult(0, 0, 0, 0, 0);
        }
    }
}
