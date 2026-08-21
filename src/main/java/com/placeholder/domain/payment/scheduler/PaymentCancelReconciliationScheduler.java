package com.placeholder.domain.payment.scheduler;

import com.placeholder.domain.payment.service.PaymentCancelReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 역방향 대사 스케줄러 — 우리는 취소했는데 토스가 모르는 결제를 재시도한다 (ADR-019).
 *
 * <p>{@link PaymentReconciliationScheduler}와 같은 구조다: 스케줄러는 얇게, 처리는 서비스에 위임하고,
 * <b>성격이 다른 두 잡을 분리해 되돌릴 수 없는 결정을 새벽 잡에만 맡긴다.</b>
 *
 * <table>
 *   <tr><th></th><th>재시도 잡</th><th>포기 잡</th></tr>
 *   <tr><td>목적</td><td>누락된 취소 재전송 (사용자 돈이 묶여 있음)</td><td>끝내 실패한 건 확정</td></tr>
 *   <tr><td>주기</td><td>짧게 (기본 5분)</td><td>새벽 1회 (기본 04:20 KST)</td></tr>
 *   <tr><td>revert 권한</td><td><b>없음</b></td><td><b>있음</b></td></tr>
 * </table>
 *
 * <p><b>revert 권한을 새벽 잡에만 준 이유:</b> revert는 "현금 환불을 포기하고 포인트로 돌려준다"는
 * 금전적 결정이다. 자주 도는 잡이 이 권한을 가지면 <b>토스의 일시적 장애가 곧바로 환불 포기로
 * 이어진다.</b> 정방향 대사에서 만료(종결) 권한을 새벽 잡에만 준 것과 같은 논리 — 자주 도는 잡은
 * 되돌릴 수 없는 결정을 하지 않는다.
 *
 * <p><b>재시도 잡에 하한(min-age)을 둔 이유:</b> 지금 진행 중인 취소는 ②(토스 호출)를 수행하는 동안
 * 정확히 후보와 같은 모양이다. 멱등 키가 중복 환불을 막아주긴 하지만, 무의미한 외부 호출을 애초에
 * 하지 않는 편이 낫다.
 *
 * <p>정방향 대사와 새벽 시각을 어긋나게 둔다(04:00 vs 04:20) — 같은 순간에 두 배치가 토스 API 한도를
 * 함께 쓰지 않도록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.cancel-reconciliation.enabled", havingValue = "true",
        matchIfMissing = true)
public class PaymentCancelReconciliationScheduler {

    private final PaymentCancelReconciliationService reconciliationService;

    /** 이보다 최근에 취소된 건은 아직 ②를 수행 중일 수 있어 대상에서 제외한다. */
    @Value("${payment.cancel-reconciliation.min-age-minutes:5}")
    private int minAgeMinutes;

    /** 이 시간을 넘도록 토스 확인이 안 되면 재시도를 포기하고 포인트를 되돌린다. */
    @Value("${payment.cancel-reconciliation.give-up-hours:24}")
    private int giveUpHours;

    /**
     * 재시도 잡 — 누락된 취소를 빠르게 다시 보낸다. 포인트를 되돌리지 않는다(allowRevert=false).
     */
    @Scheduled(fixedDelayString = "${payment.cancel-reconciliation.interval-ms:300000}")
    public void retryUnconfirmed() {
        LocalDateTime now = LocalDateTime.now();
        run(now.minusHours(giveUpHours), now.minusMinutes(minAgeMinutes), false, "재시도");
    }

    /**
     * 포기 잡 — 새벽에 한 번, 오래도록 확인되지 않은 취소를 확정한다(allowRevert=true).
     *
     * <p>나이만 보고 일괄 포기하지 않고 토스를 다시 조회한다 — 24시간 된 미확인 취소가 실은 토스에서
     * 이미 취소돼 있을 수 있기 때문이다(재시도 잡이 그 시간대에 죽어 있었다면). 그 경우 포인트를
     * 되돌리면 <b>사용자가 돈과 포인트를 둘 다 갖게 되므로</b>, 차액이 0이면 확인 기록만 남긴다.
     *
     * <p>{@code zone}을 반드시 명시한다 — 서버 기본 시간대가 UTC면 "새벽 4시"가 한국 낮에 실행된다.
     */
    @Scheduled(cron = "${payment.cancel-reconciliation.give-up-cron:0 20 4 * * *}", zone = "Asia/Seoul")
    public void giveUpStale() {
        LocalDateTime now = LocalDateTime.now();
        run(EPOCH, now.minusHours(giveUpHours), true, "포기");
    }

    private void run(LocalDateTime from, LocalDateTime to, boolean allowRevert, String label) {
        try {
            reconciliationService.reconcile(from, to, allowRevert);
        } catch (RuntimeException e) {
            // 대사 실패가 스케줄러 스레드를 죽이면 이후 주기가 통째로 멈춘다 — 삼키고 다음 주기에 재시도.
            log.warn("역방향 대사({}) 실패 — 다음 주기 재시도: {}", label, e.getMessage(), e);
        }
    }

    /** 포기 잡의 스캔 하한. 사실상 무제한(그 이전 취소는 존재하지 않는다). */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
}
