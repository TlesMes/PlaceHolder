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

## 파일

- `lib/setup.js` — 공통 데이터 시드(provider/event/booker 생성, 토큰 발급)
- `event-list.js` — 이벤트 목록 조회 부하 (N+1 측정)
