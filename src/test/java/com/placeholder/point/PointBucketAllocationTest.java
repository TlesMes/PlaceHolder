package com.placeholder.point;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.point.entity.PointAllocation;
import com.placeholder.domain.point.entity.PointBucket;
import com.placeholder.global.exception.custom.InsufficientPointException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재원 계층 배분 규칙 검증 (ADR-020) — 순수 도메인 단위 테스트.
 *
 * <p>배분은 {@code BookerAccount} 안의 산술이므로 DB도 Spring 컨텍스트도 필요 없다.
 * 통합 테스트로 덮으면 느릴 뿐 아니라, 정작 검증하려는 <b>경계</b>(계층을 걸친 차감, 부족 판정,
 * 실패 시 무변경)를 촘촘히 짜기 어렵다.
 */
class PointBucketAllocationTest {

    @Nested
    @DisplayName("소모 순서: EVENT → FREE → PAID")
    class SpendOrder {

        @Test
        @DisplayName("이벤트 잔액만으로 충당되면 무료·유료는 건드리지 않는다")
        void spendsEventFirst() {
            BookerAccount account = account(3_000, 2_000, 7_000);

            PointAllocation alloc = account.deduct(2_000);

            assertThat(alloc).isEqualTo(new PointAllocation(2_000, 0, 0));
            assertThat(account.getEventBalance()).isEqualTo(1_000);
            assertThat(account.getFreeBalance()).isEqualTo(2_000);
            assertThat(account.getPaidBalance()).isEqualTo(7_000);
        }

        @Test
        @DisplayName("계층을 걸친 차감: 이벤트를 비우고 무료로 넘어간다")
        void spansEventAndFree() {
            BookerAccount account = account(3_000, 2_000, 7_000);

            PointAllocation alloc = account.deduct(4_000);

            assertThat(alloc).isEqualTo(new PointAllocation(3_000, 1_000, 0));
            assertThat(account.getEventBalance()).isZero();
            assertThat(account.getFreeBalance()).isEqualTo(1_000);
            // 환불 재원인 유료분은 마지막까지 보존된다 — 이 순서가 서비스를 지키는 지점
            assertThat(account.getPaidBalance()).isEqualTo(7_000);
        }

        @Test
        @DisplayName("세 계층 전부에 걸치면 유료가 마지막으로 빠진다")
        void spansAllThree() {
            BookerAccount account = account(3_000, 2_000, 7_000);

            PointAllocation alloc = account.deduct(9_000);

            assertThat(alloc).isEqualTo(new PointAllocation(3_000, 2_000, 4_000));
            assertThat(account.getBalance()).isEqualTo(3_000);
            assertThat(account.refundableBalance()).isEqualTo(3_000);
        }

        @Test
        @DisplayName("배분 합은 언제나 차감액과 같다")
        void allocationSumEqualsAmount() {
            BookerAccount account = account(1_500, 2_500, 6_000);

            PointAllocation alloc = account.deduct(5_000);

            assertThat(alloc.total()).isEqualTo(5_000);
        }
    }

    @Nested
    @DisplayName("잔액 부족")
    class Insufficient {

        @Test
        @DisplayName("판정은 계층별이 아니라 총합 기준이다")
        void judgedByTotal() {
            // 어느 계층도 단독으로는 5,000에 못 미치지만 합계는 6,000이라 통과해야 한다
            BookerAccount account = account(2_000, 2_000, 2_000);

            PointAllocation alloc = account.deduct(5_000);

            assertThat(alloc).isEqualTo(new PointAllocation(2_000, 2_000, 1_000));
        }

        @Test
        @DisplayName("총합이 모자라면 예외 + 어느 계층도 변하지 않는다")
        void throwsAndLeavesBalancesUntouched() {
            BookerAccount account = account(1_000, 1_000, 1_000);

            assertThatThrownBy(() -> account.deduct(3_001))
                    .isInstanceOf(InsufficientPointException.class);

            // 부분 차감이 남으면 "돈은 줄었는데 예약은 실패"가 된다 — 무변경이 핵심
            assertThat(account.getEventBalance()).isEqualTo(1_000);
            assertThat(account.getFreeBalance()).isEqualTo(1_000);
            assertThat(account.getPaidBalance()).isEqualTo(1_000);
        }
    }

    @Nested
    @DisplayName("적립·환불 재원")
    class ChargeAndRefund {

        @Test
        @DisplayName("적립은 지정한 계층에만 들어간다")
        void chargeTargetsGivenBucket() {
            BookerAccount account = account(0, 0, 0);

            account.charge(1_000, PointBucket.EVENT);
            account.charge(2_000, PointBucket.FREE);
            account.charge(3_000, PointBucket.PAID);

            assertThat(account.getEventBalance()).isEqualTo(1_000);
            assertThat(account.getFreeBalance()).isEqualTo(2_000);
            assertThat(account.getPaidBalance()).isEqualTo(3_000);
            assertThat(account.getBalance()).isEqualTo(6_000);
        }

        @Test
        @DisplayName("환불 가능액은 유료분뿐 — 쿠폰이 아무리 많아도 늘지 않는다")
        void refundableIsPaidOnly() {
            BookerAccount account = account(50_000, 50_000, 1_000);

            assertThat(account.getBalance()).isEqualTo(101_000);
            assertThat(account.refundableBalance()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("단일 계층 차감은 그 계층 잔액을 넘을 수 없다 (총합이 충분해도)")
        void deductFromRespectsBucketCeiling() {
            BookerAccount account = account(10_000, 10_000, 1_000);

            assertThatThrownBy(() -> account.deductFrom(2_000, PointBucket.PAID))
                    .isInstanceOf(InsufficientPointException.class);

            assertThat(account.getPaidBalance()).isEqualTo(1_000);
        }
    }

    private static BookerAccount account(int event, int free, int paid) {
        return BookerAccount.builder()
                .eventBalance(event)
                .freeBalance(free)
                .paidBalance(paid)
                .build();
    }
}
