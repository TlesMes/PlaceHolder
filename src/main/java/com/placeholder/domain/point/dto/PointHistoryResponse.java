package com.placeholder.domain.point.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class PointHistoryResponse {
    private List<TransactionItem> items;
    /** 다음 페이지 cursor (마지막 item의 createdAt). 더 이상 없으면 null. */
    private LocalDateTime nextCursor;

    @Getter
    @Builder
    public static class TransactionItem {
        private Long transactionId;
        /** CHARGE / DEDUCT / SETTLE */
        private String type;
        private int amount;
        /** CHARGE 타입은 null */
        private Long reservationId;
        /** CHARGE 타입은 null */
        private String eventTitle;
        private LocalDateTime createdAt;
    }
}
