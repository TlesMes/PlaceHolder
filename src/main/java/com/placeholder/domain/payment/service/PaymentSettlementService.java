package com.placeholder.domain.payment.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.global.exception.custom.PaymentAmountMismatchException;
import com.placeholder.global.exception.custom.PaymentCancelNotAllowedException;
import com.placeholder.global.exception.custom.PaymentOrderNotFoundException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        // 취소된 주문도 "이미 승인이 끝난" 주문이라 토스 승인을 다시 호출해선 안 된다 (ADR-019).
        return order.isSettled();
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

        // 멱등: 이미 확정·적립된 주문이면 재적립 없이 현재 잔액 반환.
        // 취소된 주문도 "한 번 적립됐던" 주문이므로 여기에 포함된다 — 취소 후 뒤늦게 도착한 웹훅이
        // 환불한 포인트를 되돌려주지 않도록 isDone()이 아니라 isSettled()로 판정한다 (ADR-019).
        if (order.isSettled()) {
            int balance = bookerAccountRepository.findByUserId(userId)
                    .map(BookerAccount::getBalance)
                    .orElse(0);
            return new SettleResult(order.getAmount(), balance, false, order.getStatus());
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

        return new SettleResult(order.getAmount(), account.getBalance(), true, order.getStatus());
    }

    /**
     * <b>취소 1단계 — 포인트 선회수</b> (ADR-019 보상 순서의 첫 칸).
     *
     * <p>주문·계정을 비관적 락으로 잡고 ① 취소 가능 여부(상태·기한·본인) 검증 → ② 환불액 산정
     * → ③ 잔액 차감 + {@code REFUND} 기록 → ④ {@code markCanceled} 까지를 <b>한 트랜잭션</b>에서 끝낸다.
     *
     * <p><b>왜 상태 전이까지 여기서 하는가:</b> 토스 취소 성공을 기다렸다가 상태를 바꾸면, 그 사이
     * 도착한 두 번째 취소 요청이 잔여액을 다시 계산해 토스에 중복 취소를 날린다. 상태 전이가 곧
     * 동시 취소의 방어선이다. 외부 호출이 실패하면 {@link #revertCancel}이 이 트랜잭션을 되돌린다.
     *
     * <p><b>환불액 = min(주문 잔여액, 현재 포인트 잔액)</b> — "미사용분만 환불" 정책. 이미 좌석 예약에
     * 써버린 포인트는 되돌릴 수 없으므로 남은 잔액만큼만 취소한다.
     */
    @Transactional
    public CancelPreparation prepareCancel(String orderId, Long userId, int cancelPeriodDays) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        // 본인 주문만 취소 가능 (타인 주문은 존재를 숨기고 not found 처리 — confirm과 동일 원칙)
        if (!order.getUser().getId().equals(userId)) {
            throw new PaymentOrderNotFoundException("주문을 찾을 수 없습니다");
        }
        if (!order.isCancelable()) {
            throw new PaymentCancelNotAllowedException(
                    "취소할 수 없는 주문 상태입니다: status=" + order.getStatus());
        }
        if (!order.isWithinCancelPeriod(LocalDateTime.now(), cancelPeriodDays)) {
            throw new PaymentCancelNotAllowedException(
                    "취소 가능 기간(" + cancelPeriodDays + "일)이 지났습니다");
        }

        BookerAccount account = bookerAccountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));

        int refundAmount = Math.min(order.remainingAmount(), account.getBalance());
        if (refundAmount <= 0) {
            throw new PaymentCancelNotAllowedException(
                    "환불 가능한 금액이 없습니다 (이미 사용했거나 전액 취소된 결제입니다)");
        }

        account.deduct(refundAmount);
        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.REFUND)
                .amount(refundAmount)
                .build());
        order.markCanceled(refundAmount);

        return new CancelPreparation(order.getPaymentKey(), refundAmount,
                order.getCanceledAmount(), account.getBalance());
    }

    /**
     * <b>취소 3단계(성공)</b> — 토스 취소가 확인됐음을 기록한다. 여기까지 와야 "돈이 돌아갔다".
     */
    @Transactional
    public void confirmCancel(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));
        order.confirmCancel();
    }

    /**
     * <b>취소 3단계(실패) — 보상 트랜잭션.</b> 토스 취소가 실패했으므로 1단계를 통째로 되돌린다:
     * 취소 기록 롤백 + 포인트 복구 + 복구분을 {@code CHARGE}로 기록.
     *
     * <p>이력을 지우지 않고 반대 거래를 추가하는 이유는 회계 원장과 같다 — 실제로 잔액이 두 번
     * 움직였으므로(회수→복구) 그 사실이 사용자 이력에 남아야 한다.
     */
    @Transactional
    public void revertCancel(String orderId, int refundAmount) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        Long userId = order.getUser().getId();
        BookerAccount account = bookerAccountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));

        order.revertCancel(refundAmount);
        account.charge(refundAmount);
        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.CHARGE)
                .amount(refundAmount)
                .build());
    }

    /**
     * @param newlyCredited 이번 호출에서 실제로 적립했으면 true, 멱등 no-op이면 false
     * @param status        주문의 <b>실제</b> 현재 상태. 멱등 재요청 응답에 DONE을 박아 넣으면
     *                      이미 취소된 주문에도 "DONE"이라고 답하게 된다(ADR-019)
     */
    public record SettleResult(int chargedAmount, int balance, boolean newlyCredited,
                               PaymentOrder.PaymentStatus status) {
    }

    /**
     * 취소 1단계 결과 — 트랜잭션 밖 토스 취소 호출에 필요한 값들.
     *
     * @param paymentKey           토스 취소 대상 키
     * @param refundAmount         이번에 취소할 금액(= 회수한 포인트)
     * @param totalCanceledAmount  이번 취소를 포함한 누적 취소액 — 토스 멱등 키의 재료
     * @param balance              회수 후 잔액
     */
    public record CancelPreparation(String paymentKey, int refundAmount,
                                    int totalCanceledAmount, int balance) {
    }
}
