package com.placeholder.domain.provider.service;

import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.dto.SettlementResponse;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.reservation.entity.Reservation;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProviderAccountService {

    private final ProviderAccountRepository providerAccountRepository;
    private final PointTransactionRepository pointTransactionRepository;

    public SettlementResponse getMySettlement(Long providerId) {
        ProviderAccount account = providerAccountRepository.findByUserId(providerId)
                .orElseThrow(() -> new UserNotFoundException("제공자 계정을 찾을 수 없습니다"));

        List<PointTransaction> settlements =
                pointTransactionRepository.findSettlementsByProviderId(providerId);

        List<SettlementResponse.SettlementItem> items = settlements.stream()
                .map(tx -> {
                    Reservation r = tx.getReservation();
                    Seat seat = r.getSeat();
                    return SettlementResponse.SettlementItem.builder()
                            .transactionId(tx.getId())
                            .amount(tx.getAmount())
                            .reservationId(r.getId())
                            .eventTitle(seat.getEvent().getTitle())
                            .seatLabel(seat.getLabel())
                            .confirmedAt(r.getConfirmedAt())
                            .build();
                })
                .toList();

        return SettlementResponse.builder()
                .settlementBalance(account.getSettlementBalance())
                .settlements(items)
                .build();
    }
}
