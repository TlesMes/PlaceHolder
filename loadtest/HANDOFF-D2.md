# Phase D-2 인수인계 — hold/confirm knee point 측정 (진행 중)

> 작성: 2026-06-10 / 브랜치: `feature/loadtest-hold-confirm-knee`
> 다른 디바이스에서 이어가기 위한 맥락 정리. **측정 1차 완료, 설계 재조정 결정 대기 중.**

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

## ⛔ 다음 디바이스에서 결정할 것 (작업 중단 지점)

**1차 측정 결과, hold 경로의 포화 신호가 5xx가 아니라서 abort/knee 기준 재정의가 필요하다.** 아래 중 택1 후 재측정:

- **(A) 성공 지연 p99 기준 abort** — 5xx 대신 `hold_duration` p99가 임계(예 1~2s) 넘으면 중단. 지연 폭발=knee. 이 시스템엔 이게 맞는 신호로 보임.
- **(B) 거절 없게 재설계 후 측정** — 좌석 풀을 훨씬 키우거나 ramp를 천천히. 거절 없는 구간에서 순수 처리량 곡선 재측정.
- (대화에서 여기까지 논의함. 질문에 답하다 중단.)

> 참고: confirm.js는 **아직 한 번도 안 돌림.** confirm은 좌석 1회용이라 좌석 고갈(`seat_exhausted`) 관리가 더 빡빡함 — hold 기준 정리된 뒤 동일 패턴으로 측정.

## 재개 절차

```powershell
# 1. Docker Desktop 켜고 MySQL 기동
docker compose up -d
# 2. 앱 기동 (local=자격증명, loadtest=측정설정)
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,loadtest"
# 3. (기준 재정의 반영 후) 측정
k6 run -e EVENT_COUNT=400 -e BOOKER_COUNT=50 loadtest/hold.js
```

- DB 자격증명: `application-local.yml` (gitignore됨 — 디바이스마다 따로 있어야 함). 없으면 `spring.datasource.username/password` 또는 `DB_USERNAME/DB_PASSWORD` 환경변수 설정.
- ddl-auto: create라 앱 재시작마다 DB 초기화 → k6 setup이 매번 자급자족 시드.

## 협업 경계 리마인드 (CLAUDE.md)
- 스크립트 작성·측정 실행 = Claude / **수치 해석·knee 판단·ADR 결론 = 인간**
- 측정 결과 기록 시 반드시 측정 환경(CPU/RAM/MySQL/위치) 병기 — 절대값 아닌 곡선·상대 비교가 핵심
