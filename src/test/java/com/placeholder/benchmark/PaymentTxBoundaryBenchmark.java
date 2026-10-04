package com.placeholder.benchmark;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.payment.client.TossPaymentClient;
import com.placeholder.domain.payment.client.TossPaymentResult;
import com.placeholder.domain.payment.dto.TossWebhookPayload;
import com.placeholder.domain.payment.entity.PaymentOrder;
import com.placeholder.domain.payment.entity.PaymentOrder.PaymentStatus;
import com.placeholder.domain.payment.repository.PaymentOrderRepository;
import com.placeholder.domain.payment.service.PaymentConfirmService;
import com.placeholder.domain.payment.service.PaymentOrderService;
import com.placeholder.domain.payment.service.PaymentSettlementService;
import com.placeholder.domain.payment.service.PaymentWebhookService;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * <b>결제 승인의 트랜잭션 경계 비교 — 측정 하네스.</b>
 *
 * <p>질문: "결제사 호출을 트랜잭션 안에서 하면 결제사 응답을 기다리는 동안 DB 커넥션을 점유해
 * 좌석 홀드가 영향을 받는다"가 숫자로 얼마인가 (ADR-018 2번의 근거를 측정으로 확인).
 *
 * <p>테스트가 아니라 <b>측정</b>이다. 단정은 "측정 중에도 정합성이 깨지지 않았다"는 최소한만 걸고
 * 결론은 stdout 표에서 읽는다. 클래스명이 {@code ...Benchmark}라 surefire 기본 include 패턴에 걸리지
 * 않아 CI에서 자동 실행되지 않는다:
 *
 * <pre>{@code ./mvnw.cmd test -Dtest=PaymentTxBoundaryBenchmark -DfailIfNoSpecifiedTests=false}</pre>
 *
 * <p><b>비교군</b>
 * <ul>
 *   <li><b>A</b> — {@link InTxConfirmService}: 주문 행 잠금 → 결제사 호출 → 적립을 <b>하나의</b>
 *       {@code @Transactional}에서 수행. 이 테스트 안에만 있는 대조군이다.</li>
 *   <li><b>B</b> — 현재 구조 {@link PaymentConfirmService}: 짧은 검증 tx → 트랜잭션 밖 결제사 호출 →
 *       짧은 적립 tx ({@link PaymentSettlementService#settle}).</li>
 * </ul>
 * 적립 로직은 두 비교군 모두 운영 코드의 {@code settle}을 그대로 쓴다. 달라지는 것은 경계뿐이다.
 *
 * <p><b>계측 방법 — 운영 코드 무수정.</b> {@link ProbeConfig}가 HikariDataSource 빈을 감싸서
 * <ul>
 *   <li>{@code getConnection()} 소요 = <b>커넥션 획득 대기</b></li>
 *   <li>커넥션 반납({@code close()})까지 = <b>커넥션 보유 시간</b>. 트랜잭션은 시작 시 커넥션을 받고
 *       커밋 후 반납하므로 "트랜잭션 시작~커밋"을 바깥에서 감싸는 값이다</li>
 *   <li>{@code payment_orders ... for update} 실행 소요 = <b>주문 행 락 대기</b> (웹훅 실험)</li>
 * </ul>
 * 을 호출 스레드의 {@link Probe}에 적는다. 측정 대상 스레드만 Probe를 꽂으므로 다른 스레드는 영향 없다.
 */
@SuppressWarnings("null")
@SpringBootTest(properties = {
        // 측정 조건으로 고정한다. 결과 표에도 같이 찍는다.
        "spring.datasource.hikari.maximum-pool-size=" + PaymentTxBoundaryBenchmark.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=" + PaymentTxBoundaryBenchmark.POOL_SIZE
})
@ActiveProfiles("test")
@Import({PaymentTxBoundaryBenchmark.ProbeConfig.class, PaymentTxBoundaryBenchmark.InTxConfirmService.class})
class PaymentTxBoundaryBenchmark extends MySQLIntegrationTest {

    static final int POOL_SIZE = 10;
    private static final int PAYMENTS = 20;
    private static final int HOLD_THREADS = 20;
    private static final int[] DELAYS_MS = {0, 500, 2000};
    private static final int REPEATS = 3;
    private static final int AMOUNT = 10_000;
    private static final long SAMPLE_INTERVAL_MS = 50;
    /** 홀드는 좌석마다 한 번만 성공하므로 회차 안에서 소진되지 않을 만큼 미리 만든다. 소진되면 실패로 처리. */
    private static final int SEAT_POOL = 40_000;
    /** 웹훅 실험: 승인 호출 시작 후 이만큼 뒤에 웹훅을 보낸다. */
    private static final long WEBHOOK_OFFSET_MS = 100;

    @Autowired PaymentConfirmService confirmService;
    @Autowired InTxConfirmService inTxConfirmService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired PaymentOrderService orderService;
    @Autowired PaymentOrderRepository paymentOrderRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired SeatService seatService;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;

    @MockitoBean TossPaymentClient tossClient;

    /** 가짜 결제사의 승인 응답 지연. 회차마다 바꾼다. */
    private static volatile long tossDelayMs;

    private HikariPoolMXBean pool;
    private Long holdEventId;
    private List<Long> holdSeatIds;
    private List<Long> holdUserIds;

    enum Arm { A, B }

    @BeforeEach
    void setUp() throws SQLException {
        // 승인: 지정 지연만큼 응답을 기다린 뒤 DONE
        when(tossClient.confirm(any(), any(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(tossDelayMs);
            return new TossPaymentResult(inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2));
        });
        // 웹훅 재조회: 즉시 DONE. 지연을 주면 웹훅이 락을 요청하는 시점이 지연만큼 뒤로 밀려
        // A의 락 보유 구간과 겹치지 않게 되므로, 락 대기를 보려는 이 실험에서는 0으로 둔다.
        when(tossClient.getPayment(any())).thenAnswer(inv ->
                new TossPaymentResult(inv.getArgument(0), "order", "DONE", AMOUNT));

        pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        assertThat(pool).as("Hikari 풀이 기동돼 있어야 한다").isNotNull();
    }

    // ================================================================== 실험 1: 결제 승인 중 좌석 홀드

    @Test
    @DisplayName("결제 승인 20건 진행 중 좌석 홀드 — 트랜잭션 경계 A/B × 결제사 지연")
    void holdUnderPayments() throws Exception {
        persistHoldFixture();

        // 워밍업 — 비교군마다 1회 폐기 (JIT·Hibernate 초회 비용·A 경로 초기화)
        runHoldUnderPayments(Arm.A, 0);
        runHoldUnderPayments(Arm.B, 0);

        List<HoldRun> runs = new ArrayList<>();
        for (int delay : DELAYS_MS) {
            for (int rep = 0; rep < REPEATS; rep++) {
                // 비교군을 번갈아 돌려 시간에 따른 드리프트가 한쪽에만 쌓이지 않게 한다
                for (Arm arm : Arm.values()) {
                    runs.add(runHoldUnderPayments(arm, delay));
                }
            }
        }
        reportHold(runs);
    }

    private HoldRun runHoldUnderPayments(Arm arm, int delayMs) throws Exception {
        tossDelayMs = delayMs;
        List<PayTarget> targets = persistPaymentTargets(PAYMENTS);

        Queue<HoldSample> holds = new ConcurrentLinkedQueue<>();
        Queue<PaySample> pays = new ConcurrentLinkedQueue<>();
        Queue<Integer> activeSamples = new ConcurrentLinkedQueue<>();
        Queue<Integer> waitingSamples = new ConcurrentLinkedQueue<>();
        AtomicInteger seatCursor = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean seatsExhausted = new AtomicBoolean();
        AtomicInteger holdFailures = new AtomicInteger();
        AtomicReference<Exception> firstHoldFailure = new AtomicReference<>();
        AtomicReference<Exception> firstPayFailure = new AtomicReference<>();

        ExecutorService holdExec = Executors.newFixedThreadPool(HOLD_THREADS);
        ExecutorService payExec = Executors.newFixedThreadPool(PAYMENTS);
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch ready = new CountDownLatch(HOLD_THREADS + PAYMENTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch payDone = new CountDownLatch(PAYMENTS);

        for (int t = 0; t < HOLD_THREADS; t++) {
            Long userId = holdUserIds.get(t);
            holdExec.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                while (!stop.get()) {
                    int idx = seatCursor.getAndIncrement();
                    if (idx >= holdSeatIds.size()) {
                        seatsExhausted.set(true);
                        return;
                    }
                    Probe probe = Probe.begin();
                    long t0 = System.nanoTime();
                    try {
                        seatService.holdSeat(holdSeatIds.get(idx), userId);
                        holds.add(new HoldSample(System.nanoTime() - t0, probe.acquireNanos));
                    } catch (Exception e) {
                        holdFailures.incrementAndGet();
                        firstHoldFailure.compareAndSet(null, e);
                    } finally {
                        Probe.end();
                    }
                }
            });
        }

        for (PayTarget target : targets) {
            payExec.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                Probe probe = Probe.begin();
                long t0 = System.nanoTime();
                try {
                    confirm(arm, target);
                    pays.add(new PaySample(System.nanoTime() - t0, probe.connHeldNanos, probe.connCount));
                } catch (Exception e) {
                    firstPayFailure.compareAndSet(null, e);
                } finally {
                    Probe.end();
                    payDone.countDown();
                }
            });
        }

        // 작업 수 = 스레드 수라 전원이 출발선에 설 수 있다 — 동시 출발을 보장한다
        ready.await();
        sampler.scheduleAtFixedRate(() -> {
            activeSamples.add(pool.getActiveConnections());
            waitingSamples.add(pool.getThreadsAwaitingConnection());
        }, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        long startedAt = System.nanoTime();
        start.countDown();

        // 측정 구간 = 결제 20건이 모두 끝날 때까지. 그동안 홀드 스레드는 쉬지 않고 요청한다
        boolean paymentsFinished = payDone.await(2, TimeUnit.MINUTES);
        stop.set(true);
        holdExec.shutdown();
        boolean holdsFinished = holdExec.awaitTermination(1, TimeUnit.MINUTES);
        long windowNanos = System.nanoTime() - startedAt;
        sampler.shutdownNow();
        payExec.shutdownNow();

        assertThat(paymentsFinished && holdsFinished).as("측정이 시간 안에 끝나야 한다").isTrue();
        assertThat(seatsExhausted.get()).as("좌석 풀(%d)이 회차 중 소진되면 안 된다", SEAT_POOL).isFalse();
        assertThat(pays).as("결제 %d건이 모두 승인돼야 한다 (첫 실패: %s)", PAYMENTS, firstPayFailure.get())
                .hasSize(PAYMENTS);
        // 측정 중에도 정합성은 깨지지 않아야 한다 — 결제마다 정확히 한 번 적립
        for (PayTarget target : targets) {
            assertThat(bookerAccountRepository.findByUserId(target.userId()).orElseThrow().getBalance())
                    .isEqualTo(AMOUNT);
        }

        resetHoldSeats();

        double windowSec = windowNanos / 1e9;
        List<Long> total = holds.stream().map(HoldSample::totalNanos).toList();
        List<Long> acquire = holds.stream().map(HoldSample::acquireNanos).toList();
        List<Long> exec = holds.stream().map(h -> h.totalNanos() - h.acquireNanos()).toList();
        List<Long> payConnHeld = pays.stream().map(PaySample::connHeldNanos).toList();
        List<Long> payLatency = pays.stream().map(PaySample::latencyNanos).toList();

        HoldRun run = new HoldRun(arm, delayMs, holds.size(), holdFailures.get(), windowNanos,
                holds.size() / windowSec,
                pct(total, 50), pct(total, 95), pct(total, 99),
                pct(acquire, 50), pct(acquire, 95), pct(acquire, 99),
                pct(exec, 50), pct(exec, 95), pct(exec, 99),
                max(activeSamples), avg(activeSamples), max(waitingSamples), avg(waitingSamples),
                pct(payConnHeld, 50), pct(payConnHeld, 100),
                pays.stream().mapToInt(PaySample::connCount).max().orElse(0),
                pct(payLatency, 50));
        System.out.printf(Locale.ROOT, "  [run] %s %5dms holds=%d fail=%d window=%.0fms p99=%.1fms waitMax=%.0f%s%n",
                arm, delayMs, run.holds(), run.holdFailures(), windowNanos / 1e6, run.totalP99() / 1e6,
                run.waitingMax(), firstHoldFailure.get() == null ? "" : " firstFail=" + firstHoldFailure.get());
        return run;
    }

    private void confirm(Arm arm, PayTarget target) {
        if (arm == Arm.A) {
            inTxConfirmService.confirm(target.orderId(), target.paymentKey(), AMOUNT, target.userId());
        } else {
            confirmService.confirm(target.orderId(), target.paymentKey(), AMOUNT, target.userId());
        }
    }

    // ================================================================== 실험 2: 승인 직후 웹훅의 주문 행 락 대기

    @Test
    @DisplayName("승인 호출 100ms 뒤 같은 주문의 웹훅 — 주문 행 락 대기")
    void webhookLockWait() throws Exception {
        // 워밍업 — 비교군마다 1회 폐기
        runWebhookRace(Arm.A, 0);
        runWebhookRace(Arm.B, 0);

        List<WebhookRun> runs = new ArrayList<>();
        for (int delay : DELAYS_MS) {
            for (int rep = 0; rep < REPEATS; rep++) {
                for (Arm arm : Arm.values()) {
                    runs.add(runWebhookRace(arm, delay));
                }
            }
        }
        reportWebhook(runs);
    }

    private WebhookRun runWebhookRace(Arm arm, int delayMs) throws Exception {
        tossDelayMs = delayMs;
        PayTarget target = persistPaymentTargets(1).get(0);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch confirmStarted = new CountDownLatch(1);

        var confirmFuture = exec.submit(() -> {
            confirmStarted.countDown();
            try {
                confirm(arm, target);
            } catch (Exception e) {
                failure.compareAndSet(null, e);
            }
        });

        confirmStarted.await();
        Thread.sleep(WEBHOOK_OFFSET_MS);

        Probe[] webhookProbe = new Probe[1];
        long[] webhookLatency = new long[1];
        var webhookFuture = exec.submit(() -> {
            Probe probe = Probe.begin();
            long t0 = System.nanoTime();
            try {
                webhookService.handle(webhookPayload(target.orderId(), target.paymentKey()));
            } catch (Exception e) {
                failure.compareAndSet(null, e);
            } finally {
                webhookLatency[0] = System.nanoTime() - t0;
                webhookProbe[0] = probe;
                Probe.end();
            }
        });

        confirmFuture.get(1, TimeUnit.MINUTES);
        webhookFuture.get(1, TimeUnit.MINUTES);
        exec.shutdownNow();

        assertThat(failure.get()).as("승인·웹훅 모두 예외 없이 끝나야 한다").isNull();
        // 두 경로가 겹쳐도 적립은 정확히 한 번 (ADR-018 멱등)
        assertThat(bookerAccountRepository.findByUserId(target.userId()).orElseThrow().getBalance())
                .isEqualTo(AMOUNT);
        assertThat(pointTransactionRepository.findByTypeAndUserId(TransactionType.CHARGE, target.userId()))
                .hasSize(1);
        assertThat(paymentOrderRepository.findByOrderId(target.orderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.DONE);

        Probe probe = webhookProbe[0];
        assertThat(probe.orderLockCount).as("웹훅이 주문 행 락을 정확히 한 번 요청해야 한다").isEqualTo(1);
        WebhookRun run = new WebhookRun(arm, delayMs, probe.orderLockNanos, webhookLatency[0]);
        System.out.printf(Locale.ROOT, "  [webhook] %s %5dms lockWait=%.1fms webhookTotal=%.1fms%n",
                arm, delayMs, run.lockWaitNanos() / 1e6, run.webhookNanos() / 1e6);
        return run;
    }

    // ================================================================== 비교군 A

    /**
     * <b>비교군 A — 결제사 호출을 트랜잭션 안에 둔 승인.</b> 운영 코드에 없다(이 측정만을 위한 대조군).
     *
     * <p>순진하게 짜면 나오는 형태를 그대로 쓴다: 중복 처리를 막으려 주문 행을 <b>먼저</b> 잠그고,
     * 잠근 채로 결제사를 호출하고, 같은 트랜잭션에서 적립한다. 적립은 운영의 {@code settle}을 그대로
     * 호출해 같은 트랜잭션에 합류시킨다(REQUIRED) — B와 다른 점이 경계뿐이 되도록.
     *
     * <p>잠금을 결제사 호출 <b>뒤</b>로 미루는 변형도 가능하다. 그 경우 커넥션 점유(실험 1)는 같지만
     * 주문 행 락 보유 구간은 짧아져 실험 2의 대기도 줄어든다. 실험 2의 A 수치는 이 선택에 의존한다.
     */
    static class InTxConfirmService {

        private final PaymentOrderRepository paymentOrderRepository;
        private final PaymentSettlementService settlementService;
        private final TossPaymentClient tossClient;

        InTxConfirmService(PaymentOrderRepository paymentOrderRepository,
                           PaymentSettlementService settlementService,
                           TossPaymentClient tossClient) {
            this.paymentOrderRepository = paymentOrderRepository;
            this.settlementService = settlementService;
            this.tossClient = tossClient;
        }

        @Transactional
        public void confirm(String orderId, String paymentKey, int amount, Long userId) {
            PaymentOrder order = paymentOrderRepository.findByOrderIdForUpdate(orderId).orElseThrow();
            if (!order.getUser().getId().equals(userId) || order.getAmount() != amount) {
                throw new IllegalStateException("주문 검증 실패: " + orderId);
            }
            if (order.isSettled()) {
                return;
            }
            TossPaymentResult result = tossClient.confirm(paymentKey, orderId, amount);
            if (!result.isDone()) {
                throw new IllegalStateException("승인 상태 아님: " + result.status());
            }
            settlementService.settle(orderId, paymentKey);
        }
    }

    // ================================================================== 계측

    /**
     * 측정 대상 스레드에 꽂는 계측 버킷. {@link ProbingDataSource}가 같은 스레드에서 값을 더한다.
     * 트랜잭션의 커넥션 획득·반납은 호출 스레드에서 일어나므로 스레드 로컬로 귀속이 정확하다.
     */
    static final class Probe {
        private static final ThreadLocal<Probe> CURRENT = new ThreadLocal<>();

        long acquireNanos;
        long connHeldNanos;
        int connCount;
        long orderLockNanos;
        int orderLockCount;

        static Probe begin() {
            Probe probe = new Probe();
            CURRENT.set(probe);
            return probe;
        }

        static void end() {
            CURRENT.remove();
        }

        static Probe current() {
            return CURRENT.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        /** 자동 구성된 HikariDataSource를 계측 래퍼로 감싼다. 풀 자체는 그대로다. */
        @Bean
        static BeanPostProcessor probingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof HikariDataSource hikari ? new ProbingDataSource(hikari) : bean;
                }
            };
        }
    }

    static final class ProbingDataSource extends DelegatingDataSource {

        ProbingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            long t0 = System.nanoTime();
            Connection connection = super.getConnection();
            long acquiredAt = System.nanoTime();
            Probe probe = Probe.current();
            if (probe != null) {
                probe.acquireNanos += acquiredAt - t0;
            }
            return probingConnection(connection, acquiredAt);
        }

        private static Connection probingConnection(Connection target, long acquiredAt) {
            AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if ("close".equals(name) && closed.compareAndSet(false, true)) {
                            Probe probe = Probe.current();
                            if (probe != null) {
                                probe.connHeldNanos += System.nanoTime() - acquiredAt;
                                probe.connCount++;
                            }
                        }
                        Object result = invokeTarget(target, method, args);
                        if ("prepareStatement".equals(name) && args != null && args[0] instanceof String sql
                                && isOrderRowLock(sql)) {
                            return probingStatement((PreparedStatement) result);
                        }
                        return result;
                    });
        }

        /** 주문 행 비관적 락({@code findByOrderIdForUpdate})만 골라낸다. 계정 행 락은 제외. */
        private static boolean isOrderRowLock(String sql) {
            String lower = sql.toLowerCase(Locale.ROOT);
            return lower.contains("payment_orders") && lower.contains("for update");
        }

        private static PreparedStatement probingStatement(PreparedStatement target) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (!method.getName().startsWith("execute")) {
                            return invokeTarget(target, method, args);
                        }
                        long t0 = System.nanoTime();
                        try {
                            return invokeTarget(target, method, args);
                        } finally {
                            Probe probe = Probe.current();
                            if (probe != null) {
                                probe.orderLockNanos += System.nanoTime() - t0;
                                probe.orderLockCount++;
                            }
                        }
                    });
        }

        private static Object invokeTarget(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    // ================================================================== 리포트

    private record HoldSample(long totalNanos, long acquireNanos) {
    }

    private record PaySample(long latencyNanos, long connHeldNanos, int connCount) {
    }

    private record HoldRun(Arm arm, int delayMs, int holds, int holdFailures, long windowNanos, double throughput,
                           double totalP50, double totalP95, double totalP99,
                           double acquireP50, double acquireP95, double acquireP99,
                           double execP50, double execP95, double execP99,
                           double activeMax, double activeAvg, double waitingMax, double waitingAvg,
                           double payConnHeldP50, double payConnHeldMax, int payConnCount,
                           double payLatencyP50) {
    }

    private record WebhookRun(Arm arm, int delayMs, long lockWaitNanos, long webhookNanos) {
    }

    private void reportHold(List<HoldRun> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n=== 결제 승인 트랜잭션 경계 × 좌석 홀드 (칸마다 %d회 중앙값) ===%n", REPEATS));
        sb.append(conditions());
        sb.append(String.format(Locale.ROOT,
                "부하: 결제 승인 %d건 동시 + 좌석 홀드 스레드 %d개 연속 요청 / 측정 구간 = 결제 %d건 완료까지 / "
                        + "Hikari 샘플링 %dms%n", PAYMENTS, HOLD_THREADS, PAYMENTS, SAMPLE_INTERVAL_MS));

        sb.append(String.format("%n[1] 좌석 홀드 지연·처리량%n"));
        sb.append(String.format("%-11s %8s %8s %9s %9s %9s %9s %6s%n",
                "지연/비교군", "구간ms", "홀드수", "홀드/초", "p50", "p95", "p99", "실패"));
        forEachCell(runs, (label, g) -> sb.append(String.format(Locale.ROOT,
                "%-11s %8.0f %8.0f %9.1f %9s %9s %9s %6.0f%n", label,
                med(g, r -> r.windowNanos() / 1e6), med(g, r -> r.holds()), med(g, HoldRun::throughput),
                ms(g, HoldRun::totalP50), ms(g, HoldRun::totalP95), ms(g, HoldRun::totalP99),
                med(g, r -> r.holdFailures()))));

        sb.append(String.format("%n[2] 좌석 홀드 — 커넥션 획득 대기 vs 획득 후 실행%n"));
        sb.append(String.format("%-11s %9s %9s %9s | %9s %9s %9s%n",
                "지연/비교군", "대기p50", "대기p95", "대기p99", "실행p50", "실행p95", "실행p99"));
        forEachCell(runs, (label, g) -> sb.append(String.format(Locale.ROOT,
                "%-11s %9s %9s %9s | %9s %9s %9s%n", label,
                ms(g, HoldRun::acquireP50), ms(g, HoldRun::acquireP95), ms(g, HoldRun::acquireP99),
                ms(g, HoldRun::execP50), ms(g, HoldRun::execP95), ms(g, HoldRun::execP99))));

        sb.append(String.format("%n[3] Hikari 풀 상태 · 결제 1건당 커넥션(=트랜잭션) 보유%n"));
        sb.append(String.format("%-11s %8s %8s %8s %8s | %11s %11s %6s %10s%n",
                "지연/비교군", "활성max", "활성avg", "대기max", "대기avg",
                "보유합p50", "보유합max", "tx수", "결제p50"));
        forEachCell(runs, (label, g) -> sb.append(String.format(Locale.ROOT,
                "%-11s %8.0f %8.2f %8.0f %8.2f | %11s %11s %6.0f %10s%n", label,
                med(g, HoldRun::activeMax), med(g, HoldRun::activeAvg),
                med(g, HoldRun::waitingMax), med(g, HoldRun::waitingAvg),
                ms(g, HoldRun::payConnHeldP50), ms(g, HoldRun::payConnHeldMax),
                med(g, r -> r.payConnCount()), ms(g, HoldRun::payLatencyP50))));
        sb.append("  보유합 = 결제 1건이 쓴 모든 트랜잭션의 커넥션 획득~반납 합계, tx수 = 그 트랜잭션 개수(최대)\n");

        System.out.println(sb);
    }

    private void reportWebhook(List<WebhookRun> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n=== 승인 %dms 뒤 웹훅 — 주문 행 락 대기 (칸마다 %d회 중앙값) ===%n",
                WEBHOOK_OFFSET_MS, REPEATS));
        sb.append(conditions());
        sb.append("웹훅 재조회(getPayment)는 지연 0 — 승인 호출만 지연을 준다. 배경 부하 없음\n");
        sb.append(String.format("%-11s %12s %12s   %s%n", "지연/비교군", "락대기", "웹훅전체", "회차별 락대기"));
        for (int delay : DELAYS_MS) {
            for (Arm arm : Arm.values()) {
                List<WebhookRun> g = runs.stream().filter(r -> r.arm() == arm && r.delayMs() == delay).toList();
                String raw = String.join(", ", g.stream()
                        .map(r -> String.format(Locale.ROOT, "%.1f", r.lockWaitNanos() / 1e6)).toList());
                sb.append(String.format(Locale.ROOT, "%-11s %10.1fms %10.1fms   [%s]%n",
                        delay + "ms/" + arm,
                        medW(g, r -> r.lockWaitNanos() / 1e6), medW(g, r -> r.webhookNanos() / 1e6), raw));
            }
        }
        System.out.println(sb);
    }

    private String conditions() {
        long ramBytes = ((com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        return String.format(Locale.ROOT,
                "조건: CPU %s (논리 %d) / RAM %.1fGB / Hikari max=min=%d, connectionTimeout %dms / "
                        + "MySQL 8.0 Testcontainers(같은 PC) / 인프로세스(HTTP 제외) / 워밍업 비교군당 1회 폐기%n",
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "?"),
                Runtime.getRuntime().availableProcessors(), ramBytes / 1024.0 / 1024 / 1024,
                POOL_SIZE, dataSource instanceof DelegatingDataSource d
                        ? ((HikariDataSource) d.getTargetDataSource()).getConnectionTimeout() : -1);
    }

    private interface CellConsumer {
        void accept(String label, List<HoldRun> group);
    }

    private static void forEachCell(List<HoldRun> runs, CellConsumer consumer) {
        for (int delay : DELAYS_MS) {
            for (Arm arm : Arm.values()) {
                List<HoldRun> g = runs.stream().filter(r -> r.arm() == arm && r.delayMs() == delay).toList();
                if (!g.isEmpty()) {
                    consumer.accept(delay + "ms/" + arm, g);
                }
            }
        }
    }

    private static String ms(List<HoldRun> g, ToDoubleFunction<HoldRun> nanosField) {
        return String.format(Locale.ROOT, "%.1fms", med(g, nanosField) / 1e6);
    }

    private static double med(List<HoldRun> rows, ToDoubleFunction<HoldRun> field) {
        double[] sorted = rows.stream().mapToDouble(field).sorted().toArray();
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }

    private static double medW(List<WebhookRun> rows, ToDoubleFunction<WebhookRun> field) {
        double[] sorted = rows.stream().mapToDouble(field).sorted().toArray();
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }

    private static double pct(List<Long> samples, int p) {
        if (samples.isEmpty()) return 0;
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }

    private static double max(Queue<Integer> samples) {
        return samples.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static double avg(Queue<Integer> samples) {
        return samples.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    // ================================================================== 픽스처

    private record PayTarget(Long userId, String orderId, String paymentKey) {
    }

    /** 결제 사용자 — 홀드 사용자와 겹치지 않는 별도 사용자. 주문은 측정 전에 READY로 만들어 둔다. */
    private List<PayTarget> persistPaymentTargets(int count) {
        List<PayTarget> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = userRepository.save(User.builder()
                    .email("bench-payer-" + uniqueId() + "@test.com")
                    .passwordHash("hash")
                    .role(User.UserRole.BOOKER)
                    .build());
            bookerAccountRepository.save(BookerAccount.builder().user(user).build());
            String orderId = orderService.createOrder(user.getId(), AMOUNT).getOrderId();
            targets.add(new PayTarget(user.getId(), orderId, "pk_" + orderId));
        }
        return targets;
    }

    /**
     * 홀드 사용자 {@value #HOLD_THREADS}명과 좌석 {@value #SEAT_POOL}석. 한 번만 만들고 회차마다
     * 좌석을 AVAILABLE로 되돌려 재사용한다. 좌석마다 한 번만 잡으므로 홀드끼리 같은 행을 다투지 않고,
     * 결제(payment_orders·booker_accounts)와도 겹치는 행이 없다 — 남는 공유 자원은 커넥션 풀뿐이다.
     */
    private void persistHoldFixture() {
        User provider = userRepository.save(User.builder()
                .email("bench-hold-provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        Event event = eventRepository.save(Event.builder()
                .provider(provider)
                .title("트랜잭션 경계 측정 이벤트")
                .venue("벤치마크홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .build());
        holdEventId = event.getId();

        holdUserIds = new ArrayList<>();
        for (int i = 0; i < HOLD_THREADS; i++) {
            holdUserIds.add(userRepository.save(User.builder()
                    .email("bench-holder-" + uniqueId() + "@test.com")
                    .passwordHash("hash")
                    .role(User.UserRole.BOOKER)
                    .build()).getId());
        }

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < SEAT_POOL; i++) {
            rows.add(new Object[]{holdEventId, "H-" + i, 1_000, "AVAILABLE"});
        }
        jdbcTemplate.batchUpdate("insert into seats (event_id, label, price, status) values (?, ?, ?, ?)", rows);
        holdSeatIds = jdbcTemplate.queryForList(
                "select id from seats where event_id = ? order by id", Long.class, holdEventId);
        assertThat(holdSeatIds).hasSize(SEAT_POOL);
    }

    private void resetHoldSeats() {
        jdbcTemplate.update("update seats set status = 'AVAILABLE', held_by = null, held_until = null "
                + "where event_id = ? and status <> 'AVAILABLE'", holdEventId);
    }

    private TossWebhookPayload webhookPayload(String orderId, String paymentKey) {
        TossWebhookPayload payload = new TossWebhookPayload();
        TossWebhookPayload.Data data = new TossWebhookPayload.Data();
        setField(data, "paymentKey", paymentKey);
        setField(data, "orderId", orderId);
        setField(data, "status", "DONE");
        setField(payload, "data", data);
        return payload;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
