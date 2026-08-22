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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>ADR-021 이후.</b> 확정 경로가 제공자 계정을 더 이상 잠그지도 갱신하지도 않으므로
 * 락 대기·보유 계측과 "락 제거 상한" 비교군은 잴 대상이 사라져 제거했다. 남은 질문은 하나다 —
 * <b>P가 처리량에 영향을 주지 않게 됐는가.</b> 재설계 전에는 P=1이 초당 88건, P=8이 352건으로
 * 3.99배 벌어졌다. 파생 후에는 공유하는 행이 없으므로 그 차이가 사라져야 한다.
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
    /**
     * 측정 조합. 예전에는 제공자 수 하나만 바꿨는데, 픽스처가 제공자 1명당 이벤트 1개를 만들어서
     * <b>제공자 수와 이벤트 수가 붙어 움직였다</b> — 몰려서 느린 것이 제공자 때문인지 이벤트
     * 때문인지 구분할 수 없었다.
     *
     * <p>이벤트는 제공자가 한 명이므로 한 이벤트의 좌석은 반드시 한 제공자 것이다. 따라서
     * "제공자 8명 · 이벤트 1개"는 만들 수 없고, 아래 세 칸이 가능한 전부다.
     * {@code (1,1)}과 {@code (1,8)}이 같으면 원인은 제공자 쪽, {@code (1,8)}이 {@code (8,8)}만큼
     * 빨라지면 원인은 이벤트 쪽이다.
     */
    private static final Cell[] CELLS = {
            new Cell(1, 1),   // 제공자도 이벤트도 몰림 (기존 P=1)
            new Cell(1, 8),   // 제공자만 몰고 이벤트는 흩음
            new Cell(8, 8),   // 둘 다 흩음 (기존 P=8)
    };
    private static final int REPEATS = 5;
    private static final int PRICE = 5_000;

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ProviderAccountRepository providerAccountRepository;

    private final Queue<Long> confirmNanos = new ConcurrentLinkedQueue<>();

    // ------------------------------------------------------------------ 측정

    @Test
    @DisplayName("제공자 수 × 이벤트 수 대조 — 몰려서 느린 것이 어느 쪽 때문인가")
    void providerAndEventSweep() throws InterruptedException {
        List<Row> rows = new ArrayList<>();

        // 워밍업 1회 폐기 — JIT·커넥션 풀 채우기·Hibernate 초회 비용이 첫 회차에 몰린다
        runOnce(CELLS[0]);

        for (Cell cell : CELLS) {
            for (int rep = 0; rep < REPEATS; rep++) {
                rows.add(runOnce(cell));
            }
        }

        report("확정 처리량 (ADR-021 파생 이후)", rows);
    }

    // ------------------------------------------------------------------ 실행

    private Row runOnce(Cell cell) throws InterruptedException {
        Fixture f = persistFixture(cell);
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

        // 측정 중에도 정합성은 깨지지 않아야 한다. 잔액은 이제 파생값이라
        // "확정한 만큼 SETTLE 원장이 쌓였는가"를 묻는다 (ADR-021)
        for (Long providerId : f.providerIds) {
            long settled = pointTransactionRepository.sumSettlementByProviderId(providerId);
            long expected = (long) pointTransactionRepository
                    .findByTypeAndUserId(TransactionType.SETTLE, providerId).size() * PRICE;
            assertThat(settled)
                    .as("제공자 %d — 정산 합이 SETTLE 이력과 일치해야 한다", providerId)
                    .isEqualTo(expected);
        }

        double seconds = wallNanos / 1_000_000_000.0;
        return new Row(cell, TOTAL_CONFIRMS / seconds,
                percentile(confirmNanos, 50), percentile(confirmNanos, 95), percentile(confirmNanos, 99));
    }

    // ------------------------------------------------------------------ 리포트

    /** 제공자 수 × 이벤트 수 한 칸. 이벤트 수는 제공자 수의 배수여야 균등 분배가 된다. */
    private record Cell(int providers, int events) {
        String label() {
            return "P=" + providers + " E=" + events;
        }
    }

    private record Row(Cell cell, double throughput,
                       double confirmP50, double confirmP95, double confirmP99) {
    }

    private void report(String arm, List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n=== 제공자 정산 처리량 측정 — %s ===%n", arm));
        sb.append(String.format("조건: 총 확정 %d건 / 스레드 %d / Hikari 48 / 칸당 %d회 중앙값 / 워밍업 1회 폐기%n",
                TOTAL_CONFIRMS, THREADS, REPEATS));
        sb.append(String.format("%-10s %10s %10s %10s %10s%n",
                "칸", "확정/초", "지연p50", "지연p95", "지연p99"));

        for (Cell cell : CELLS) {
            List<Row> group = rows.stream().filter(r -> r.cell().equals(cell)).toList();
            if (group.isEmpty()) continue;
            sb.append(String.format("%-10s %10.1f %10s %10s %10s%n",
                    cell.label(), median(group, Row::throughput),
                    ms(group, Row::confirmP50), ms(group, Row::confirmP95), ms(group, Row::confirmP99)));
        }

        double baseline = median(rows.stream().filter(r -> r.cell().equals(CELLS[0])).toList(),
                Row::throughput);
        sb.append(System.lineSeparator()).append(CELLS[0].label()).append(" 대비 배수: ");
        for (Cell cell : CELLS) {
            List<Row> group = rows.stream().filter(r -> r.cell().equals(cell)).toList();
            if (group.isEmpty()) continue;
            sb.append(String.format("%s → %.2fx   ", cell.label(), median(group, Row::throughput) / baseline));
        }

        // 읽는 법: (1,1)과 (1,8)이 같으면 몰려서 느린 원인은 제공자 쪽,
        //          (1,8)이 (8,8)만큼 빨라지면 원인은 이벤트 쪽이다.
        sb.append(String.format("%n(파생 전: P=1 88.1건/초, P=8 351.9건/초 — 그때는 제공자·이벤트가 붙어 있었다)%n"));

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
     * 제공자 {@code cell.providers()}명, 이벤트 {@code cell.events()}개를 만들고 좌석을 이벤트에
     * 균등 분배한다. 이벤트는 제공자에게 라운드로빈으로 배정되므로 두 수를 <b>따로</b> 움직일 수
     * 있다 — 이전 픽스처는 제공자 1명당 이벤트 1개로 고정돼 둘을 구분할 수 없었다.
     *
     * <p>좌석마다 예약자를 따로 두는 이유는 예약자 계정 락으로 요청들이 직렬화되면 대조 변수가
     * 오염되기 때문이다.
     *
     * <p>단일 트랜잭션으로 묶는다 — 건별 저장은 커밋이 수백 회 일어나 픽스처 구축이 측정보다
     * 오래 걸린다.
     */
    private Fixture persistFixture(Cell cell) {
        return transactionTemplate.execute(status -> {
            List<Long> providerIds = new ArrayList<>();
            List<User> providers = new ArrayList<>();
            for (int i = 0; i < cell.providers(); i++) {
                User provider = userRepository.save(User.builder()
                        .email("bench-provider-" + uniqueId() + "@test.com")
                        .passwordHash("hash")
                        .role(User.UserRole.PROVIDER)
                        .build());
                providerAccountRepository.save(ProviderAccount.builder().user(provider).build());
                providers.add(provider);
                providerIds.add(provider.getId());
            }

            // 이벤트를 제공자에게 라운드로빈 배정 — 이 분리가 이번 측정의 요점이다
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < cell.events(); i++) {
                events.add(eventRepository.save(Event.builder()
                        .provider(providers.get(i % cell.providers()))
                        .title("정산 처리량 측정 이벤트")
                        .venue("벤치마크홀")
                        .eventAt(LocalDateTime.now().plusDays(1))
                        .build()));
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
                        .event(events.get(i % cell.events()))
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
