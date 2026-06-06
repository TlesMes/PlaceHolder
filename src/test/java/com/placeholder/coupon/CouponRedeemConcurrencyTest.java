package com.placeholder.coupon;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.coupon.entity.Coupon;
import com.placeholder.domain.coupon.repository.CouponRepository;
import com.placeholder.domain.coupon.service.CouponRedeemService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.CouponAlreadyRedeemedByUserException;
import com.placeholder.global.exception.custom.CouponExhaustedException;
import com.placeholder.global.exception.custom.CouponNotFoundException;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class CouponRedeemConcurrencyTest extends MySQLIntegrationTest {

    @Autowired CouponRedeemService couponRedeemService;
    @Autowired CouponRepository couponRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("maxUses=3 쿠폰에 서로 다른 10명이 동시 상환해도 정확히 3명만 성공한다 (exactly-K)")
    void concurrent_redeem_succeeds_exactly_maxUses_times() throws InterruptedException {
        // given - 선착순 3명 한정 쿠폰, 경쟁자 10명(각자 별도 계정)
        int threadCount = 10;
        int maxUses = 3;
        int amount = 1_000;
        Coupon coupon = persistCoupon("WELCOME-" + uniq(), amount, maxUses);
        List<Long> bookerIds = persistBookers(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger exhaustedCount = new AtomicInteger();

        // when - 전원 동시 출발
        for (int i = 0; i < threadCount; i++) {
            Long bookerId = bookerIds.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponRedeemService.redeem(coupon.getCode(), bookerId);
                    successCount.incrementAndGet();
                } catch (CouponExhaustedException e) {
                    exhaustedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then - 정확히 maxUses명만 성공, 초과 없음
        assertThat(successCount.get()).isEqualTo(maxUses);
        assertThat(exhaustedCount.get()).isEqualTo(threadCount - maxUses);

        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        assertThat(reloaded.getUsedCount()).isEqualTo(maxUses);

        // 적립도 정확히 maxUses회: 성공한 booker만 잔액 amount 보유
        int chargedAccounts = 0;
        for (Long bookerId : bookerIds) {
            int balance = bookerAccountRepository.findByUserId(bookerId).orElseThrow().getBalance();
            assertThat(balance).isIn(0, amount);
            if (balance == amount) chargedAccounts++;
        }
        assertThat(chargedAccounts).isEqualTo(maxUses);
    }

    @Test
    @DisplayName("같은 booker가 동일 쿠폰을 동시에 5회 상환해도 1회만 성공한다 (유저당 1회)")
    void same_booker_redeems_only_once() throws InterruptedException {
        // given - 넉넉한 maxUses라도 같은 유저는 1회만
        int threadCount = 5;
        int maxUses = 100;
        int amount = 1_000;
        Coupon coupon = persistCoupon("ONEPER-" + uniq(), amount, maxUses);
        Long bookerId = persistBookers(1).get(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponRedeemService.redeem(coupon.getCode(), bookerId);
                    successCount.incrementAndGet();
                } catch (CouponAlreadyRedeemedByUserException e) {
                    duplicateCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // then - 1회만 성공, 잔액은 amount 한 번분, 카운터도 1
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);

        int balance = bookerAccountRepository.findByUserId(bookerId).orElseThrow().getBalance();
        assertThat(balance).isEqualTo(amount);

        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        assertThat(reloaded.getUsedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰 코드는 404, 잔액 불변")
    void unknown_code_is_rejected() {
        // given
        Long bookerId = persistBookers(1).get(0);

        // when & then
        assertThatThrownBy(() -> couponRedeemService.redeem("NO-SUCH-CODE", bookerId))
                .isInstanceOf(CouponNotFoundException.class);

        int balance = bookerAccountRepository.findByUserId(bookerId).orElseThrow().getBalance();
        assertThat(balance).isZero();
    }

    @Test
    @DisplayName("이미 소진된 쿠폰(usedCount==maxUses) 상환은 409")
    void exhausted_coupon_is_rejected() {
        // given - maxUses=1을 이미 다 쓴 쿠폰
        int amount = 1_000;
        Coupon coupon = persistCoupon("DONE-" + uniq(), amount, 1);
        Long firstBooker = persistBookers(1).get(0);
        couponRedeemService.redeem(coupon.getCode(), firstBooker); // 소진

        Long secondBooker = persistBookers(1).get(0);

        // when & then
        assertThatThrownBy(() -> couponRedeemService.redeem(coupon.getCode(), secondBooker))
                .isInstanceOf(CouponExhaustedException.class);

        int balance = bookerAccountRepository.findByUserId(secondBooker).orElseThrow().getBalance();
        assertThat(balance).isZero();
    }

    // --- 테스트 데이터 셋업 헬퍼 ---

    @Transactional
    Coupon persistCoupon(String code, int amount, int maxUses) {
        return couponRepository.save(Coupon.builder()
                .code(code)
                .amount(amount)
                .maxUses(maxUses)
                .build());
    }

    @Transactional
    List<Long> persistBookers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User booker = userRepository.save(User.builder()
                    .email("booker-" + uniq() + "@test.com")
                    .passwordHash("hash")
                    .role(User.UserRole.BOOKER)
                    .build());
            bookerAccountRepository.save(BookerAccount.builder()
                    .user(booker)
                    .balance(0)
                    .build());
            ids.add(booker.getId());
        }
        return ids;
    }

    private static int uniq() {
        return uniqueId();
    }
}
