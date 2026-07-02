# PlaceHolder — CLAUDE.md

## 프로젝트 개요
좌석 제공자가 등록한 좌석을 예약자가 포인트로 예약하는 양면 마켓플레이스.
동시 요청이 몰리는 좌석 점유 시나리오에서 정합성을 보장하는 동시성 제어가 핵심 주제.

## 기술 스택
- Java 17, Spring Boot 3.3.x
- Spring Data JPA (Hibernate)
- Spring Security + JWT
- MySQL 8.0 (Docker)
- Lombok

## 패키지 구조
com.placeholder
├── domain/
│   ├── user/entity/
│   ├── booker/entity/
│   ├── provider/entity/
│   ├── event/entity/
│   ├── seat/entity/
│   ├── reservation/entity/
│   └── point/entity/

## 핵심 설계 결정
- 연관관계: 전부 단방향
- fetch: 전부 LAZY
- cascade: 없음 (명시적 삭제)
- soft delete: users.deleted_at
- 포인트 단위: int
- Enum 저장: EnumType.STRING

## 도메인 규칙
- 좌석 점유(Hold): Seat 상태필드 흡수 (AVAILABLE → HELD → CONFIRMED)
- 포인트 차감: 확정 시점에만 발생 (홀드는 점유만)
- 확정 트랜잭션: [예약자 포인트 차감 + 제공자 정산예정액 적립 + 좌석 CONFIRMED] 단일 트랜잭션
- 정산: settlement_balance 집계까지만, 현금 출금 없음

## 개발 규칙
- 엔티티 직접 수정 금지 (setter 없음, 도메인 메서드로만 상태 변경)
- @Data 사용 금지
- 경쟁 조건 관련 코드는 반드시 테스트 작성
- ADR 필요한 결정은 docs/adr/에 문서화

## 설계 문서
- docs/README.md (문서 구조 가이드)
- docs/placeholder_master_plan.md (전체 Phase 플랜)
- docs/table_definitions.md
- docs/sql/schema.sql
- docs/adr/

---

## Claude Code 활용 경계

### Claude에게 맡길 것 ✅
- Repository/Service/Controller 보일러플레이트
- 기본 CRUD 로직 (단순 조회/등록/수정)
- 테스트 코드 구조 작성 (given-when-then)
- ADR/문서 초안 작성
- CI/CD 스크립트 생성
- SQL 마이그레이션 파일 생성
- 부하 테스트 스크립트 작성 (k6, nGrinder 등)

### 인간이 직접 할 것 🧑‍💻
- 트랜잭션 경계 설계
- 락 전략 선택 (비교는 Claude, 결정은 인간)
- 측정 수치 해석 및 판단
- ADR 결론 작성
- Phase 우선순위 조정
- 도메인 규칙 검증 (법적 맥락 이해)
- PR 최종 self review

### 협업이 필요한 것 🤝
- 동시성 제어 로직 (Claude 구현 → 인간 검증 → 함께 개선)
- 성능 측정 및 분석 (Claude 측정 → 인간 분석)
- 복잡한 트랜잭션 (인간 설계 → Claude 구현 → 인간 검증)

---

## PR 작업 규칙

### 1 PR = 1개 기능 단위
- 예: `feat: 비관적 락 기반 좌석 홀드 구현`
- 예: `feat: JWT 인증 필터 추가`
- 예: `docs: ADR-006 락 전략 결정 문서화`

### PR 체크리스트
- [ ] 엔티티 메서드로만 상태 변경 (setter 미사용)
- [ ] 동시성 관련 코드는 테스트 작성됨
- [ ] ADR 필요 시 함께 커밋
- [ ] 트랜잭션 경계 명확
- [ ] 불필요한 cascade 없음
- [ ] fetch 전략 LAZY 유지

### 커밋 메시지 컨벤션
- `feat:` 새로운 기능 추가
- `fix:` 버그 수정
- `refactor:` 리팩토링 (동작 변경 없음)
- `test:` 테스트 추가/수정
- `docs:` 문서 작성/수정
- `chore:` 빌드, 설정 파일 수정

---

## 현재 진행 상황 (2026.06.30 기준)

> 완료 항목은 "무엇을/왜"만 요약. 구현 디테일·근거는 해당 ADR / PR에서 확인.

### 완료된 작업 ✅
- **Phase A~B:** 설계 + 기반 (머지 완료)
  - 엔티티 7종, Repository 7종, ERD/테이블 정의/초기 ADR
  - 기본 CRUD API(Event/Seat), Exception 인프라, DTO/Service/Controller 레이어
  - 회원가입 단일 트랜잭션(User + Booker/Provider), 역할 검증 fail-fast
  - JWT 인증/인가(로그인 발급, 필터, @PreAuthorize 역할 제어)
  - 후속 보안/테스트 개선: soft delete 필터링, 401/403 의미 분리, 인증 테스트 4종

- **Phase C: 좌석 동시성** (PR #1~4, 머지 완료)
  - **C-1 홀드(비관적 락):** `findByIdForUpdate`로 행 잠금 → 한 명만 성공. AVAILABLE→HELD, held_until TTL 5분. lazy 만료(ADR-008). `POST /api/seats/{id}/hold`
  - **C-2 확정(원자성):** 좌석·BookerAccount 락 → [포인트 차감 + 정산 적립 + CONFIRMED + Reservation + PointTransaction] 단일 트랜잭션, 잔액 부족 시 전체 롤백. `POST /api/seats/{id}/confirm`
  - **C-3 자동 만료(스케줄러):** 후보 ID 무락 조회 → 행별 재잠금 → 만료 재확인 → release (TOCTOU 방어). lazy 만료를 안전망으로 병행(ADR-009)
  - **C-4 정합성 테스트:** 만료 vs confirm/hold 경쟁 5종으로 위반 부재 증명

- **포인트 충전 (쿠폰 상환)** — PR #5 (머지 완료)
  - 목적: confirm 측정에 필요한 booker 잔액 확보. 무제한 대신 쿠폰 상환으로 진입 제한
  - 비관적 락 + (coupon_id,user_id) 유니크. `POST /api/points/redeem`. ADR-010(비관적 락 채택 근거)

- **Phase D-1: N+1 발견→해결** — PR #6 (머지 완료)
  - "목록에 잔여/총 좌석 수" 추가가 N+1(1+2N) → GROUP BY 집계로 쿼리 1+2N→2, list p95 ~10x 개선
  - k6 인프라 최초 구축(`loadtest/`). ADR-011(fetch join 대신 집계 채택). 정확성 테스트 보강

- **Phase E-3: 조회 API (백엔드)** — PR #8 (머지 완료)
  - 자기 데이터 조회 3종 → 마이페이지·정산 대시보드 토대
    - `GET /api/reservations/my` (BOOKER): fetch join으로 N+1 회피
    - `GET /api/points/history` (BOOKER/PROVIDER): cursor 페이징(created_at < cursor DESC, size 20/max 100, from default now-3개월)
    - `GET /api/providers/my/settlement` (PROVIDER): 잔액 + SETTLE 목록
  - 인덱스 추가 없이 기존 단일 `idx_pt_user_id` 유지(도메인상 사용자당 거래 소량). ADR-012
  - 서비스 단위 테스트는 별도 PR(Docker 환경)로 분리

- **Phase E-3: 프론트엔드 + 테마** — PR #9 (머지 완료)
  - 마이페이지(`/me`, BOOKER): 예약 내역 + 포인트 이력(타입별 색·기간/타입 필터·"더 보기" 시안) 탭 전환
  - 정산 대시보드(`/provider/settlement`, PROVIDER): 잔액 카드 + SETTLE 테이블
  - ProtectedRoute에 `requiredRole` 추가(역할 보호). UI/UX 단계 — 모의 데이터 하드코딩(API 연결은 PR #10)
  - 다크/라이트 테마: 의미 기반 색상 토큰(CSS 변수, `darkMode:'class'`) + 토글(localStorage 영속, FOUC 방지). 기존 색 하드코딩 전량 토큰화

- **Phase E-3: 프론트 API 연결** — PR #10 (머지 완료)
  - PR #9의 모의 데이터(MOCK_*)를 실제 조회 API 3종으로 교체. api 모듈 추가(reservations/points/providers)
  - 포인트 이력: 기간(1/3/6개월)은 `from` 서버 반영, cursor 페이징("더 보기") 실동작. **타입 필터는 클라이언트 처리**(서버 미지원, ADR-012 거래량 적음 전제)
  - SettlementPage 링크 버그 수정(reservationId를 eventId로 오용 → eventId 부재로 텍스트화)
  - **부호 표시 버그 수정**: `amount`는 타입 무관 양수 크기 저장(방향은 `type`) → 부호를 `TYPE_META.sign`에서 도출(사용=−, 충전/정산=+). Chrome 수동 검증으로 발견(`docs/verification/PR10-frontend-api-검증-2026-06-14.md`)
  - **정산 조회 무페이징 한계 백로그**: `/providers/my/settlement`가 SETTLE 전건 반환 → PROVIDER는 좌석 판매량 비례 증가라 ADR-012 "거래량 적음" 가정이 약함. 측정 후 cursor 페이징 검토(`docs/performance/settlement-query-scalability.md`)

- **Phase E-3: 조회 API 서비스 단위 테스트** — PR #11 (머지 완료)
  - PR #8(조회 API 3종)이 테스트 없이 머지된 회귀 안전망 보강. 총 16건, 전부 통과(Testcontainers MySQL)
    - `MyReservationsServiceTest`(3): 매핑·confirmedAt DESC·본인 격리·빈 리스트
    - `PointHistoryServiceTest`(8): 기본 size 20/period 3개월·MAX_SIZE 100 cap·from 필터·cursor strict(<)·다중 페이지 무누락/무중복·nextCursor null·user 격리·CHARGE/DEDUCT 매핑(양수 amount)
    - `ProviderSettlementServiceTest`(5): 잔액+매핑·SETTLE만·빈 목록·계정 없음(UserNotFoundException)·provider 격리
  - **타임스탬프 정밀도 함정:** `created_at`/`confirmed_at`은 `@PrePersist now()` 고정 → cursor/정렬 경계를 결정론적으로 만들려면 저장 후 `JdbcTemplate`으로 덮어씀. 단일 값을 저장값+cursor 파라미터로 동시에 쓰는 경계 테스트는 `truncatedTo(SECONDS)`로 datetime(6) round-trip 일치 보장(self-invocation `@Transactional`은 프록시 미경유라 무효, repository.save 자체 트랜잭션에만 의존)

- **Phase D-2: hold knee point 측정** — PR #7 (`feature/loadtest-hold-confirm-knee`, **머지 완료 2026-06-23**)
  - 방법: 초당 요청 수(도착률)를 점점 올리며 **성공 응답의 지연 p99가 임계(기본 1초)를 넘는 지점**을 한계점으로 보고 자동 중단(A안). 좌석 7.5만(좌석 부족 방지) + 풀 크기 `HIKARI_POOL` env 가변
  - **풀 크기별 측정 5/10/20(이 PC 기준):** 한계 도착률이 5→10에서 2.2배로 늘고(연결이 부족했다는 뜻) → 10→20에선 거의 안 늘어남(연결 충분, CPU가 한계). 풀 5는 CPU 65%로 노는데도 꺾임=연결 부족. → **병목은 커넥션 풀이 아니라 ~풀10부터 이 PC의 CPU**(앱+MySQL+k6 한 PC 공유, ~880/s). 적정 풀 ≈ 10(기본값), **튜닝 불필요**
  - **결론:** 시험 범위에선 5xx 미발생. **단 이는 서버가 우아하게 버텼다는 증거가 아님** — k6(부하생성기)가 응답 지연 시 요청 생성을 스스로 밀거나 drop해, 서버를 실제 한계까지 밀지 못했다(coordinated omission / 생성기 자체 병목). 지연 한계점 자동 중단도 서버가 깨지기 전에 멈춘 것. 요컨대 **"안 죽는다"도 "죽는다"도 이 측정으로는 말할 수 없음**. 과부하 지속/무제한 사용자면 커넥션 대기 30초 초과 → 5xx 예상이나, 그 구간을 실제로 측정하지 않았음. → 대기열(E-1)의 필요성은 **입증된 것이 아니라 미지(未知)**; 진짜 확인하려면 부하생성기를 분리(별도 머신 또는 closed-loop 보정)해야 함. ⚠️ 부하를 골고루 분산해 측정해서, 잰 건 **인프라 처리량**이지 "같은 좌석 경합 하 락 설계"가 아님(경합 정확성은 C-4가 증명). **confirm 미실행**. 상세: `loadtest/HANDOFF-D2.md`
  - 부산물: 측정이 가설을 3회 교정(풀=병목→아니다→10미만에선 맞다). **실질 perf 성과는 D-1(N+1)**, D-2는 보조 결론
  - 쿠폰 생성 API(`POST /api/loadtest/coupons`, `@Profile("loadtest")` 전용): 운영엔 빈 없어 404

- **Phase E-1: Redis 대기열 (백엔드)** — PR #13 (`feature/queue-redis-implementation`, **머지 완료 2026-06-27**)
  - ADR-013 설계를 구현. **대기열=트래픽 셰이핑만**, 좌석 정합성은 기존 비관적 락(ADR-008)이 유지하는 2층 구조. `queueEnabled=true` 이벤트만 게이트 동작, 비활성·Redis 장애 시 hold 그대로 통과(graceful degradation, 하위호환)
  - 구현: Redis Sorted Set 순번(`enqueue` ZADD NX로 FIFO 보존/`rank`), 입장 토큰(String+TTL, `issueEntryToken`/`hasEntryToken`), hold 게이트(`SeatService.enforceQueueAdmission` — 락 잡기 전 비잠금 projection으로 fast-fail), 배치 입장 스케줄러(얇은 `QueueAdmissionScheduler` → `QueueAdmissionService.admitWaiting` ZPOPMIN)
  - **`current_held_count`를 "유효 입장 토큰 수"(SCAN)로 구체화:** HELD 좌석 수로 세면 토큰만 받고 hold 전인 in-flight가 누락돼 한 틱 내 과다 입장 → 토큰 자체를 셈
  - 테스트 73종 전부 통과(큐 신규 15: smoke 1/QueueService 5/SeatHoldQueueGate 3/QueueAdmissionService 4/QueueConcurrency 2). 동시성: 스케줄러 race(ZPOPMIN 원자성→토큰 XOR 대기 분할 불변식) + 토큰·락 합성(토큰 보유자 다수 동시 hold→락이 1명만 성공)
  - **게이트(3단계)와 스케줄러(4단계)는 분리 불가** — 토큰 발급 주체 없으면 queueEnabled 이벤트 영구 입장 거부 → 단일 PR로 묶음
  - 한계: 대기열 **필요성 자체는 미입증**(D-2 생성기 병목). 이 PR은 "필요해서"가 아니라 **설계안을 구현으로 증명**하는 포트폴리오 단위

- **Phase E-1: 프론트엔드 + 입장 제어 재설계** — PR #14 (`feature/queue-redis-implementation`, **머지 완료 2026-06-29**)
  - **프론트엔드:** 대기실 페이지(`QueueWaitingPage`, 동적 폴링), EventDetail 입장 게이트 분기(queueEnabled면 대기열 입장), EventCreatePage + `queueEnabled` 토글, api/queue.js, 라우팅·헤더
  - **입장 제어 재설계(핵심):** 기존 `max-concurrent-holds`(이벤트별, DB 풀 사이징=단위 오류) → **전역 `max-active-sessions`(ceiling, 앱 세션 용량) + `rate-per-second`(초당 입장)** 두 레버. check-then-act(활성수 확인→ZPOPMIN→토큰 발급)을 **`admit.lua` EVAL 1회로 원자화** → 다중 인스턴스/스레드 동시 호출에도 캡 초과·중복 입장 불가(락 없이 상호배제). 활성 세션은 `active:all` ZSET(만료 score), rate는 `rate:{epochSec}` 버킷
  - **입장은 스케줄러 일원화:** `enter()`는 enqueue만. enter fast-path(빈자리 즉시입장)는 추가했다 **제거(시기상조)** — 고부하 전제라 이득 없고 오픈 정각 스파이크에 EVAL 부하만 더함
  - **동적 폴링:** status 응답 `nextPollDelayMs = clamp(앞인원/rate, min, max)` → 대기실 폴링 부하 완화(앞쪽 촘촘/뒤쪽 성김)
  - 테스트 76 통과(큐: 원자 ceiling 정확성·rate 윈도 캡·초과→이탈→대기순 −1·동적 폴링). E2E 브라우저 검증(대기실→ceiling 가득 대기→해제 시 자동 입장 리다이렉트)

- **Phase E-1: enter/status 이벤트 존재 검증 캐싱** — PR #15 (`feature/cache-event-exists`, **머지 완료 2026-06-30**)
  - `QueueService.requireEventExists`(enter·status 공용)의 `eventRepository.existsById`가 매 요청 MySQL 직격 → 대기열이 보호해야 할 DB로 오픈 정각 스파이크가 직행하던 자기모순 제거
  - **read-through Caffeine 캐시**(`eventExists`, TTL 60s `expireAfterWrite`/maxSize 1000). 존재 검증을 별도 빈 `EventExistenceChecker`로 분리 — `@Cacheable`이 self-invocation으로 무력화되는 함정 회피. `@Cacheable(unless="#result==false")` **양성만 캐싱**(미존재 캐싱 시 신규 생성 이벤트 404 고착 방지)
  - **`sync=true`는 `unless`와 양립 불가** → 스탬피드 방지(sync) 포기, 기능 정합성(양성 캐싱) 우선. 잔존 스탬피드(오픈 첫 ms·60초 만료 순간 동시 미스)는 초경량 PK 조회라 수용. ADR-014
  - 테스트: `QueueServiceTest` +2(`@MockitoSpyBean`으로 DB 조회 횟수 검증 — 캐시 히트 1회/미존재 uncached). 프로젝트 표준 `@MockitoSpyBean` 사용(deprecated `@SpyBean` 아님)
  - ⚠️ **효과 미측정** — existsById가 실제 병목이었다는 증거 없음(PK 단건은 경량). "측정된 성능 개선"이 아니라 **"구조적 결함 제거 + 저비용 보강"** 의 의사결정(ADR-013/D-2 정직성 기준 동일)

### 현재 상태
- **작업 브랜치:** `main` (E-1 일단락, 다음 작업 미착수)
- **마지막 main 커밋:** `Merge pull request #15` (5960f2f)
  - PR #1~11, #13~15 머지 완료
  - E-3(조회 API + 프론트 연결 + 부호 버그 수정 + 서비스 테스트 16종) 완료
  - **E-1 대기열 전 구간 머지 완료:** 백엔드(#13, 06-27) + 프론트·입장재설계(#14, 06-29) + enter/status 캐싱(#15, 06-30, ADR-014)
- **실행 가능 API:**
  - POST /api/auth/signup - 회원가입, POST /api/auth/login - 로그인(JWT 발급)
  - POST /api/events - 이벤트 등록 (PROVIDER 토큰 필요)
  - GET /api/events - 이벤트 목록, GET /api/events/{id} - 상세, GET /api/events/{id}/seats - 좌석(heldUntil 포함)
  - POST /api/seats/{seatId}/hold - 좌석 홀드 (BOOKER)
  - POST /api/seats/{seatId}/confirm - 예약 확정 (BOOKER)
  - POST /api/points/redeem - 캠페인 쿠폰 상환→포인트 충전 (BOOKER)
  - **GET /api/reservations/my** - 내 예약 내역 (BOOKER) ★ E-3
  - **GET /api/points/history** - 포인트 이력 cursor 페이징 (BOOKER/PROVIDER) ★ E-3
  - **GET /api/providers/my/settlement** - 정산 잔액 + SETTLE 거래 목록 (PROVIDER) ★ E-3
  - **POST /api/queue/{eventId}/enter** - 대기열 진입(순번 반환) (BOOKER) ★ E-1
  - **GET /api/queue/{eventId}/status** - 순번·대기 인원·입장 여부·nextPollDelayMs (BOOKER) ★ E-1
  - POST /api/loadtest/coupons - 쿠폰 생성 (**loadtest 프로파일 전용**, 운영 404)
- **프론트엔드:** frontend/ (React+Vite+Tailwind). `cd frontend && npm install && npm run dev` → :5173. CORS는 WebConfig가 :5173 허용.

### 다음 작업 (우선순위 순)
> **정정(2026-06-30):** CI 파이프라인(PR #12)은 이미 머지 완료 — 직전 갱신에서 "병렬 진행 중"으로 잘못 이어적음. 전 브랜치 확인 결과 열린 PR·진행 중 작업 없음.
1. 대기열 백로그(아래) 중 택1 — **다음 세션 시작점: confirm 시 입장 토큰 반환(작고 독립적).**

### 백로그 — 대기열 (우선순위 순, 다음 세션 이어가기용)
> PR #14에서 E-1 입장 제어를 다듬으며 식별. 지금은 단일 인스턴스·단일 핫이벤트 전제로 충분.
1. **confirm 시 입장 토큰 반환** — 성공/이탈 유저 토큰이 TTL(5분)까지 ceiling 슬롯 점유(유령 세션) → 구매 완료 시 즉시 회수로 처리량 개선. *작음.* ← **다음 세션 시작점**
2. **이벤트별 가중치 입장 제어(RR/쿼터) + 저부하 프리패스** — 현재 ceiling·rate는 **전역**이라 핫 이벤트가 전역 rate를 독식해 다른 이벤트가 굶을(starvation) 수 있음. 전역 캡 아래 이벤트별 공정 분배 도입, 그 안전망 위에서 enter 프리패스 재검토(키 분리 아님 — 전역은 의도된 설계). *큰 단위·동시성 showcase.*
3. **대기열 필요성 실측** — D-2는 생성기 병목으로 미입증. 부하생성기 별도 머신 + 핫좌석 경합 부하로 지속 과부하 구간 측정. *인프라 의존(별도 머신).*
4. **enter/status 캐싱 효과 실측** — PR #15(ADR-014)는 효과 미측정. 캐시 on/off로 existsById 쿼리 수·커넥션 점유 비교(생성기 분리 필요). *3번과 함께 묶을 수 있음.*

### 백로그 — 기타
- **정산 조회 cursor 페이징(측정 선행):** `/providers/my/settlement` 전건 반환 → 건수 증가 응답 곡선 측정 후 도입 판단. `docs/performance/settlement-query-scalability.md`

### 중요 메모
- **⚠️ Maven 실행:** 시스템에 `mvn`이 **설치되어 있지 않음**(PATH에 없음, IntelliJ 번들 Maven만 존재). 터미널/스크립트에서 빌드·실행 시 반드시 **`mvnw.cmd`(Windows) / `./mvnw`(bash)** 사용. 예: `.\mvnw.cmd spring-boot:run`, `.\mvnw.cmd test`. `mvn ...`을 직접 호출하면 `command not found`로 실패하고, 백그라운드 실행 시엔 PID만 찍히고 즉시 종료됨(로그 안 남음). Wrapper는 Maven 3.9.16 + Java 17(Temurin) 자동 인식.
- **인증:** JWT 적용 완료. 컨트롤러는 @AuthenticationPrincipal로 userId 획득 (providerId 파라미터 제거됨)
- **테스트 데이터:** Provider User (ID=1, email: provider@test.com) 존재
- **주의:** ddl-auto: create이므로 애플리케이션 재시작 시 Provider User 재생성 필요
- **✅ 테스트 DB 격리 완료:** 통합 테스트를 Testcontainers MySQL로 격리. 베이스 클래스 `support/MySQLIntegrationTest`(@Testcontainers + @ServiceConnection)를 extends, `@ActiveProfiles("test")` + `application-test.yml`(create-drop). 더 이상 로컬 DB를 건드리지 않음.
  - **⚠️ Docker API 버전 함정:** Docker Engine 29.x(MinAPIVersion 1.40) ↔ docker-java 3.4.1이 초기 probe에 하드코딩 API **1.32**를 보내 **HTTP 400** → "Could not find a valid Docker environment"로 테스트 전멸. 해결: `pom.xml` surefire `<systemPropertyVariables><api.version>1.44</api.version></systemPropertyVariables>`. 키는 반드시 `api.version`(env var `DOCKER_API_VERSION` 아님). TCP 노출 불필요 — named pipe 기본값으로 동작. **새 환경에서 동일 증상 시 이 설정부터 확인.**
- **패키지 구조:**
  ```
  domain/
  ├── auth/ (controller, service, dto - 회원가입/로그인)
  ├── user/ (entity, repository)
  ├── booker/, provider/ (entity, repository)
  ├── event/ (entity, repository, dto, service, controller)
  ├── seat/ (entity, repository, dto, service, controller - 홀드)
  ├── reservation/ (entity, repository, dto, service, controller - 확정)
  ├── point/ (entity, repository)
  global/
  ├── exception/ (GlobalExceptionHandler, ErrorResponse, custom/)
  ├── jwt/ (JwtProvider, JwtAuthenticationFilter)
  ├── security/ (CustomUserDetails(Service), JwtAuthenticationEntryPoint)
  └── config/ (SecurityConfig, WebConfig - CORS)
  ```
- **프론트엔드 구조:** `frontend/src/` — pages(Login/Signup/EventList/EventDetail/Checkout/MyPage/Settlement), components(SeatGrid/SeatCell/Header/Layout/ThemeToggle 등), context(Auth/Toast/Theme), hooks(useSeatPolling), lib(jwt/errors/format/seatStyle/theme), api(client/auth/events/seats/reservations/points/providers). 폴링은 useSeatPolling 훅 경계로 분리(추후 SSE 교체점).
  - **색상 토큰:** 색은 의미 기반 토큰(`bg-surface`/`text-fg`/`primary`/`success` 등)으로만 사용. 팔레트 색(slate/indigo…) 직접 하드코딩 금지. 토큰 값은 `index.css`의 `:root`/`.dark` CSS 변수, 정의는 `tailwind.config.js`.

---

## 세션 시작 템플릿

Claude Code 세션 시작 시 다음 정보를 전달:

```
현재 Phase: [B/C/D/E/F]
이번 세션 목표: [placeholder_master_plan.md의 특정 항목]
작업 브랜치: feature/[feature-name]
제약사항: [CLAUDE.md의 도메인 규칙 재확인 필요 시]
```

**예시:**
```
현재 Phase: C
이번 세션 목표: Phase C-1 비관적 락 기반 좌석 홀드 구현
작업 브랜치: feature/hold-pessimistic-lock
제약사항: 엔티티 setter 금지, 상태 변경은 도메인 메서드로만
```