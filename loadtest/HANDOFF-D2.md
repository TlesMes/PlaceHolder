# Phase D-2 인수인계 — hold knee point 측정 (종료)

> 작성: 2026-06-10 / 갱신: 2026-06-22 / 브랜치: `feature/loadtest-hold-confirm-knee`
> **D-2 측정 종료(2026-06-22). 결과·결론은 아래 「측정 결과 및 결론」 참조.** confirm은 미실행(사유는 결론부).

## ✅ 결정됨 (2026-06-21) — abort/knee 기준 = 성공 지연 p99 (A안)

1차 측정에서 "포화 신호가 5xx가 아니라 4xx+dropped"라 5xx abort가 안 걸린 문제 → **A안 채택**으로 종결.
스크립트에 반영 완료:

- **abort 기준 변경:** `hold.js`/`confirm.js`의 주 threshold를 `*_error`(5xx) count → **성공 지연 `*_duration` p99 < `P99_ABORT_MS`(기본 2000ms), abortOnFail**로 교체. `*_error`(5xx) abort는 보조 안전망으로 유지. `delayAbortEval: '10s'`로 cold-start 오발동 방지.
- **ramp 재스케일:** 1차 천장(~356/s)을 한 칸에 건너뛰던 50→5000(분수 스텝)을 폐기. hold=50~600/s, confirm=30~300/s를 **절대 스텝으로 촘촘히**. (knee는 p99 abort가 멈춤.)
- **HikariCP 풀 명시 고정:** `application-loadtest.yml`에 `maximum-pool-size: 10`(기본값과 동일, 1차와 같은 조건) + `com.zaxxer.hikari: DEBUG`. measured knee = "10커넥션 풀의 knee"임을 문서로 박음.

## 측정 결과 및 결론 (2026-06-22, D-2 종료)

### 측정 환경
- CPU: AMD Ryzen 5 5600 (6C/12T), RAM 32GB / MySQL 8.0.46 (Docker) / k6 v2.0.0 (Windows)
- **app + MySQL + k6가 단일 박스 공유** / 풀 크기는 `HIKARI_POOL` env로 가변(기본 10)

### 여정 요약
1. **2차 hold(좌석 2만):** 성공 19,262 ≈ 좌석 풀 → **재고 소진 오염**(거절 70%). 무효.
2. **3차 hold(좌석 7.5만, 375이벤트×200좌석):** 재고 소진 제거. knee에서 p99 abort 정상 발동. 단 **5xx=0** — 에러로 안 터지고 지연으로 degrade.
3. **풀 스윕 5/10/20(+CPU):** 병목 식별.

### 풀 스윕 (knee = 성공 p99>1s 도달 시 주입률)
| 풀 | knee | p99 | knee 시 CPU | Hikari |
|----|------|-----|------|--------|
| 5  | ~390/s | 1.17s | **~65% (여유 있음)** | active=5 만석, waiting 195 |
| 10 | ~840/s | 1.04s | 미측정(~90% 추정) | active=10 만석, waiting 190 |
| 20 | ~890/s | 1.16s | 미측정(~100% 추정) | active=20 만석, waiting 181 |

- **5→10: knee 2.2배(풀-바운드) / 10→20: 1.06배(플래토).**
- 풀 5는 CPU 65%(여유)인데 꺾임 = **커넥션 부족(풀-한계).** 풀 10부터 **박스 자원(CPU)**이 한계.

### 결론
1. **적정 풀 ≈ 10**(HikariCP 기본값). 아래는 풀 부족, 위는 무의미(지연만 악화). → **튜닝 불필요, 기본값 검증됨.**
2. **5xx 없이 지연으로 graceful degrade.** 한계는 **단일 박스 CPU(~880/s)**지 동시성 로직이 아님.
3. **대기열(E-1) 함의:** 도입 시 = 크래시 방지 아니라 **지연 SLO 보호**용 (ADR "대기열=트래픽 보호"와 일치).

### 정직한 한계 (재현·해석 시 필독)
- **분산(spread) 설계 = 인프라 처리량 측정**이지 "경합 하 락 설계" 측정이 아님. 경합 정확성은 JUnit C-4가 이미 증명. → 락 설계 자체의 부하 거동은 **핫좌석 경합 시나리오**가 별도 필요(미수행, 백로그).
- 절대 수치(~880/s)는 단일 박스 공유 + k6 오버헤드 포함 → **이식 불가, 곡선 형태만 유효.**
- CPU는 풀 5만 샘플링(10/20은 추론).
- **confirm.js 미실행:** hold가 "한계는 인프라/CPU지 로직 아님"으로 결론나, confirm도 동일 양상 예상 → 추가 측정 가치 낮다고 판단해 보류.

### D-2 종합 (D-1 대비)
- **D-1(N+1 해결)이 측정의 실질 성과**(쿼리 1+2N→2, 곡선 개선 증명).
- **D-2는 보조 결론** — "한계는 인프라/CPU지 동시성 로직이 아니다 + graceful degrade." 측정이 가설을 3회 교정한 과정 자체가 방법론 산출물.

## 지금까지 한 것 (커밋 완료, push 됨)

```
d6f39a2 test: 부하 종료를 에러 발생 시 자동 중단(abortOnFail)으로 변경
be132a0 test: hold/confirm knee point 부하 스크립트 추가 (Phase D-2)
05b1970 test: k6 setup에 좌석 ID 풀 수집 + booker 잔액 시드
67435a1 feat: 부하 측정용 쿠폰 생성 엔드포인트 (loadtest 프로파일 전용)
```

- **쿠폰 생성 API** (`POST /api/loadtest/coupons`, `@Profile("loadtest")` 전용) — confirm 부하용 booker 잔액 시드 수단. 운영엔 빈이 없어 404.
- **k6 시드 헬퍼** (`lib/setup.js`): `seedForSeatLoad`(좌석 ID 풀 + 잔액충전), `seedForConfirmLoad`(사전 hold + holder 토큰 매핑)
- **hold.js / confirm.js**: `ramping-arrival-rate` open-loop ramp, 성공/거절/에러 분리 집계, `*_error`(5xx/timeout)에 `abortOnFail`

## 측정 환경

- CPU/RAM: (이 디바이스 — 이어받는 곳과 다를 수 있음. 절대 RPS 비교 금지, 곡선 형태만)
- MySQL: 8.0 (Docker, placeholder-mysql, 3306)
- 앱: `local,loadtest` 프로파일 (`local`=DB 자격증명, `loadtest`=쿼리통계+쿠폰컨트롤러)
- 기동: `./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,loadtest"`
- 측정일: 2026-06-10

## 1차 측정 결과 (hold.js, EVENT_COUNT=400 → 2만 좌석, BOOKER_COUNT=50)

| 지표 | 값 |
|---|---|
| hold_success | 19,621 (85.8/s) |
| **hold_rejected (4xx)** | **61,831 (270/s) — 전체의 75%** |
| hold_error (5xx/timeout) | **0** |
| hold_duration p95 | 6.35s |
| http_req_duration p95 | 5.96s |
| dropped_iterations | **235,781** |
| abort 발동 여부 | **❌ 안 걸림** |

### 이 결과가 드러낸 것 (사실, 해석은 인간 몫)

1. **abort가 안 걸린 이유 = 5xx가 0.** 이 시스템은 포화 시 5xx로 죽지 않고 **4xx 거절 + dropped_iterations**로 버틴다. 즉 `*_error`(5xx) 기준 abortOnFail이 hold 경로엔 발동하지 않는다.
2. **거절 75%.** 좌석 2만 개로도 부족했다. hold가 좌석을 소비(AVAILABLE→HELD)하므로 시간이 갈수록 AVAILABLE이 줄어 "이미 점유됨" 거절이 누적. → 분산 모드여도 풀이 부하 대비 작으면 측정이 "락 천장"이 아니라 "재고 소진"이 된다.
3. **dropped 23만.** 도착률 5000을 시스템이 못 받아 k6가 주입 포기. 이게 사실상의 포화 신호(주입 ≫ 달성).

## 결정 종결 (위 ✅ 참고) — (A) 성공 지연 p99 채택

위 1차 결과를 근거로 **(A) 성공 지연 p99 abort**를 채택했다(지연 폭발=knee, 이 시스템에 맞는 신호).
(B) 거절 제거안은 검증 단계의 보조 조건으로 흡수 — 천장 *이전* 구간에서 `*_rejected`가 낮아야 측정 유효(아니면 재고 소진 측정 → EVENT_COUNT↑ 재측정).

> 참고: confirm.js는 이번이 **첫 실행.** confirm은 좌석 1회용이라 좌석 고갈(`seat_exhausted`) 관리가 더 빡빡함 — 사전 hold 좌석 풀 > 총 confirm 시도, TTL 5분 내 종료.

## 재개 절차 (2차 측정)

```powershell
# 1. Docker Desktop 켜고 MySQL 기동
docker compose up -d
# 2. 앱 기동 (local=자격증명, loadtest=측정설정+풀10고정+쿠폰컨트롤러)
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,loadtest"
# 3. hold 2차 측정 (성공 p99 abort, ramp 50~600)
k6 run -e EVENT_COUNT=400 -e BOOKER_COUNT=50 -e P99_ABORT_MS=2000 loadtest/hold.js
# 4. confirm 첫 측정 (좌석 풀 > 총 confirm, 짧게)
k6 run -e EVENT_COUNT=200 -e BOOKER_COUNT=50 -e P99_ABORT_MS=2000 loadtest/confirm.js
```

- 각 측정 후 앱 재시작(ddl-auto: create → DB 초기화 → setup 자급자족 시드).
- `P99_ABORT_MS`는 1~2s 사이에서 조정 가능(기본 2000). 절대 RPS 비교 금지 — 곡선 형태(천장·p99 꺾임)만.
- DB 자격증명: `application-local.yml` (gitignore됨 — 디바이스마다 따로 있어야 함). 없으면 `spring.datasource.username/password` 또는 `DB_USERNAME/DB_PASSWORD` 환경변수 설정.

### 결과 기록 (knee 해석은 인간 몫)

| 도착률(target/s) | 달성 성공/s | 성공 p99(ms) | 거절/s | 5xx | 비고 |
|---|---|---|---|---|---|
| 50 | | | | | |
| … | | | | | |

- knee = 성공 처리량이 평탄해지고 성공 p99가 임계에서 abort된 직전 도착률.
- 측정 환경(CPU/RAM/MySQL/날짜) 병기 필수.

## 협업 경계 리마인드 (CLAUDE.md)
- 스크립트 작성·측정 실행 = Claude / **수치 해석·knee 판단·ADR 결론 = 인간**
- 측정 결과 기록 시 반드시 측정 환경(CPU/RAM/MySQL/위치) 병기 — 절대값 아닌 곡선·상대 비교가 핵심
