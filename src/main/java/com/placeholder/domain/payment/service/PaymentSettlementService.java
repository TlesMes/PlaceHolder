package com.placeholder.domain.payment.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.global.exception.custom.PaymentAmountMismatchException;
import com.placeholder.global.exception.custom.PaymentOrderNotFoundException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * confirm(동기)과 webhook(보조)이 공유하는 <b>멱등 적립 코어</b> (ADR-018).
 *
 * <p>두 경로가 같은 orderId로 동시/순차 도착해도 여기서 수렴한다. 비관적 락으로 주문 행을 잠그고
 * "이미 DONE이면 no-op"으로 처리하므로 포인트는 정확히 1회 적립된다(쿠폰 상환 ADR-010의 락+상태체크 기조).
 *
 * <p>모든 트랜잭션 메서드를 이 한 빈에 모은 이유: {@code @Transactional} self-invocation은 프록시를
 * 경유하지 않아 무효다. 외부 I/O(토스 호출)를 하는 상위 서비스는 트랜잭션 없이 이 빈의 메서드를 호출한다.
 */
@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
public class PaymentSettlementService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final BookerAccountRepository bookerAccountRepository;
    private final PointTransactionRepository pointTransactionRepository;

    /**
     * confirm 전 사전 검증 (외부 토스 호출 전에 수행). 락 없이 읽어 빠르게 fail-fast.
     * @return 이미 확정된 주문이면 true (호출 측이 토스 승인 호출을 건너뛰고 바로 멱등 settle로 간다)
     */
    @Transactional(readOnly = true)
    public boolean validateBeforeConfirm(String orderId, int requestAmount, Long userId) {
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        // 본인 주문만 확정 가능 (타인 주문은 존재를 숨기고 not found 처리)
        if (!order.getUser().getId().equals(userId)) {
            throw new PaymentOrderNotFoundException("주문을 찾을 수 없습니다");
        }
        // 금액 위변조 검증 — 서버가 주문 시 저장한 금액과 대조
        if (order.getAmount() != requestAmount) {
            throw new PaymentAmountMismatchException("결제 금액이 주문 금액과 일치하지 않습니다");
        }
        return order.isDone();
    }

    /**
     * 승인 실패 확정. READY 상태에서만 FAILED로 전이(이미 DONE인 주문은 건드리지 않는다).
     */
    @Transactional
    public void markFailed(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));
        if (order.isReady()) {
            order.markFailed();
        }
    }

    /**
     * 고아 주문 만료 확정 (대사 새벽 배치 전용). READY 상태에서만 EXPIRED로 전이한다.
     *
     * <p>{@link #markFailed}와 같은 패턴 — 비관적 락으로 잠그고 {@code isReady()} 가드를 두어,
     * 판정과 전이 사이에 confirm/웹훅이 먼저 적립을 끝냈다면 조용히 no-op이 된다(멱등).
     * 대사가 뒤늦게 정상 결제를 만료시키는 역전을 막는 지점이다.
     */
    @Transactional
    public void markExpired(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));
        if (order.isReady()) {
            order.markExpired();
        }
    }

    /**
     * 멱등 적립 — confirm·webhook 공통 수렴점. 주문 행을 비관적 락으로 잠그고,
     * 이미 DONE이면 재적립 없이 현재 잔액만 반환한다. READY면 DONE 전이 + 충전 + CHARGE 기록.
     */
    @Transactional
    public SettleResult settle(String orderId, String paymentKey) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        Long userId = order.getUser().getId();

        // 멱등: 이미 확정·적립된 주문이면 재적립 없이 현재 잔액 반환
        if (order.isDone()) {
            int balance = bookerAccountRepository.findByUserId(userId)
                    .map(BookerAccount::getBalance)
                    .orElse(0);
            return new SettleResult(order.getAmount(), balance, false);
        }

        order.markDone(paymentKey);

        // 동일 유저 동시 작업 직렬화를 위해 계정도 비관적 락
        BookerAccount account = bookerAccountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));
        account.charge(order.getAmount());

        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.CHARGE)
                .amount(order.getAmount())
                .build());

        return new SettleResult(order.getAmount(), account.getBalance(), true);
    }

    /**
     * @param newlyCredited 이번 호출에서 실제로 적립했으면 true, 멱등 no-op이면 false
     */
    public record SettleResult(int chargedAmount, int balance, boolean newlyCredited) {
    }
}
