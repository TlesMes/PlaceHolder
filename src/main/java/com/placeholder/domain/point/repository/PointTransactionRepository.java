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

}
