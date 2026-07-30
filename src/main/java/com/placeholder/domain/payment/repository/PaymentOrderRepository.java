package com.placeholder.domain.payment.repository;

import com.placeholder.domain.payment.entity.PaymentOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
