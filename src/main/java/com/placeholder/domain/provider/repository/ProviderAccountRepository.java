package com.placeholder.domain.provider.repository;

import com.placeholder.domain.provider.entity.ProviderAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, Long> {

    /** 조회 전용. 잔액을 <b>변경</b>할 때는 반드시 {@link #findByUserIdForUpdate}를 쓴다. */
    Optional<ProviderAccount> findByUserId(Long userId);

    /**
     * 정산액 적립을 위한 비관적 락 조회.
     *
     * <p>{@code settlementBalance += amount}는 read-modify-write이고 JPA 더티 체킹은
     * <b>절대값</b>({@code SET settlement_balance = ?})을 쓴다. 락 없이 부르면 서로 다른 예약자가
     * 같은 제공자의 <b>다른</b> 좌석을 동시에 확정할 때 좌석 락도 예약자 계정 락도 이들을
     * 직렬화하지 못해 적립이 서로 덮어써진다(lost update).
     *
     * <p>인기 이벤트의 정상 트래픽이 정확히 그 모양이라 예외적 상황이 아니다 —
     * {@code ProviderSettlementConcurrencyTest}가 10건 중 9건 유실을 재현한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProviderAccount p where p.user.id = :userId")
    Optional<ProviderAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
