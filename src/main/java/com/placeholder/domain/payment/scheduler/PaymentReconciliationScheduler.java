package com.placeholder.domain.payment.scheduler;

import com.placeholder.domain.payment.service.PaymentReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 결제 대사 스케줄러 (ADR-018 삼중화의 세 번째 층).
 *
 * <p>성격이 다른 두 잡을 분리한다 — 스케줄러는 얇게 유지하고 처리는 서비스에 위임한다
 * (SeatExpiryScheduler와 동일 패턴).
 *
 * <table>
 *   <tr><th></th><th>보정 잡</th><th>만료 잡</th></tr>
 *   <tr><td>목적</td><td>누락 결제 적립 (사용자가 기다림)</td><td>고아 주문 청소 (급할 것 없음)</td></tr>
 *   <tr><td>주기</td><td>짧게 (기본 5분)</td><td>새벽 1회 (기본 04:00 KST)</td></tr>
 *   <tr><td>만료 권한</td><td><b>없음</b></td><td><b>있음</b></td></tr>
 * </table>
 *
 * <p><b>만료 권한을 새벽 잡에만 준 이유:</b> 토스는 결제창이 열리기 전(주문 생성 직후 수 초)에는
 * 그 orderId를 몰라 404를 준다. 이때 성급히 만료시키면 곧이어 결제를 마친 사용자의 confirm이
 * 거부되어 <b>막으려던 사고를 대사가 일으킨다.</b> 보정 잡은 아무리 자주 돌아도 주문을 종결시키지
 * 못하게 해 이 위험을 구조적으로 차단한다.
 *
 * <p><b>보정 잡에 상한(max-age)을 둔 이유:</b> 충전 버튼만 누르고 이탈한 주문은 만료 전까지 READY로
 * 남아 매 주기 토스에 조회된다. 상한을 넘긴 건은 새벽 배치 소관으로 넘겨 불필요한 외부 호출을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    /** 이보다 최근에 생성된 주문은 아직 결제 진행 중일 수 있어 대사 대상에서 제외한다. */
    @Value("${payment.reconciliation.min-age-minutes:5}")
    private int minAgeMinutes;

    /** 이보다 오래된 주문은 사실상 고아 — 새벽 배치 소관으로 넘긴다. */
    @Value("${payment.reconciliation.max-age-hours:2}")
    private int maxAgeHours;

    /** 이 시간을 넘긴 READY 주문만 만료(EXPIRED) 대상으로 삼는다. */
    @Value("${payment.reconciliation.expiry-age-hours:24}")
    private int expiryAgeHours;

    /**
     * 보정 잡 — 누락된 결제를 빠르게 찾아 적립한다. 주문을 종결시키지 않는다(allowExpire=false).
     */
    @Scheduled(fixedDelayString = "${payment.reconciliation.interval-ms:300000}")
    public void reconcileRecent() {
        LocalDateTime now = LocalDateTime.now();
        run(now.minusHours(maxAgeHours), now.minusMinutes(minAgeMinutes), false, "보정");
    }

    /**
     * 만료 잡 — 새벽에 한 번, 오래된 고아 주문을 종결한다(allowExpire=true).
     *
     * <p>나이만 보고 일괄 만료시키지 않고 토스를 다시 조회한다 — 24시간 된 READY가 실은 토스에서
     * DONE일 수 있기 때문이다(보정 잡이 그 시간대에 죽어 있었다면). 마지막 안전망 역할.
     *
     * <p>{@code zone}을 반드시 명시한다. 서버 기본 시간대가 UTC면 "새벽 4시"가 한국 낮 1시에
     * 실행되는 흔한 함정이 있다.
     */
    @Scheduled(cron = "${payment.reconciliation.expiry-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void expireOrphans() {
        LocalDateTime now = LocalDateTime.now();
        run(EPOCH, now.minusHours(expiryAgeHours), true, "만료");
    }

    private void run(LocalDateTime from, LocalDateTime to, boolean allowExpire, String label) {
        try {
            reconciliationService.reconcile(from, to, allowExpire);
        } catch (RuntimeException e) {
            // 대사 실패가 스케줄러 스레드를 죽이면 이후 주기가 통째로 멈춘다 — 삼키고 다음 주기에 재시도.
            log.warn("결제 대사({}) 실패 — 다음 주기 재시도: {}", label, e.getMessage(), e);
        }
    }

    /** 만료 잡의 스캔 하한. 사실상 무제한(그 이전 주문은 존재하지 않는다). */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
}
