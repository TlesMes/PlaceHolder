package com.placeholder.domain.payment.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.point.entity.PointBucket;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.global.exception.custom.PaymentAmountMismatchException;
import com.placeholder.global.exception.custom.PaymentCancelNotAllowedException;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
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
     * <b>승인 시작 — 토스 호출 전 트랜잭션</b> (ADR-022).
     *
     * <p>주문 행을 잠그고 ① 본인 ② 금액 위변조 ③ 상태를 판정한 뒤, READY면 {@code IN_PROGRESS}로
     * 전이해 <b>커밋한다</b>. 이 커밋이 다른 요청에게 "승인 요청이 이미 나갔다"를 알리는 시점이다.
     * 락은 커밋과 함께 풀리므로 토스 응답을 기다리는 동안 락·커넥션을 쥐지 않는다(ADR-018 3번 유지).
     *
     * <p>예전에는 읽기 전용 검증이었다. 아무것도 기록하지 않아 "승인 요청 중"을 DB가 표현하지 못했고,
     * 새로고침으로 온 두 번째 요청이 토스를 또 호출하거나, 응답을 못 받은 결제를 실패로 굳혔다.
     *
     * @return 호출 측이 이어서 할 일
     */
    @Transactional
    public ConfirmStart beginConfirm(String orderId, int requestAmount, Long userId) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
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
        if (order.isSettled()) {
            return ConfirmStart.ALREADY_SETTLED;
        }
        // 다른 요청이 이미 토스에 승인을 요청했다 — 다시 부르지 않고 그 결과를 기다린다
        if (order.isInProgress()) {
            return ConfirmStart.IN_PROGRESS;
        }
        if (!order.isReady()) {
            throw new PaymentConfirmFailedException(
                    "승인할 수 없는 주문 상태입니다: status=" + order.getStatus());
        }
        order.markInProgress();
        return ConfirmStart.PROCEED;
    }

    /**
     * 현재 주문 상태와 잔액 — 이번 요청이 적립하지 않았을 때(이미 처리됨·결과 대기)의 응답용.
     * 상태를 다시 읽으므로, 그 사이 웹훅이 적립을 마쳤다면 DONE으로 답한다.
     */
    @Transactional(readOnly = true)
    public SettleResult currentState(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));
        int balance = bookerAccountRepository.findByUserId(order.getUser().getId())
                .map(BookerAccount::getBalance)
                .orElse(0);
        int charged = order.isSettled() ? order.getAmount() : 0;
        return new SettleResult(charged, balance, false, order.getStatus());
    }

    /**
     * 승인 실패 확정. 결과 미확정(READY·IN_PROGRESS)에서만 FAILED로 전이(이미 DONE인 주문은 건드리지 않는다).
     * 토스가 거절했음이 <b>확인됐을 때만</b> 부른다 — 결과를 모를 때는 부르지 않는다 (ADR-022).
     */
    @Transactional
    public void markFailed(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));
        if (order.isPending()) {
            order.markFailed();
        }
    }

    /**
     * 고아 주문 만료 확정 (대사 새벽 배치 전용). 결과 미확정 상태에서만 EXPIRED로 전이한다.
     *
     * <p>{@link #markFailed}와 같은 패턴 — 비관적 락으로 잠그고 {@code isPending()} 가드를 두어,
     * 판정과 전이 사이에 confirm/웹훅이 먼저 적립을 끝냈다면 조용히 no-op이 된다(멱등).
     * 대사가 뒤늦게 정상 결제를 만료시키는 역전을 막는 지점이다.
     */
    @Transactional
    public void markExpired(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));
        if (order.isPending()) {
            order.markExpired();
        }
    }

    /**
     * 멱등 적립 — confirm·webhook·대사 공통 수렴점. 주문 행을 비관적 락으로 잠그고,
     * 이미 DONE이면 재적립 없이 현재 잔액만 반환한다. 결과 미확정(READY·IN_PROGRESS)이면
     * DONE 전이 + 충전 + CHARGE 기록.
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
        // 현금으로 산 포인트이므로 PAID 버킷 — 이후 환불 재원이 될 수 있는 유일한 잔액 (ADR-020)
        account.charge(order.getAmount(), PointBucket.PAID);

        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.CHARGE)
                .amount(order.getAmount())
                .bucketPaid(order.getAmount())
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
     * <p><b>환불액 = min(주문 잔여액, 유료 잔액)</b> — "미사용분만 환불" 정책. 이미 좌석 예약에
     * 써버린 포인트는 되돌릴 수 없으므로 남은 잔액만큼만 취소한다.
     *
     * <p>상한이 총 잔액이 아니라 <b>유료 잔액</b>인 것이 요점이다 (ADR-020). 총 잔액으로 재면
     * "현금 충전 → 좌석에 소진 → 쿠폰 상환 → 결제 취소" 순서에서 쿠폰으로 채워진 잔액이 보이므로
     * <b>쿠폰이 현금으로 환전된다</b>. 주문 잔여액 상한은 이것을 막지 못한다 — 그 주문은 실제로
     * 그 금액이었고 아직 취소된 적도 없기 때문이다.
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

        int refundAmount = Math.min(order.remainingAmount(), account.refundableBalance());
        if (refundAmount <= 0) {
            throw new PaymentCancelNotAllowedException(
                    "환불 가능한 금액이 없습니다 (이미 사용했거나 전액 취소된 결제입니다)");
        }

        account.deductRefundable(refundAmount);
        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.REFUND)
                .amount(refundAmount)
                .bucketPaid(refundAmount)
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
        // 회수했던 재원 그대로 되돌린다 — 유료에서 뺐으므로 유료로 복구해야 환불 재원이 보존된다
        account.charge(refundAmount, PointBucket.PAID);
        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.CHARGE)
                .amount(refundAmount)
                .bucketPaid(refundAmount)
                .build());
    }

    /**
     * <b>역방향 대사 — 취소 확인 스탬프</b>. 대사가 토스와 대조를 마쳤을 때만 호출한다 (ADR-019).
     *
     * <p><b>{@code expectedCanceledAmount} 가드가 이 메서드의 존재 이유다.</b> 대사는 후보를 락 없이
     * 읽고 → 트랜잭션 밖에서 토스를 호출하고 → 여기서 기록한다. 그 사이에 사용자가 <b>새 부분 취소</b>를
     * 하면 {@code canceledAmount}가 늘어나는데, 그대로 스탬프를 찍으면 아직 토스에 도달하지도 않은
     * 새 취소까지 "확인됨"으로 표시된다 — 대사가 스스로 크래시 창을 만들어 덮어버리는 셈이다.
     *
     * <p>값이 달라졌으면 아무것도 하지 않는다. 다음 주기가 갱신된 상태로 다시 대조한다.
     *
     * @return 실제로 스탬프를 찍었으면 true, 그 사이 장부가 바뀌어 건너뛰었으면 false
     */
    @Transactional
    public boolean confirmCancelIfUnchanged(String orderId, int expectedCanceledAmount) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        if (order.getCanceledAmount() != expectedCanceledAmount) {
            return false;
        }
        order.confirmCancel();
        return true;
    }

    /**
     * <b>역방향 대사 — 포기(revert)</b>. 재시도를 오래 했는데도 토스 취소가 끝내 실패할 때,
     * 회수했던 포인트를 되돌려 사용자 상태를 확정시킨다 (ADR-019).
     *
     * <p><b>호출 측이 {@code delta > 0}(= 토스가 확실히 취소하지 않은 금액)을 확인한 뒤에만 부른다.</b>
     * 실제로 환불이 나간 건에 포인트까지 복구하면 사용자가 <b>돈과 포인트를 둘 다</b> 갖는다.
     *
     * <p>되돌린 뒤 잔여 취소액이 남아 있으면 확인 스탬프를 찍어 후보에서 배출한다 — 남은 취소분은
     * 토스가 확인해 준 부분이기 때문이다. 0이 되면 {@link PaymentOrder#revertCancel}이
     * {@code canceledAt}을 null로 만들어 후보 조건에서 자연히 빠진다.
     *
     * @return 실제로 되돌렸으면 true, 그 사이 장부가 바뀌어 건너뛰었으면 false
     */
    @Transactional
    public boolean revertCancelIfUnchanged(String orderId, int refundAmount, int expectedCanceledAmount) {
        PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException("주문을 찾을 수 없습니다"));

        if (order.getCanceledAmount() != expectedCanceledAmount) {
            return false;
        }

        Long userId = order.getUser().getId();
        BookerAccount account = bookerAccountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));

        order.revertCancel(refundAmount);
        // 회수했던 재원 그대로 되돌린다 — 유료에서 뺐으므로 유료로 복구해야 환불 재원이 보존된다 (ADR-020)
        account.charge(refundAmount, PointBucket.PAID);
        pointTransactionRepository.save(PointTransaction.builder()
                .user(order.getUser())
                .type(TransactionType.CHARGE)
                .amount(refundAmount)
                .bucketPaid(refundAmount)
                .build());

        if (order.getCanceledAmount() > 0) {
            order.confirmCancel();
        }
        return true;
    }

    /**
     * @param newlyCredited 이번 호출에서 실제로 적립했으면 true, 멱등 no-op이면 false
     * @param status        주문의 <b>실제</b> 현재 상태. 멱등 재요청 응답에 DONE을 박아 넣으면
     *                      이미 취소된 주문에도 "DONE"이라고 답하게 된다(ADR-019)
     */
    public record SettleResult(int chargedAmount, int balance, boolean newlyCredited,
                               PaymentOrder.PaymentStatus status) {
    }

    /** {@link #beginConfirm}의 판정 — 호출 측이 토스 승인을 부를지 말지. */
    public enum ConfirmStart {
        /** READY → IN_PROGRESS 전이를 커밋했다. 이 요청이 토스를 호출한다. */
        PROCEED,
        /** 이미 적립(또는 취소)까지 끝난 주문. 토스를 부르지 않는다. */
        ALREADY_SETTLED,
        /** 다른 요청이 승인을 요청해 결과 대기 중. 토스를 부르지 않는다. */
        IN_PROGRESS
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
