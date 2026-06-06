package com.placeholder.domain.coupon.repository;

import com.placeholder.domain.coupon.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 쿠폰 행에 비관적 쓰기 락(SELECT ... FOR UPDATE)을 걸고 코드로 조회한다.
     * 같은 코드에 동시 상환 요청이 몰려도 락을 보유한 트랜잭션만 카운터를 검사·증가하므로
     * 선착순 maxUses 초과/lost update가 발생하지 않는다 (ADR-008·ADR-010, 좌석 hold와 동일 기조).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.code = :code")
    Optional<Coupon> findByCodeForUpdate(@Param("code") String code);
}
