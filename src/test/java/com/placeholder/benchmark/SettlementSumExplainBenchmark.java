package com.placeholder.benchmark;

import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>정산 합계 조회의 실행 계획 — 측정 하네스.</b>
 *
 * <p>{@code sumSettlementByProviderId}가 {@code idx_pt_settlement_sum}을 커버링으로 쓰는지
 * 실제 MySQL에서 EXPLAIN / EXPLAIN ANALYZE로 확인한다. 테이블은 운영과 같은 경로(JPA ddl-auto)로
 * 만들어지고, 대상 SQL은 Hibernate가 실제로 내보낸 문장을 로그에서 잡아 그대로 쓴다.
 *
 * <p>클래스명이 {@code ...Benchmark}라 CI에서 돌지 않는다. 실행:
 * <pre>{@code ./mvnw.cmd test -Dtest=SettlementSumExplainBenchmark -DfailIfNoSpecifiedTests=false}</pre>
 *
 * <p>시드: 대상 제공자 3명(SETTLE 240 / 17,500 / 285,000) + 다른 제공자 97명 × 5,000 SETTLE
 * + 예약자 20,000명 × 60건(CHARGE/DEDUCT/REFUND). 총 약 199만 행. 실제 누적처럼 사용자들의
 * 행이 PK 순서상 섞이도록 무작위 순서로 넣는다(한 사용자 행이 연속 블록이면 user_id 인덱스 →
 * 본체 조회가 순차 접근이 되어 비현실적으로 유리해진다).
 */
@SuppressWarnings({"null", "SqlSourceToSinkFlow"})
@SpringBootTest(properties = {
        "spring.jpa.show-sql=true",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SettlementSumExplainBenchmark extends MySQLIntegrationTest {

    private static final long[] TARGETS = {1_000_001L, 1_000_002L, 1_000_003L};
    private static final int[] TARGET_ROWS = {240, 17_500, 285_000};
    private static final int RUNS = 5;

    @Autowired PointTransactionRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("정산 합계 조회 EXPLAIN — 규모 × 인덱스")
    void explain(CapturedOutput output) throws Exception {
        // Testcontainers MySQL 이미지 설정은 버퍼 풀이 16MB다. 그대로 두면 디스크 I/O가 결과를 지배하므로
        // MySQL 기본값(128MB, docker-compose 개발 DB와 동일)으로 온라인 리사이즈한다
        // 테스트 계정엔 SYSTEM_VARIABLES_ADMIN이 없어 root로 연결한다 (Testcontainers 기본 root 비밀번호 = "test")
        String url;
        try (Connection c = dataSource.getConnection()) { url = c.getMetaData().getURL(); }
        try (Connection root = java.sql.DriverManager.getConnection(url, "root", "test"); Statement s = root.createStatement()) {
            s.execute("set global innodb_buffer_pool_size = 134217728");
        }
        for (int i = 0; i < 50 && jdbc.queryForObject("select @@innodb_buffer_pool_size", Long.class) < 134217728L; i++) {
            Thread.sleep(200);
        }
        out("MySQL " + jdbc.queryForObject("select version()", String.class)
                + " / innodb_buffer_pool_size=" + jdbc.queryForObject("select @@innodb_buffer_pool_size", Long.class));

        // 1. 테이블이 실제로 어떻게 만들어졌는가
        out("\n=== SHOW CREATE TABLE ===");
        out((String) jdbc.queryForMap("show create table point_transactions").get("Create Table"));
        out("\n=== SHOW INDEX ===");
        jdbc.queryForList("show index from point_transactions").forEach(r -> out(String.format(
                "%-24s seq=%s col=%-14s card=%s", r.get("Key_name"), r.get("Seq_in_index"),
                r.get("Column_name"), r.get("Cardinality"))));

        // 2. Hibernate가 실제로 내보내는 SQL
        repository.sumSettlementByProviderId(TARGETS[0]);
        String sql = captureSql(output.getOut());
        out("\n=== 실제 SQL (Hibernate) ===\n" + sql);
        output.getOut().lines().filter(l -> l.contains("binding parameter") && l.contains("[" + TARGETS[0] + "]"))
                .reduce((a, b) -> b)
                .ifPresent(l -> out("바인딩: " + l.substring(l.indexOf("binding parameter"))));

        // 3. 시드
        long t0 = System.currentTimeMillis();
        seed();
        out(String.format("%n시드 완료 %,d행 (%.1fs)", jdbc.queryForObject(
                "select count(*) from point_transactions", Long.class), (System.currentTimeMillis() - t0) / 1000.0));
        jdbc.queryForList("analyze table point_transactions");
        out("\n=== 인덱스 크기 (ANALYZE 후) ===");
        // mysql.innodb_index_stats는 테스트 계정이 읽을 수 없어 root로 조회한다
        try (Connection root = java.sql.DriverManager.getConnection(url, "root", "test"); Statement s = root.createStatement();
             java.sql.ResultSet rs = s.executeQuery("select index_name, stat_value * @@innodb_page_size / 1024 / 1024 as mb "
                     + "from mysql.innodb_index_stats where database_name = 'test' "
                     + "and table_name = 'point_transactions' and stat_name = 'size'")) {
            while (rs.next()) out(String.format("%-24s %.1f MB", rs.getString(1), rs.getDouble(2)));
        }
        out("\n=== SHOW INDEX (ANALYZE 후) ===");
        jdbc.queryForList("show index from point_transactions").forEach(r -> out(String.format(
                "%-24s seq=%s col=%-14s card=%s", r.get("Key_name"), r.get("Seq_in_index"),
                r.get("Column_name"), r.get("Cardinality"))));

        // 4. 규모 × 인덱스
        String[] variants = {null, "idx_pt_settlement_sum", "idx_pt_user_id"};
        List<String> table = new ArrayList<>();
        table.add("| SETTLE 행 | 인덱스 | key | key_len | rows | filtered | Extra | cost | EXPLAIN ANALYZE 실제 행 | ANALYZE 시간(ms, 중앙값) | 쿼리 시간(ms, 중앙값) |");
        table.add("|---|---|---|---|---|---|---|---|---|---|---|");
        for (int i = 0; i < TARGETS.length; i++) {
            String bound = bind(sql, TARGETS[i]);
            long expected = (long) TARGET_ROWS[i] * 1000;
            for (String v : variants) {
                String q = v == null ? bound : force(bound, v);
                String label = v == null ? "기본" : "FORCE " + v;
                out(String.format("%n===== SETTLE %,d / %s =====%n%s", TARGET_ROWS[i], label, q));

                Map<String, Object> e = jdbc.queryForList("explain " + q).get(0);
                out("EXPLAIN: " + e);
                String json = jdbc.queryForObject("explain format=json " + q, String.class);
                String cost = match(json, "\"query_cost\":\\s*\"([0-9.]+)\"");

                // 워밍업 1회 후 RUNS회 — 버퍼 풀이 데워진 상태(warm)를 잰다
                Long sum = jdbc.queryForObject(q, Long.class);
                if (sum == null || sum != expected) throw new IllegalStateException("합계 불일치: " + sum + " != " + expected);
                double[] analyzeMs = new double[RUNS];
                double[] queryMs = new double[RUNS];
                String tree = null;
                for (int r = 0; r < RUNS; r++) {
                    tree = jdbc.queryForObject("explain analyze " + q, String.class);
                    analyzeMs[r] = topActualTime(tree);
                    long s = System.nanoTime();
                    jdbc.queryForObject(q, Long.class);
                    queryMs[r] = (System.nanoTime() - s) / 1e6;
                }
                out("EXPLAIN ANALYZE:\n" + tree);
                table.add(String.format("| %,d | %s | %s | %s | %s | %s | %s | %s | %s | %.2f | %.2f |",
                        TARGET_ROWS[i], label, e.get("key"), e.get("key_len"), e.get("rows"), e.get("filtered"),
                        e.get("Extra"), cost, leafRows(tree), median(analyzeMs), median(queryMs)));
            }
        }
        out("\n=== 결과 표 ===");
        table.forEach(this::out);
    }

    // ---- 시드 ----

    private void seed() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // 사용자 행은 만들지 않는다 — 실행 계획은 point_transactions의 통계만 본다
            s.execute("set foreign_key_checks = 0");
            // MySQL은 임시 테이블을 한 쿼리에서 두 번 열 수 없어(self-join 불가) 일반 테이블을 쓰고 끝에 지운다
            s.execute("create table bench_digits (d int)");
            s.execute("insert into bench_digits values (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)");
            s.execute("create table seq (n int primary key)");
            s.execute("insert into seq select 1 + a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000 "
                    + "from bench_digits a, bench_digits b, bench_digits c, bench_digits d, bench_digits e, bench_digits f"); // 1..1,000,000
            s.execute("create table seq2 as select n + 1000000 as n from seq where n <= 300000");
            s.execute("insert into seq select n from seq2");                                       // ..1,300,000
            s.execute("create table stg (user_id bigint, type varchar(10), amount int)");
            for (int i = 0; i < TARGETS.length; i++) {
                s.execute("insert into stg select " + TARGETS[i] + ", 'SETTLE', 1000 from seq where n <= " + TARGET_ROWS[i]);
            }
            s.execute("insert into stg select 1000100 + (n % 97), 'SETTLE', 1000 from seq where n <= 485000");
            s.execute("insert into stg select 2000000 + (n % 20000), elt(1 + (n % 3), 'CHARGE', 'DEDUCT', 'REFUND'), 1000 "
                    + "from seq where n <= 1200000");
            // 무작위 순서로 넣어 사용자들의 행이 PK상 섞이게 한다. created_at은 PK와 같은 방향으로 증가
            s.execute("insert into point_transactions "
                    + "(user_id, type, amount, bucket_event, bucket_free, bucket_paid, reservation_id, created_at) "
                    + "select user_id, type, amount, 0, 0, if(type = 'SETTLE', 0, amount), null, "
                    + "timestamp('2021-01-01') + interval (row_number() over (order by r)) * 60 second "
                    + "from (select stg.*, rand(42) r from stg) t order by r");
            s.execute("set foreign_key_checks = 1");
            s.execute("drop table bench_digits, seq, seq2, stg");
        }
    }

    // ---- SQL 가공 ----

    private static String captureSql(String log) {
        return log.lines().filter(l -> l.startsWith("Hibernate:") && l.contains("point_transactions") && l.contains("sum("))
                .reduce((a, b) -> b).map(l -> l.substring("Hibernate:".length()).trim())
                .orElseThrow(() -> new IllegalStateException("Hibernate SQL 로그를 찾지 못했다"));
    }

    private static String bind(String sql, long id) {
        if (sql.chars().filter(ch -> ch == '?').count() != 1) throw new IllegalStateException("바인딩 자리가 1개가 아님: " + sql);
        return sql.replace("?", Long.toString(id));
    }

    private static String force(String sql, String index) {
        return sql.replaceFirst("point_transactions (\\w+)", "point_transactions $1 force index (" + index + ")");
    }

    // ---- EXPLAIN ANALYZE 파싱 ----

    /** 트리 첫 줄(최상위 노드)의 actual time=a..b 중 b(마지막 행까지의 시간) */
    private static double topActualTime(String tree) {
        return Double.parseDouble(match(tree.lines().findFirst().orElse(""), "actual time=[0-9.]+\\.\\.([0-9.]+)"));
    }

    /** 가장 깊은 노드(인덱스 접근)의 실제 반환 행 수 */
    private static String leafRows(String tree) {
        List<String> lines = tree.lines().toList();
        return match(lines.get(lines.size() - 1), "actual time=[^)]*rows=([0-9.e+]+)");
    }

    private static String match(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : "?";
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private void out(String s) {
        System.out.println("[EXPLAIN-BENCH] " + s.replace("\n", "\n[EXPLAIN-BENCH] "));
    }
}
