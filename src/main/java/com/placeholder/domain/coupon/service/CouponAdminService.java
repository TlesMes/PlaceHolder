package com.placeholder.domain.coupon.service;

import com.placeholder.domain.coupon.dto.CouponCreateResponse;
import com.placeholder.domain.coupon.entity.Coupon;
import com.placeholder.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 쿠폰 생성 코어.
 *
 * <p>상환(CouponRedeemService)과 짝을 이루는 발급 측. 현재 진입 경로는 부하 측정용
 * {@code LoadTestCouponController}(@Profile("loadtest"))뿐이며, 운영 ADMIN 인증 경로는 후속이다.
 * 생성 로직 자체는 경로와 무관한 코어로 분리해 둔다(BookerAccount.charge와 동일 기조).
 */
@Service
@RequiredArgsConstructor
public class CouponAdminService {

    private final CouponRepository couponRepository;

    @Transactional
    public CouponCreateResponse create(String code, int amount, int maxUses) {
        Coupon coupon = Coupon.builder()
                .code(code)
                .amount(amount)
                .maxUses(maxUses)
                .build();

        try {
            couponRepository.saveAndFlush(coupon);
        } catch (DataIntegrityViolationException e) {
            // code 유니크 제약 위반 — 부하 setup 재실행 시 멱등하게 동작하도록 기존 쿠폰을 반환
            Coupon existing = couponRepository.findByCode(code)
                    .orElseThrow(() -> e);
            return toResponse(existing);
        }

        return toResponse(coupon);
    }

    private CouponCreateResponse toResponse(Coupon coupon) {
        return CouponCreateResponse.builder()
                .couponId(coupon.getId())
                .code(coupon.getCode())
                .amount(coupon.getAmount())
                .maxUses(coupon.getMaxUses())
                .build();
    }
}
