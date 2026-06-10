package com.placeholder.domain.provider.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class SettlementResponse {
    private int settlementBalance;
    private List<SettlementItem> settlements;

    @Getter
    @Builder
    public static class SettlementItem {
        private Long transactionId;
        private int amount;
        private Long reservationId;
        private String eventTitle;
        private String seatLabel;
        private LocalDateTime confirmedAt;
    }
}
