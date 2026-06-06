package com.placeholder.domain.coupon.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.coupon.dto.CouponRedeemResponse;
import com.placeholder.domain.coupon.entity.Coupon;
import com.placeholder.domain.coupon.entity.CouponRedemption;
import com.placeholder.domain.coupon.repository.CouponRedemptionRepository;
import com.placeholder.domain.coupon.repository.CouponRepository;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.CouponAlreadyRedeemedByUserException;
import com.placeholder.global.exception.custom.CouponNotFoundException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 쿠폰 상환 → 예약자 포인트 적립 (ADR-010).
 *
 * <p>동시성은 좌석 hold/confirm과 동일하게 <b>비관적 락</b>으로 직렬화한다(ADR-008 기조).
 * 쿠폰 행을 {@code findByCodeForUpdate}로 잠그면 같은 코드의 동시 상환이 한 줄로 처리되어
 * 선착순 maxUses 초과·lost update가 발생하지 않는다. 유저당 1회는 유니크 제약이 추가로 막는다.
 *
 * <p>(원자적 조건부 UPDATE도 검토했으나, 한 트랜잭션에서 카운터·유니크삽입·잔액락이 엇갈려
 * InnoDB 데드락이 빈발해 재시도 비용이 비관적 락 이득을 상쇄했다. 초고경합(인기 티켓팅/선착순)
 * 시나리오는 Redis 원자 연산으로 분리하는 것이 정석 — Phase E 후속. ADR-010 참고.)
 */
@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponRedeemService {

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;
    private final BookerAccountRepository bookerAccountRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final UserRepository userRepository;

    @Transactional
    public CouponRedeemResponse redeem(String code, Long bookerId) {
        // 1. 쿠폰 비관적 락 조회 — 동시 상환 요청 직렬화
        Coupon coupon = couponRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> new CouponNotFoundException("쿠폰을 찾을 수 없습니다"));

        User booker = userRepository.findByIdAndDeletedAtIsNull(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자를 찾을 수 없습니다"));

        // 2. 유저당 1회: 중복 기록 삽입 → 유니크 제약 위반이면 거부 (락 보유 중이므로 데드락 없이 직렬화됨)
        try {
            couponRedemptionRepository.saveAndFlush(CouponRedemption.builder()
                    .coupon(coupon)
                    .user(booker)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new CouponAlreadyRedeemedByUserException("이미 사용한 쿠폰입니다");
        }

        // 3. 카운터 증가 — 락 보유 상태에서 도메인 메서드로 검사·증가 (소진이면 CouponExhaustedException)
        coupon.redeem();

        // 4. 잔액 적립 (동일 유저 동시 작업 직렬화 위해 계정 비관적 락)
        BookerAccount bookerAccount = bookerAccountRepository.findByUserIdForUpdate(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));
        bookerAccount.charge(coupon.getAmount());

        // 5. CHARGE 트랜잭션 기록 (reservation 없음)
        pointTransactionRepository.save(PointTransaction.builder()
                .user(booker)
                .type(TransactionType.CHARGE)
                .amount(coupon.getAmount())
                .build());

        return CouponRedeemResponse.builder()
                .chargedAmount(coupon.getAmount())
                .balance(bookerAccount.getBalance())
                .build();
    }
}
