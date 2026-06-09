# 부하 테스트 (k6)

Phase D 부하 측정 스크립트. 실제 앱 서버에 HTTP 요청을 보내 거동을 측정한다.

## 사전 준비

- [k6 설치](https://grafana.com/docs/k6/latest/set-up/install-k6/) (Windows: `winget install k6` 또는 `choco install k6`)
- 로컬 Docker MySQL 기동 + 앱 실행 (아래 참조)

## 실행

```bash
# 1. DB 기동 (로컬 Docker MySQL)
docker compose up -d

# 2. 앱 기동 (측정 전용 프로파일 — 쿼리 수 로깅 켜짐)
./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=loadtest

# 3. 부하 테스트 실행
k6 run loadtest/event-list.js
```

### 옵션 (환경변수)

```bash
k6 run -e EVENT_COUNT=200 loadtest/event-list.js   # 데이터 규모 2배
k6 run -e VUS=50 -e DURATION=1m loadtest/event-list.js
```

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `BASE_URL` | http://localhost:8080 | 대상 서버 |
| `EVENT_COUNT` | 100 | setup이 생성하는 이벤트 수 |
| `SEATS_PER_EVENT` | 50 | 이벤트당 좌석 수 |
| `BOOKER_COUNT` | 20 | 생성할 booker 수 |
| `VUS` | 10 | 동시 가상 사용자 |
| `DURATION` | 30s | 측정 지속 시간 |

## 측정 지표 (중요)

**절대 수치(RPS)는 하드웨어 종속이라 포트폴리오 가치가 약하다.** 다음에 주목한다:

1. **쿼리 수 (1차, 환경 무관)** — N+1 before/after 비교의 핵심 증거.
   앱 로그의 Hibernate `generate_statistics` 출력에서 한 요청당 실행된 쿼리 수를 센다.
   - before(순진한 구현): 이벤트 N개 = `1 + 2N` 쿼리
   - after(GROUP BY 집계): `2` 쿼리 (이벤트 수 무관 상수)
2. **list_duration p95 (2차, 환경 참고치)** — 데이터 규모를 키우며(`EVENT_COUNT` 50→100→200)
   응답시간 증가 곡선을 본다. before는 선형 폭발, after는 거의 평탄.

> 측정 결과를 기록할 때는 반드시 **측정 환경(CPU / RAM / MySQL 버전 / 로컬·클라우드)**을 함께 적는다.
> 그래야 "절대값이 아니라 변곡점·상대 개선에 주목한다"는 해석이 성립한다.

### 측정 환경 기록 템플릿

```
- CPU:
- RAM:
- MySQL: 8.0 (Docker)
- 실행 위치: 로컬
- 측정일:
```

## Phase D-2: hold/confirm knee point

부하(도착률)를 계단식으로 올리며 **포화점(knee)** — 달성 처리량이 천장에 닿고 p99가 꺾이는
지점 — 을 찾는다. 절대 RPS가 아니라 **곡선의 형태**가 핵심.

> **부하 테스트 vs 동시성 테스트:** "동시 출발 시 한 명만 성공하는가"(락 **정합성**)는
> JUnit 동시성 테스트(C-1/C-4)가 이미 증명했다. 여기 k6는 그걸 재탕하지 않고
> **성능 곡선(처리량·지연)** 만 본다. 그래서 경합이 아니라 **분산** 모드로 측정한다.

```bash
# hold 경로 (단일 행 락). 좌석을 소비하므로 풀을 충분히 크게.
k6 run -e EVENT_COUNT=400 loadtest/hold.js

# confirm 경로 (좌석+계정 다중 락 + 포인트 차감). setup이 좌석을 미리 hold → confirm만 격리 측정.
k6 run -e EVENT_COUNT=200 -e START_RATE=50 -e MAX_RATE=400 loadtest/confirm.js
```

knee 측정 절차: 1차로 넓게(START_RATE→MAX_RATE) 훑어 knee 구간 파악 → 2차로 그 구간만
`START_RATE`를 끌어올려 좁게 재측정. **수치 해석·판단은 인간 몫**(ADR 결론 포함).

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `START_RATE` | 50 | ramp 시작 도착률(req/s). 저부하 구간 스킵용 |
| `MAX_RATE` | hold 800 / confirm 400 | ramp 상한 도착률 |
| `STAGE_DURATION` | 30s | 각 ramp 단계 길이 (총 측정은 hold TTL 5분 내로) |
| `BOOKER_CHARGE` | 1억 | confirm용 booker 1인당 충전 포인트 |

지표(분리 집계): `*_success`(달성 처리량·성공 지연=knee 판정), `*_rejected`(4xx, 측정 오염 신호로 걸러 해석), `*_error`(5xx/타임아웃=진짜 포화). confirm은 `seat_exhausted`가 0이어야 측정 유효(좌석 풀 부족 감지).

## 파일

- `lib/setup.js` — 공통 데이터 시드(provider/event/booker, 좌석 ID 풀, confirm용 잔액·사전 hold)
- `event-list.js` — 이벤트 목록 조회 부하 (N+1 측정, D-1)
- `hold.js` — 좌석 홀드 부하 (단일 락 knee, D-2)
- `confirm.js` — 예약 확정 부하 (다중 락 knee, confirm 격리, D-2)
