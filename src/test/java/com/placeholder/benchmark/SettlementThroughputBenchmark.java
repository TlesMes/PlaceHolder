package com.placeholder.benchmark;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.point.entity.PointBucket;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * <b>제공자 정산 적립의 처리량 — 측정 하네스.</b>
 *
 * <p>테스트가 아니라 <b>측정</b>이다. 단정은 "측정 중에도 정합성이 깨지지 않았다"를 확인하는
 * 최소한만 걸고, 결론은 stdout 표에서 읽는다. 클래스명이 {@code ...Benchmark}라 surefire 기본
 * include 패턴({@code *Test}/{@code Test*}/{@code *Tests})에 걸리지 않는다 — CI에서 자동으로
 * 돌지 않으며 실행은 명시적으로만 한다:
 *
 * <pre>{@code ./mvnw.cmd test -Dtest=SettlementThroughputBenchmark -DfailIfNoSpecifiedTests=false}</pre>
 *
 * <p><b>무엇을 재는가 — 지점이 아니라 형태.</b> 한 PC에서 뽑은 절대 처리량은 그 PC에서만 참이라
 * 재설계 판단의 입력이 못 된다(D-2가 그렇게 끝났다). 그래서 knee point는 재지 않고
 * <b>제공자 수 P를 대조 변수로</b> 둔다. 두 실험군이 같은 오염을 공유하므로 비율에서 상쇄된다.
 *
 * <p><b>왜 인프로세스인가.</b> {@code confirmReservation}을 스레드풀로 직접 호출하면 HTTP·Tomcat·
 * JSON·JWT가 경로에서 빠지고 남는 것은 묻는 대상 — DB 트랜잭션과 그 안의 행 락 — 뿐이다.
 * 부하생성기가 원리적으로 사라지므로 D-2의 coordinated omission이 성립하지 않는다.
 * 대신 이 숫자는 <b>확정 트랜잭션의 처리량이지 API 처리량이 아니다.</b>
 *
 * <p>설계·판정 기준: {@code docs/performance/provider-settlement-throughput.md} 2절.
 */
@SuppressWarnings("null")
@SpringBootTest(properties = {
        // 기본 풀 10 < THREADS 32이면 풀이 먼저 병목이 되어 제공자 행 경합이 가려진다.
        // D-2에서 풀 크기로 결론이 세 번 뒤집힌 전례가 있어 값을 명시하고 결과에 함께 기록한다.
        "spring.datasource.hikari.maximum-pool-size=48"
})
@ActiveProfiles("test")
class SettlementThroughputBenchmark extends MySQLIntegrationTest {

    private static final int TOTAL_CONFIRMS = 240;   // 1·2·4·8로 나누어떨어진다
    private static final int THREADS = 32;
    private static final int[] PROVIDER_COUNTS = {1, 2, 4, 8};
    private static final int REPEATS = 3;
    private static final int PRICE = 5_000;

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @PersistenceContext EntityManager entityManager;

    /**
     * 계측 + 비교군 전환 지점. 스파이는 두 가지를 한다:
     * <ol>
     *   <li>잠금 조회의 <b>소요 시간</b>을 기록한다 = {@code SELECT … FOR UPDATE}가 막힌 시간(락 대기)</li>
     *   <li>반환 직후 {@code TransactionSynchronization}을 걸어 커밋 완료까지를 잰다(락 보유)</li>
     * </ol>
     * 스파이 자체의 오버헤드(Mockito 호출 기록)는 모든 비교군에 동일하게 걸리므로 비율 해석에
     * 영향을 주지 않는다 — 이 측정이 절대값을 결론으로 쓰지 않는 이유이기도 하다.
     */
    @MockitoSpyBean ProviderAccountRepository providerAccountRepository;

    /** 비교군 ② — 잠금 조회를 비잠금으로 갈아끼운다(락 제거 상한 관측용). */
    private volatile boolean lockDisabled = false;

    private final Queue<Long> lockWaitNanos = new ConcurrentLinkedQueue<>();
    private final Queue<Long> lockHoldNanos = new ConcurrentLinkedQueue<>();
    private final Queue<Long> confirmNanos = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void instrument() {
        doAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            long t0 = System.nanoTime();
            Optional<ProviderAccount> result = loadProviderAccount(userId, !lockDisabled);
            long acquired = System.nanoTime();
            lockWaitNanos.add(acquired - t0);

            // 락은 획득부터 커밋까지 유지된다 — 보유 시간의 끝은 메서드 반환이 아니라 커밋이다.
            //
            // afterCompletion이 아니라 afterCommit을 쓰는 이유: 확정 경로는 afterCommit에서
            // Redis 토큰 회수를 하는데(ADR-013), 그 호출이 보유 시간에 섞이면 안 된다.
            // 동기화는 등록 순서대로 실행되고 이 계측은 서비스보다 먼저 등록되므로(락 획득 = 7단계,
            // 토큰 회수 등록 = 8단계) Redis 호출 앞에서 시각을 찍는다.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        lockHoldNanos.add(System.nanoTime() - acquired);
                    }
                });
            }
            return result;
        }).when(providerAccountRepository).findByUserIdForUpdate(anyLong());
    }

    /**
     * 스파이 자리에서 원래 조회를 대신 수행한다.
     *
     * <p>스파이에 원본 호출을 위임하는 두 방법이 모두 막혀 있다: {@code invocation.callRealMethod()}는
     * Spring Data 리포지토리가 <b>구현 없는 인터페이스 메서드</b>라 부를 수 없고(확정 240건 전량 실패로
     * 발현), {@code getSpiedInstance()}는 {@code @MockitoSpyBean}이 원본을 default answer로 감싸므로
     * null이다. 그래서 리포지토리와 <b>같은 JPQL·같은 락 모드</b>를 EntityManager로 직접 실행한다 —
     * 발행되는 SQL이 동일하므로 측정 대상은 그대로다.
     *
     * @param lock {@code true}면 비교군 ①(현재 프로덕션 경로), {@code false}면 비교군 ②(락 제거 상한)
     */
    private Optional<ProviderAccount> loadProviderAccount(Long userId, boolean lock) {
        TypedQuery<ProviderAccount> query = entityManager
                .createQuery("select p from ProviderAccount p where p.user.id = :userId", ProviderAccount.class)
                .setParameter("userId", userId);
        if (lock) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultList().stream().findFirst();
    }

    // ------------------------------------------------------------------ 비교군 ①

    @Test
    @DisplayName("① 제공자 수 대조 — 처리량이 제공자 수에 어떤 모양으로 반응하는가")
    void providerCountSweep() throws InterruptedException {
        List<Row> rows = new ArrayList<>();

        // 워밍업 1회 폐기 — JIT·커넥션 풀 채우기·Hibernate 초회 비용이 첫 회차에 몰린다
        runOnce(1);

        for (int providers : PROVIDER_COUNTS) {
            for (int rep = 0; rep < REPEATS; rep++) {
                rows.add(runOnce(providers));
            }
        }

        report("① 락 있음 (현재 프로덕션 경로)", rows);
    }

    // ------------------------------------------------------------------ 비교군 ②

    @Test
    @DisplayName("② 락 제거 상한 — 파생 재설계로 얻을 수 있는 최대치 (정합성 없음, 관측용)")
    void lockRemovedUpperBound() throws InterruptedException {
        lockDisabled = true;
        try {
            runOnce(1);
            List<Row> rows = new ArrayList<>();
            for (int rep = 0; rep < REPEATS; rep++) {
                rows.add(runOnce(1));
            }
            report("② 락 제거 상한 (P=1, 잔액 유실 허용)", rows);
        } finally {
            lockDisabled = false;
        }
    }

    // ------------------------------------------------------------------ 실행

    private Row runOnce(int providerCount) throws InterruptedException {
        Fixture f = persistFixture(providerCount);
        lockWaitNanos.clear();
        lockHoldNanos.clear();
        confirmNanos.clear();

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL_CONFIRMS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Exception> firstFailure = new AtomicReference<>();

        for (int i = 0; i < TOTAL_CONFIRMS; i++) {
            Long seatId = f.seatIds.get(i);
            Long bookerId = f.bookerIds.get(i);
            executor.submit(() -> {
                try {
                    start.await();
                    long t0 = System.nanoTime();
                    reservationService.confirmReservation(seatId, bookerId);
                    confirmNanos.add(System.nanoTime() - t0);
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    // 삼키면 "0건 성공"만 남고 원인을 잃는다 — 첫 실패를 단정 메시지에 실어 보낸다
                    firstFailure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }

        // 작업(240) > 스레드(32)이므로 "전원이 출발선에 섰는지"를 래치로 기다리면 안 된다 —
        // 스레드 32개가 start.await()에 걸린 채 나머지 208개는 큐에서 대기하므로 영원히 안 모인다.
        // 제출을 모두 마친 뒤 곧바로 출발시키고, 그 순간부터 시계를 잰다.
        long startedAt = System.nanoTime();
        start.countDown();
        boolean finished = done.await(5, TimeUnit.MINUTES);
        long wallNanos = System.nanoTime() - startedAt;
        executor.shutdownNow();

        assertThat(finished).as("측정이 시간 안에 끝나야 한다").isTrue();
        assertThat(succeeded.get())
                .as("좌석·예약자가 모두 다르므로 전원 확정되어야 한다 (첫 실패: %s)", firstFailure.get())
                .isEqualTo(TOTAL_CONFIRMS);

        if (!lockDisabled) {
            // 측정 중에도 정합성은 깨지지 않아야 한다 (비교군 ②는 정의상 유실되므로 제외)
            for (Long providerId : f.providerIds) {
                int settleRows = pointTransactionRepository
                        .findByTypeAndUserId(TransactionType.SETTLE, providerId).size();
                ProviderAccount account = providerAccountRepository.findByUserId(providerId).orElseThrow();
                assertThat(account.getSettlementBalance())
                        .as("제공자 %d — 정산 잔액이 SETTLE 이력 합과 일치해야 한다", providerId)
                        .isEqualTo(settleRows * PRICE);
            }
        }

        double seconds = wallNanos / 1_000_000_000.0;
        return new Row(providerCount, TOTAL_CONFIRMS / seconds,
                percentile(confirmNanos, 50), percentile(confirmNanos, 95), percentile(confirmNanos, 99),
                percentile(lockWaitNanos, 50), percentile(lockWaitNanos, 95),
                percentile(lockHoldNanos, 50), percentile(lockHoldNanos, 95));
    }

    // ------------------------------------------------------------------ 리포트

    private record Row(int providers, double throughput,
                       double confirmP50, double confirmP95, double confirmP99,
                       double waitP50, double waitP95,
                       double holdP50, double holdP95) {
    }

    private void report(String arm, List<Row> rows) {
        int[] groups = rows.stream().mapToInt(Row::providers).distinct().sorted().toArray();

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 제공자 정산 처리량 측정 — ").append(arm).append(" ===\n");
        sb.append("조건: 총 확정 ").append(TOTAL_CONFIRMS).append("건 / 스레드 ").append(THREADS)
                .append(" / Hikari 48 / 반복 ").append(REPEATS).append("회 중앙값 / 워밍업 1회 폐기\n");
        sb.append(String.format("%-4s %10s %10s %10s %10s %10s %10s %10s %10s%n",
                "P", "확정/초", "지연p50", "지연p95", "지연p99",
                "락대기p50", "락대기p95", "락보유p50", "락보유p95"));

        for (int providers : groups) {
            List<Row> group = rows.stream().filter(r -> r.providers() == providers).toList();
            sb.append(String.format("%-4d %10.1f %10s %10s %10s %10s %10s %10s %10s%n",
                    providers, median(group, Row::throughput),
                    ms(group, Row::confirmP50), ms(group, Row::confirmP95), ms(group, Row::confirmP99),
                    ms(group, Row::waitP50), ms(group, Row::waitP95),
                    ms(group, Row::holdP50), ms(group, Row::holdP95)));
        }

        // 형태: P=1 대비 배수. 이 비율이 결론이고 위의 절대값은 참고치일 뿐이다.
        double baseline = median(rows.stream().filter(r -> r.providers() == groups[0]).toList(), Row::throughput);
        sb.append("\n형태(P=").append(groups[0]).append(" 대비 처리량 배수): ");
        for (int providers : groups) {
            List<Row> group = rows.stream().filter(r -> r.providers() == providers).toList();
            sb.append(String.format("P=%d → %.2fx   ", providers, median(group, Row::throughput) / baseline));
        }

        // 자릿수: 직렬 구간의 보유 시간이 곧 제공자당 상한이다 (부하를 밀어서 얻는 값이 아니다).
        //
        // 반드시 최소 P의 값을 쓴다 — P가 커지면 동시 커밋이 늘어 보유 시간 자체가 길어지므로
        // P를 섞은 중앙값은 상한을 과소평가한다. 비교군 ②에는 애초에 락이 없어(대기 ≈0) 이 값이
        // 락 보유가 아니라 "읽기→커밋 구간"일 뿐이라 상한으로 해석하면 안 된다.
        if (!lockDisabled) {
            List<Row> smallest = rows.stream().filter(r -> r.providers() == groups[0]).toList();
            double holdSeconds = median(smallest, Row::holdP50) / 1_000_000_000.0;
            if (holdSeconds > 0) {
                sb.append(String.format("%n자릿수(제공자당 상한 ≈ 1/락보유시간, P=%d 기준): %.0f건/초   [요구 기준선 ≈100건/초]%n",
                        groups[0], 1 / holdSeconds));
            }
        }
        System.out.println(sb);
    }

    private static String ms(List<Row> group, ToDoubleFunction<Row> field) {
        return String.format("%.1fms", median(group, field) / 1_000_000.0);
    }

    private static double median(List<Row> rows, ToDoubleFunction<Row> field) {
        if (rows.isEmpty()) return 0;
        double[] sorted = rows.stream().mapToDouble(field).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    private static double percentile(Queue<Long> samples, int p) {
        if (samples.isEmpty()) return 0;
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }

    // ------------------------------------------------------------------ 픽스처

    private record Fixture(List<Long> providerIds, List<Long> bookerIds, List<Long> seatIds) {
    }

    /**
     * 제공자 {@code providerCount}명에게 좌석을 균등 분배하고, 좌석마다 서로 다른 예약자가
     * HELD 상태로 잡고 있게 만든다. 예약자를 좌석마다 따로 두는 이유는 예약자 계정 락으로
     * 요청들이 직렬화되면 대조 변수가 오염되기 때문이다.
     *
     * <p>단일 트랜잭션으로 묶는다 — 건별 저장은 커밋이 수백 회 일어나 픽스처 구축이 측정보다
     * 오래 걸린다.
     */
    private Fixture persistFixture(int providerCount) {
        return transactionTemplate.execute(status -> {
            List<Long> providerIds = new ArrayList<>();
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < providerCount; i++) {
                User provider = userRepository.save(User.builder()
                        .email("bench-provider-" + uniqueId() + "@test.com")
                        .passwordHash("hash")
                        .role(User.UserRole.PROVIDER)
                        .build());
                providerAccountRepository.save(ProviderAccount.builder().user(provider).build());
                events.add(eventRepository.save(Event.builder()
                        .provider(provider)
                        .title("정산 처리량 측정 이벤트")
                        .venue("벤치마크홀")
                        .eventAt(LocalDateTime.now().plusDays(1))
                        .build()));
                providerIds.add(provider.getId());
            }

            List<Long> bookerIds = new ArrayList<>();
            List<Long> seatIds = new ArrayList<>();
            for (int i = 0; i < TOTAL_CONFIRMS; i++) {
                User booker = userRepository.save(User.builder()
                        .email("bench-booker-" + uniqueId() + "@test.com")
                        .passwordHash("hash")
                        .role(User.UserRole.BOOKER)
                        .build());
                BookerAccount account = BookerAccount.builder().user(booker).build();
                account.charge(PRICE, PointBucket.PAID);
                bookerAccountRepository.save(account);

                Seat seat = seatRepository.save(Seat.builder()
                        .event(events.get(i % providerCount))
                        .label("B-" + uniqueId())
                        .price(PRICE)
                        .status(Seat.SeatStatus.HELD)
                        .heldBy(booker)
                        .heldUntil(LocalDateTime.now().plusMinutes(30))
                        .build());

                bookerIds.add(booker.getId());
                seatIds.add(seat.getId());
            }
            return new Fixture(providerIds, bookerIds, seatIds);
        });
    }
}
