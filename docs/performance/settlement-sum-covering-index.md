# 정산 합계 조회의 커버링 인덱스 실측

> 측정일: 2026-11-12
> 측정 코드: `src/test/java/com/placeholder/benchmark/SettlementSumExplainBenchmark.java`

## 측정 대상

- 메서드: `PointTransactionRepository.sumSettlementByProviderId`
- Hibernate가 실제로 내보낸 SQL:

  ```sql
  select coalesce(sum(pt1_0.amount),0) from point_transactions pt1_0 where pt1_0.user_id=? and pt1_0.type='SETTLE'
  ```

  - 바인딩되는 값은 `user_id` 하나(BIGINT)뿐이다. `'SETTLE'`은 SQL에 박힌 문자열 리터럴이다.
- 테이블은 JPA `ddl-auto`로 생성된다. `type` 컬럼은 MySQL 네이티브 `enum('CHARGE','DEDUCT','REFUND','SETTLE')`로 만들어진다.
- 비교 대상 인덱스
  - `idx_pt_settlement_sum (user_id, type, created_at, amount)`
  - `idx_pt_user_id (user_id)`

## 환경

- MySQL 8.0.46 (Testcontainers `mysql:8.0`)
- `innodb_buffer_pool_size` 128MB
  - Testcontainers 기본값은 16MB라서, 측정 세션에서만 MySQL 기본값으로 바꿨다.
- 테이블 전체 1,987,740행, `ANALYZE TABLE` 실행 후 측정

  | 구성 | 행 수 |
  |---|---|
  | 대상 제공자 3명 (SETTLE) | 240 / 17,500 / 285,000 |
  | 다른 제공자 97명 (SETTLE) | 485,000 |
  | 예약자 20,000명 (CHARGE/DEDUCT/REFUND) | 1,200,000 |

  - 사용자들의 행이 PK 순서상 섞이도록 무작위 순서로 넣었다. 한 사용자의 행이 연속 블록이면 `idx_pt_user_id`로 본체를 찾아가는 비용이 순차 접근이 되어 비현실적으로 싸게 나온다.
  - 대상 규모는 `docs/plans/provider-api-index-benchmark.md`의 시나리오를 따랐다(240 = 기존 하네스 규모, 17,500 = 일반 사업자 5년, 285,000 = 인기 기획사 5년).
- 인덱스 크기

  | 인덱스 | 크기 |
  |---|---|
  | PRIMARY | 124.7 MB |
  | idx_pt_settlement_sum | 113.8 MB |
  | idx_pt_user_id | 65.6 MB |

## 결과

시간은 워밍업 1회 후 5회 중앙값이다(ms). 모든 칸에서 합계가 기대값과 일치했다.

| SETTLE 행 | 인덱스 | key | key_len | rows(추정) | filtered | Extra | 실제 읽은 행 | ANALYZE 시간 | 쿼리 시간 |
|---|---|---|---|---|---|---|---|---|---|
| 240 | 기본 | settlement_sum | 9 | 240 | 100 | Using where; **Using index** | 240 | 0.09 | 1.37 |
| 240 | 강제 settlement_sum | settlement_sum | 9 | 240 | 100 | Using where; **Using index** | 240 | 0.07 | 1.31 |
| 240 | 강제 user_id | user_id | 8 | 240 | 25 | Using where | 240 | 0.58 | 1.77 |
| 17,500 | 기본 | settlement_sum | 9 | 26,892 | 100 | Using where; **Using index** | 17,500 | 4.97 | 5.37 |
| 17,500 | 강제 settlement_sum | settlement_sum | 9 | 26,892 | 100 | Using where; **Using index** | 17,500 | 5.27 | 5.23 |
| 17,500 | 강제 user_id | user_id | 8 | 31,936 | 25 | Using where | 17,500 | 131.00 | 131.63 |
| 285,000 | 기본 | settlement_sum | 9 | 466,574 | 100 | Using where; **Using index** | 285,000 | 80.90 | 64.79 |
| 285,000 | 강제 settlement_sum | settlement_sum | 9 | 466,574 | 100 | Using where; **Using index** | 285,000 | 80.10 | 64.63 |
| 285,000 | 강제 user_id | user_id | 8 | 512,038 | 25 | Using where | 285,000 | 7,598 | 7,749 |

- 세 규모 모두 `type=ref`, `ref=const,const`(기본·강제 settlement_sum) / `ref=const`(강제 user_id)
- ANALYZE 시간은 EXPLAIN ANALYZE 최상위 노드의 실제 시간, 쿼리 시간은 같은 쿼리를 JDBC로 실행한 왕복 시간이다.

## 결론

1. **기본 실행은 세 규모 모두 커버링으로 동작한다.** 옵티마이저는 매번 `idx_pt_settlement_sum`을 골랐고 Extra에 `Using index`가 떴다. `key_len=9`는 `user_id`(BIGINT, 8바이트)와 `type`(enum, 1바이트) 두 등호 조건을 모두 인덱스로 처리했다는 뜻이다.
2. **`idx_pt_user_id`를 고르는 규모는 없었다.** 이 인덱스를 강제하면 행마다 `amount`를 읽으러 본체를 찾아가야 한다. 그 결과 17,500행에서 약 25배, 285,000행에서 약 100배 느려졌다(131ms, 7.6초).
3. **"테이블 본체 접근 없이 처리"라는 서술은 측정으로 확인됐다.** 근거: EXPLAIN에서 커버링(`Using index`) 확인. 같은 조회를 `user_id` 단일 인덱스로 강제하면 285,000건 기준 65ms → 7.6초.

## 해석 시 주의

- **285,000행의 7.6초는 디스크 I/O 영향이 크다.** PK가 124.7MB라 128MB 버퍼 풀에 다 들어가지 않는다. 버퍼 풀이 더 크면 차이는 줄어든다. 다만 커버링 여부라는 결론은 버퍼 풀 크기와 무관하다.
- **Extra의 `Using where`는 본체 접근을 뜻하지 않는다.** `Using index`와 함께 뜨면 조건 검사를 인덱스 안에서 한다는 의미다.
- **rows는 옵티마이저의 추정치다.** 17,500행에서 26,892, 285,000행에서 466,574로 실제보다 크게 잡혔다. 실제 읽은 행 수는 EXPLAIN ANALYZE 값이다.
- **제공자의 행은 전부 SETTLE로 넣었다(도메인과 같음).** 그래서 이 측정에서 `type` 조건은 대상 행을 줄이지 않았다. 두 인덱스의 차이는 행을 좁히는 능력이 아니라 본체를 읽느냐 아니냐다.
- **측정은 1회 실행이다.** 시간은 그 실행 안에서 5회 반복한 중앙값이고, 버퍼 풀이 데워진(warm) 상태의 값이다.

## 재현

```bash
./mvnw.cmd test -Dtest=SettlementSumExplainBenchmark -DfailIfNoSpecifiedTests=false
```

- Docker가 필요하다. 실행에 약 7분 걸린다(시드 280초 포함).
- 클래스명이 `...Benchmark`라 surefire 기본 패턴에 걸리지 않아 CI에서 자동 실행되지 않는다.
- 결과는 stdout의 `[EXPLAIN-BENCH]` 접두 줄에 찍힌다(SHOW CREATE TABLE, SHOW INDEX, 실제 SQL·바인딩, 칸별 EXPLAIN / EXPLAIN ANALYZE 트리, 결과 표).
