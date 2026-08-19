package com.placeholder.domain.point.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.point.dto.PointBalanceResponse;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보유 포인트 조회 (ADR-020).
 *
 * <p>읽기 전용이므로 락을 잡지 않는다 — 표시용 값이고, 실제 차감·환불 시점에는 각 서비스가
 * 계정 행을 다시 잠그고 잔액을 재확인한다. 여기서 본 값과 확정 시점의 값이 다를 수 있다는 것은
 * 결함이 아니라 정상이다(그 사이 다른 요청이 잔액을 바꿀 수 있다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointBalanceService {

    private final BookerAccountRepository bookerAccountRepository;

    public PointBalanceResponse getBalance(Long userId) {
        BookerAccount account = bookerAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));

        return PointBalanceResponse.builder()
                .total(account.getBalance())
                .event(account.getEventBalance())
                .free(account.getFreeBalance())
                .paid(account.getPaidBalance())
                .refundable(account.refundableBalance())
                .build();
    }
}
