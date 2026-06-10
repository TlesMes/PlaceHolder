package com.placeholder.domain.point.service;

import com.placeholder.domain.point.dto.PointHistoryResponse;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.reservation.entity.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointHistoryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_PERIOD_MONTHS = 3;

    private final PointTransactionRepository pointTransactionRepository;

    /**
     * 포인트 이력 cursor 페이징 조회 (ADR-012).
     *
     * @param userId 본인 userId (Controller에서 SecurityContext로 전달)
     * @param from   기간 시작. null이면 now - 3개월
     * @param to     기간 종료. null이면 now (cursor가 없을 때 cursor 기본값으로 사용)
     * @param cursor 이전 페이지 마지막 createdAt. null이면 to(또는 now)부터
     * @param size   페이지 크기. null이면 20, 최대 100
     */
    public PointHistoryResponse getHistory(
            Long userId,
            LocalDateTime from,
            LocalDateTime to,
            LocalDateTime cursor,
            Integer size) {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveFrom = from != null ? from : now.minusMonths(DEFAULT_PERIOD_MONTHS);
        LocalDateTime effectiveCursor = cursor != null
                ? cursor
                : (to != null ? to : now);
        int effectiveSize = Math.min(size != null ? size : DEFAULT_SIZE, MAX_SIZE);

        List<PointTransaction> transactions = pointTransactionRepository.findHistoryByCursor(
                userId,
                effectiveFrom,
                effectiveCursor,
                PageRequest.of(0, effectiveSize));

        List<PointHistoryResponse.TransactionItem> items = transactions.stream()
                .map(this::toItem)
                .toList();

        // 결과가 size와 같으면 다음 페이지가 있을 수 있음 → 마지막 createdAt을 cursor로
        LocalDateTime nextCursor = transactions.size() == effectiveSize
                ? transactions.get(transactions.size() - 1).getCreatedAt()
                : null;

        return PointHistoryResponse.builder()
                .items(items)
                .nextCursor(nextCursor)
                .build();
    }

    private PointHistoryResponse.TransactionItem toItem(PointTransaction tx) {
        Reservation reservation = tx.getReservation();
        Long reservationId = reservation != null ? reservation.getId() : null;
        String eventTitle = reservation != null
                ? reservation.getSeat().getEvent().getTitle()
                : null;

        return PointHistoryResponse.TransactionItem.builder()
                .transactionId(tx.getId())
                .type(tx.getType().name())
                .amount(tx.getAmount())
                .reservationId(reservationId)
                .eventTitle(eventTitle)
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
