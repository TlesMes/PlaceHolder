package com.placeholder.domain.payment.repository;

import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    /** 락 없이 orderId로 조회 (검증·읽기용). */
    Optional<PaymentOrder> findByOrderId(String orderId);

    /**
     * 주문 행에 비관적 쓰기 락(SELECT ... FOR UPDATE)을 걸고 orderId로 조회한다.
     * confirm(동기)과 webhook(보조)이 같은 orderId로 동시에 적립을 시도해도, 락 보유자만
     * READY→DONE 전이·충전을 수행하므로 이중 적립이 발생하지 않는다 (ADR-018, 쿠폰 상환 ADR-010과 동일 기조).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOrder p where p.orderId = :orderId")
    Optional<PaymentOrder> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * 대사 후보 조회 — 지정 구간에 생성된 특정 상태의 주문을 오래된 순으로 가져온다.
     *
     * <p>락 없이 읽는다. 실제 상태 전이는 건별로 {@code findByOrderIdForUpdate}가 다시 잠그므로
     * 여기서 잠글 필요가 없고, 잠그면 외부 토스 호출 동안 락을 쥐게 되어 ADR-018 트랜잭션 경계
     * 원칙을 깬다. 오래된 순 정렬 + Pageable로 한 번에 처리할 건수를 제한한다.
     */
    List<PaymentOrder> findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
            PaymentStatus status, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 내 결제·환불 내역 — 최신순. 사용자당 주문 건수가 적어 cursor 페이징 없이 상한만 둔다
     * (ADR-012의 "사용자당 거래 소량" 전제와 같은 판단).
     */
    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * <b>역방향 대사 후보</b> — 우리는 취소로 기록했는데 토스 확인이 안 된 주문 (ADR-019).
     *
     * <p>취소 보상 트랜잭션의 ①(포인트 회수 커밋)과 ②(토스 호출) 사이에서 서버가 죽으면
     * "포인트만 회수되고 돈은 안 돌아간" 상태가 남는다. 그 주문들이 여기 잡힌다.
     *
     * <p><b>{@code cancelConfirmedAt IS NULL}로는 부족하다.</b> {@code markCanceled}가
     * {@code canceledAt}을, {@code confirmCancel}이 {@code cancelConfirmedAt}을 매번 덮어쓰므로,
     * 부분 취소 <b>2회차</b>가 크래시하면 {@code cancelConfirmedAt}은 1회차 값으로 non-null이다.
     * 정확히 막으려던 상태가 후보에서 빠지는 것이다 — 그래서 두 시각을 <b>비교</b>한다.
     *
     * <p><b>{@code <} 가 아니라 {@code <=} 인 이유:</b> 오탐(이미 확인된 취소를 다시 대조)은 멱등 키
     * 덕분에 무해하고 차액 0으로 즉시 배출되지만, 누락은 <b>영구적 금전 손실</b>이다. 위험이
     * 비대칭이므로 안전한 쪽으로 기운다.
     *
     * <p>락은 걸지 않는다 — 외부 토스 호출 동안 락을 쥐면 ADR-018 트랜잭션 경계 원칙을 깬다.
     * 실제 상태 전이는 건별로 {@link #findByOrderIdForUpdate}가 다시 잠근다.
     */
    @Query("select p from PaymentOrder p "
            + "where p.canceledAt is not null "
            + "and p.canceledAt between :from and :to "
            + "and (p.cancelConfirmedAt is null or p.cancelConfirmedAt <= p.canceledAt) "
            + "order by p.canceledAt asc")
    List<PaymentOrder> findUnconfirmedCancels(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              Pageable pageable);
}
