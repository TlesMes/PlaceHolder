package com.placeholder.domain.queue.service;

import com.placeholder.domain.queue.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배치 입장 처리 서비스 (ADR-013, 분배는 ADR-017).
 *
 * <p>활성 대기열을 순회하며 원자 입장 스크립트({@link QueueRedisRepository#admit})를 호출한다. 입장 제어는
 * 두 레버를 함께 쓴다 — <b>ceiling</b>(동시 활성 세션 전역 상한, 앱 용량 기준)과 <b>rate</b>(초당 입장 수).
 * 캡 판정·ZPOPMIN·토큰 발급은 Lua가 원자적으로 처리하므로, 다중 인스턴스가 동시에 돌아도 상한이 정확하다.
 *
 * <p><b>이벤트 간 분배(ADR-017):</b> 틱 예산(rate)을 활성 이벤트 수로 균등 분할(quantum)해 배정하고,
 * 정수 입장 단위 때문에 남는 끝수는 이벤트별 deficit 장부로 다음 틱에 이월한다(DRR). 빈 큐의 몫은
 * 남은 큐로 재분배(work-conserving). 이 분배 계산은 best-effort이고, 전역 ceiling·rate 초과 불가는
 * 여전히 Lua가 최종 보장한다 — 분배가 어긋나도 캡은 깨지지 않는 구조.
 *
 * <p>deficit 장부는 인메모리 단일 인스턴스 전제(ADR-017 한계). 재시작 리셋 왜곡은 이벤트당 1슬롯 미만.
 *
 * <p>스케줄러(QueueAdmissionScheduler)와 타이밍 경계를 분리하기 위해 별도 서비스로 둔다. 순수 Redis 연산.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final QueueRedisRepository queueRepository;

    /** 동시 활성 세션(입장 토큰 보유) 전역 상한 (C). DB 풀이 아니라 앱 세션 용량 기준. */
    @Value("${queue.admission.max-active-sessions:200}")
    private int maxActiveSessions;

    /** 초당 입장 허용 수 (R). 유입 스파이크 평탄화. */
    @Value("${queue.admission.rate-per-second:20}")
    private int ratePerSecond;

    @Value("${queue.entry-token-ttl-minutes:5}")
    private int entryTokenTtlMinutes;

    /**
     * 이벤트별 끝수 이월 장부 (ADR-017). 값은 "받을 자격이 있었지만 정수 단위라 못 받은 몫"이며,
     * 잔여 슬롯을 먼저 받으면 음수(선지급)가 된다. (-1, 1] 범위를 유지한다 — 큐가 몫보다 짧아
     * 못 쓴 자격은 이월하지 않고 소멸시킨다(빈 큐 크레딧 축적 금지, DRR 표준).
     *
     * <p>운영에선 스케줄러 단일 스레드만 접근한다. 동시 호출(테스트의 스케줄러 race 등)에도
     * 구조 손상이 없도록 ConcurrentHashMap을 쓰되, 그 경우 장부 정밀도는 근사가 된다 —
     * 캡 정확성은 어차피 Lua가 보장하므로 분배 근사는 허용(ADR-017 best-effort).
     */
    private final Map<Long, Double> deficits = new ConcurrentHashMap<>();

    /**
     * 활성 대기열 전체에 대해 ceiling·rate 한도 내에서 입장 토큰을 발급한다.
     * 틱 예산을 이벤트 간 균등 분할(quantum + deficit 이월)로 나눠 어느 이벤트도 굶지 않게 한다.
     *
     * @return 이번 호출에서 발급한 입장 토큰 수
     */
    public int admitWaiting() {
        Set<Long> eventIds = queueRepository.activeQueueEventIds();

        // 빈 대기열은 활성 집합·장부에서 정리하고, 후보(비어있지 않은 큐)의 대기 인원을 수집한다.
        Map<Long, Long> sizes = new HashMap<>();
        for (Long eventId : eventIds) {
            long size = queueRepository.size(eventId);
            if (size == 0) {
                queueRepository.unmarkActiveQueue(eventId);
                deficits.remove(eventId);
            } else {
                sizes.put(eventId, size);
            }
        }
        deficits.keySet().retainAll(sizes.keySet());

        List<Long> candidates = new ArrayList<>(sizes.keySet());
        candidates.sort(null); // eventId 오름차순 — 결정적 순서(동률 tie-break 포함)

        int budget = ratePerSecond;
        int totalAdmitted = 0;
        boolean capReached = false;

        while (budget > 0 && !candidates.isEmpty() && !capReached) {
            Map<Long, Integer> grants = allocate(budget, candidates, sizes);
            int passAdmitted = 0;
            List<Long> stillWaiting = new ArrayList<>();

            for (Long eventId : candidates) {
                int grant = grants.getOrDefault(eventId, 0);
                long remaining = sizes.get(eventId);
                if (grant > 0 && !capReached) {
                    int admitted = admitForEvent(eventId, grant);
                    passAdmitted += admitted;
                    remaining -= admitted;
                    sizes.put(eventId, remaining);
                    if (admitted < grant && remaining > 0) {
                        // 큐에 사람이 남았는데 덜 입장했다 = 전역 캡(ceiling/rate) 도달.
                        // 이후 admit은 전부 거부되므로 이번 틱은 중단한다. (미소진 grant의
                        // 장부 크레딧은 소멸 — 한 틱 한정 왜곡, 분배는 best-effort. ADR-017)
                        capReached = true;
                    }
                }
                if (remaining > 0) {
                    stillWaiting.add(eventId);
                }
            }

            totalAdmitted += passAdmitted;
            budget -= passAdmitted;
            candidates = stillWaiting;
            if (passAdmitted == 0) {
                break; // 진전 없음 — 캡 거부 등. 다음 틱에 재시도.
            }
        }

        if (totalAdmitted > 0) {
            log.info("대기열 입장 토큰 {}건 발급 (활성 이벤트 {}개)", totalAdmitted, sizes.size());
        }
        return totalAdmitted;
    }

    /**
     * 틱 예산을 후보 이벤트에 배분한다 — 균등 quantum + deficit 이월(DRR) 2단계.
     *
     * <p><b>1단계(공정 몫 산정, 장부 회계):</b> 각 이벤트에 quantum(=budget/N)을 장부에 적립하고
     * 정수 부분만 배정한다. 플로어링으로 남은 슬롯은 장부가 큰 순(동률 시 eventId 오름차순)으로
     * 1개씩 추가 배정하고 장부에서 1을 차감한다(선지급 음수 허용) — 이 차감이 틱 간 수혜자를
     * 자동 교대시켜 커서 없이 기아를 배제한다.
     *
     * <p><b>2단계(대기 인원 캡 + 무상 재분배, work-conserving):</b> 큐가 몫보다 짧은 이벤트의
     * 잉여(몰수분)는 <b>장부 차감 없이</b> 남은 큐에 순환 배분한다 — 몰수분은 공짜지 대출이 아니다.
     * 차감하면 짧은 큐 덕에 더 받은 이벤트가 다음 틱에 자기 공정 몫을 깎여 되갚게 되는데,
     * 그 몫은 애초에 누구의 자격도 아니었기 때문이다. 전원 배정된 이벤트는 장부를 0으로
     * 리셋한다(못 쓴 자격 소멸 — 재충원 시 낡은 크레딧으로 몰아치지 못하게, DRR 표준).
     */
    private Map<Long, Integer> allocate(int budget, List<Long> candidates, Map<Long, Long> sizes) {
        Map<Long, Integer> grants = new HashMap<>();

        // 1단계 — 공정 몫 산정 (대기 인원 무시, Σ배정 == budget)
        double quantum = (double) budget / candidates.size();
        int assigned = 0;
        for (Long eventId : candidates) {
            double credit = deficits.getOrDefault(eventId, 0.0) + quantum;
            int grant = Math.max(0, Math.min((int) Math.floor(credit), budget - assigned));
            deficits.put(eventId, credit - grant);
            grants.put(eventId, grant);
            assigned += grant;
        }
        for (int fracLeft = budget - assigned; fracLeft > 0; fracLeft--) {
            Long best = candidates.get(0);
            for (Long eventId : candidates) {
                if (deficits.get(eventId) > deficits.get(best)) best = eventId;
            }
            grants.merge(best, 1, Integer::sum);
            deficits.merge(best, -1.0, Double::sum);
        }

        // 2단계 — 대기 인원 캡 → 몰수분을 장부 차감 없이 순환 재분배
        int forfeited = 0;
        for (Long eventId : candidates) {
            int size = sizes.get(eventId).intValue();
            if (grants.get(eventId) >= size) {
                forfeited += grants.get(eventId) - size;
                grants.put(eventId, size);
                deficits.put(eventId, 0.0);
            }
        }
        while (forfeited > 0) {
            boolean progressed = false;
            for (Long eventId : candidates) {
                if (forfeited == 0) break;
                if (grants.get(eventId) < sizes.get(eventId)) {
                    grants.merge(eventId, 1, Integer::sum);
                    if (grants.get(eventId) >= sizes.get(eventId)) {
                        deficits.put(eventId, 0.0); // 무상 배분으로도 전원 배정 → 동일하게 리셋
                    }
                    forfeited--;
                    progressed = true;
                }
            }
            if (!progressed) break; // 모든 큐 전원 배정 — 남는 예산은 소멸(rate는 틱마다 새로 시작)
        }
        return grants;
    }

    /**
     * 단일 이벤트에 대해 ceiling·rate 한도 내 최대 {@code max}명을 입장시킨다.
     * ceiling·rate 판정·발급은 Lua가 원자 처리.
     *
     * @return 실제 발급한 입장 토큰 수
     */
    public int admitForEvent(Long eventId, int max) {
        long now = System.currentTimeMillis();
        long ttlMs = Duration.ofMinutes(entryTokenTtlMinutes).toMillis();
        return queueRepository.admit(eventId, now, maxActiveSessions, ratePerSecond, ttlMs, max).size();
    }
}
