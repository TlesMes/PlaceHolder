package com.placeholder.domain.point.repository;

import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    List<PointTransaction> findByUserId(Long userId);

    List<PointTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<PointTransaction> findByReservationId(Long reservationId);

    List<PointTransaction> findByTypeAndUserId(TransactionType type, Long userId);

    /**
     * 포인트 이력 cursor 페이징 (ADR-012).
     * - WHERE user_id = ? AND created_at >= from AND created_at < cursor
     * - ORDER BY created_at DESC LIMIT size
     * - reservation은 CHARGE 타입일 때 null → left join fetch
     * - 단일 idx_pt_user_id 인덱스로 user_id 좁힌 뒤 메모리에서 정렬 (도메인 특성상 사용자당 거래량 적음)
     */
    @Query("select pt from PointTransaction pt " +
           "left join fetch pt.reservation r " +
           "left join fetch r.seat s " +
           "left join fetch s.event " +
           "where pt.user.id = :userId " +
           "and pt.createdAt >= :from " +
           "and pt.createdAt < :cursor " +
           "order by pt.createdAt desc")
    List<PointTransaction> findHistoryByCursor(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("cursor") LocalDateTime cursor,
            Pageable pageable);

    /**
     * 제공자 정산 잔액 — SETTLE 원장의 합 (ADR-021).
     *
     * <p>확정 경로가 잔액 컬럼을 갱신하지 않으므로 이 합이 잔액이다. 이미 로드하는 목록
     * ({@link #findSettlementsByProviderId})을 메모리에서 더하지 않는 이유는, 그러면 잔액이
     * "전건 조회"에 묶여 정산 조회 페이징이 들어오는 순간 페이지 합으로 쪼개져 깨지기 때문이다.
     *
     * <p>{@code idx_pt_settlement_sum (user_id, type, created_at, amount)}가 커버링이라 본체
     * 테이블을 읽지 않는다. 인덱스가 없으면 user_id로 좁힌 뒤 <b>행마다</b> amount를 읽으러
     * 본체를 찾아가야 한다 — 판매량에 비례해 랜덤 접근이 늘어난다.
     */
    @Query("select coalesce(sum(pt.amount), 0) from PointTransaction pt " +
           "where pt.user.id = :providerId " +
           "and pt.type = com.placeholder.domain.point.entity.PointTransaction.TransactionType.SETTLE")
    long sumSettlementByProviderId(@Param("providerId") Long providerId);

    /**
     * 제공자 정산 거래 목록 — SETTLE 타입만, reservation/seat/event fetch join.
     * 사용자당 정산 건수가 작은 도메인이라 페이징 미적용.
     */
    @Query("select pt from PointTransaction pt " +
           "join fetch pt.reservation r " +
           "join fetch r.seat s " +
           "join fetch s.event " +
           "where pt.user.id = :providerId " +
           "and pt.type = com.placeholder.domain.point.entity.PointTransaction.TransactionType.SETTLE " +
           "order by pt.createdAt desc")
    List<PointTransaction> findSettlementsByProviderId(@Param("providerId") Long providerId);
}
