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

## 현재 진행 상황 (2026.07.26 기준)

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

- **Phase E-1: confirm 시 입장 토큰 즉시 회수** — PR #16 (`feature/confirm-token-release`, **머지 완료 2026-07-03**)
  - 구매 완료 유저의 토큰(`entry:` + `active:all` 멤버)이 잔여 TTL(최대 5분) 동안 ceiling 슬롯을 점유하는 **유령 세션** 제거 → 대기자 입장 회전율 개선 (대기열 백로그 #1)
  - `releaseEntryToken`(DEL+ZREM 쌍 정리, 멱등) + confirm **`afterCommit` 훅** — 트랜잭션 안에서 지우면 커밋 실패 시 "결제 안 됐는데 토큰만 잃는" 역전이 생기므로 커밋 성공 시에만 회수(롤백 시 콜백 미실행=토큰 보존). Redis 장애는 catch+warn으로 confirm 무영향(TTL 안전망, ADR-013 degradation)
  - 테스트 82 통과(신규 `ConfirmTokenReleaseTest` 4: 성공 회수/롤백 보존/비활성 이벤트 no-op 멱등/ceiling 가득→confirm→다음 대기자 입장). **degradation 우연 실증**: Redis 컨테이너 없는 기존 confirm 테스트에서 catch 경로가 실제로 동작함을 확인
  - 행동 변화: queueEnabled 이벤트에서 confirm 후 재hold는 재대기 필요(1인 1좌석 전제의 의도된 동작)

- **Phase E-1: 이탈 유저 세션 즉시 회수** — PR #17 (`feature/queue-leave-release`, **머지 완료 2026-07-04**)
  - confirm 없이 떠난 유저의 유령을 두 곳에서 제거: 입장 후 이탈(토큰이 TTL까지 ceiling 점유) + **대기 중 이탈**(대기열 ZSET엔 TTL 없어 순번 되면 유령에게 토큰 발급 — 검토 중 발견). ADR-015
  - `POST /api/queue/{eventId}/leave`: 대기 순번(ZREM)+토큰(`releaseEntryToken`) **무조건 함께** 정리(멱등, DB 무접촉). 프론트 `pagehide`+`fetch(keepalive)`로 호출(sendBeacon은 JWT 헤더 불가) — 대기실+좌석페이지 부착. SPA 내부이동·백그라운드탭은 이탈 아님, 크래시는 TTL 안전망(best-effort)
  - **전제(작성자 결정):** 입장 토큰 TTL 5분은 고정 상한, 연장 없음(초과=캠핑=퇴장) → sliding TTL/heartbeat 기각, 문제를 "고정 5분 내 이탈 감지 속도"로 좁힘
  - **함께 수정한 기존 버그:** 토큰 보유자가 hold하며 enter 재호출→대기열 재enqueue→나중에 토큰 재발급으로 5분 상한 리셋+rate 낭비. `enter`에서 토큰 보유자는 enqueue 생략("토큰 XOR 대기" 서버 불변식). 대기실 새로고침 race(leave가 재진입보다 늦게 도착→새 줄 삭제)는 폴링 자가치유(position null이면 재진입)로 방어
  - 테스트 88 통과(신규 `QueueLeaveTest` 5 + `QueueServiceTest` +1). **Chrome 실브라우저 E2E 검증**: keepalive+JWT(preflight) 조합이 언로드 중에도 서버 도달, 새로고침 시 토큰 즉시 회수 2회 재현(Redis ground truth)
  - ⚠️ **드러난 후속 과제(hold 미반환):** 좌석 hold한 유저가 결제페이지 이탈→좌석페이지 복귀 시 "내 홀드 재개" UI가 죽어있어(`myHeldSeatId={null}` 하드코딩 + DTO에 heldByMe 없음) 자기 좌석 재선택 불가 → 새로고침하면 토큰까지 회수돼 재대기+재hold 거부(isHoldable=false)로 5분 데드락. **PR #17이 만든 게 아니라 기존 버그(뒤로가기만 해도 재현)를 노출.** → PR #18에서 해소

- **Phase E-1: 이탈 시 좌석 hold 반환** — PR #18 (`feature/hold-release-on-leave`, **머지 완료 2026-07-05**)
  - PR #17이 대기열 세션(순번+토큰)은 회수했지만 좌석 hold(최희소 자원)는 만료 대기(5분)뿐이라 미반환이던 "내 좌석 데드락"을 해소. 이탈 즉시 좌석을 AVAILABLE로 되돌려 원천 제거
  - `POST /api/seats/{seatId}/release` (BOOKER): 비관적 락으로 "HELD && 내 홀드"만 `seat.release()`, 타인·CONFIRMED·이미 AVAILABLE은 멱등 no-op(남의 좌석 불가침)
  - **핵심 결정(ADR-016):** release는 좌석만 반환하고 입장 토큰은 건드리지 않는다 — 뒤로가기(좌석만·토큰 유지→"좌석 바꾸기" 성립)와 완전 이탈(좌석+leave)을 프론트가 구분하려면 토큰 회수를 별도 leave API(#17)에 맡겨야 하기 때문
  - `useCheckoutLeaveRelease` 훅: pagehide(닫기·새로고침)=좌석 반환+leave(keepalive) / SPA 언마운트(뒤로가기)=좌석만 반환. 결제 완료(done)면 생략. 기존 beforeunload 경고 유지
  - **실브라우저 E2E 중 발견·수정한 버그:** React StrictMode(개발)의 마운트 직후 setup→cleanup→setup probe가 결제페이지 진입 즉시 좌석을 오반환시킴(hold 직후 release 관측) → `setTimeout(0)` 게이트(`canReleaseRef`)로 probe cleanup을 안전하게 지나치도록 수정. pagehide 경로는 window 이벤트라 애초에 무관
  - 테스트: `SeatReleaseTest`(6, 본인 성공/타인·CONFIRMED·AVAILABLE no-op/만료 홀드 반환/미존재 예외) + `SeatReleaseConcurrencyTest`(release vs confirm 동시 경쟁 20회 반복, 최종 상태 CONFIRMED xor AVAILABLE 결정성 검증)
  - **Chrome 실브라우저 E2E 검증**: hold 유지→SPA 뒤로가기 시 release 200+AVAILABLE 즉시 재선택 가능(데드락 소멸 확인)/pagehide keepalive release 200/정상 confirm 시 release 미발생(done 가드) 3개 시나리오 네트워크+DB ground truth로 확인

- **Phase E-1: 대기열 게이트를 이벤트(좌석페이지) 진입점으로 (A안 전환)** — PR #19 (`feature/queue-gate-event-entry`, **머지 완료 2026-07-05**)
  - B안(hold 진입점에만 게이트)의 두 결함 해소: (1) 좌석 골라놓고 줄→돌아오면 매진 UX 함정, (2) `GET /events/{id}/seats` 폴링이 게이트 밖이라 ceiling이 D-2 좌석 폴링 부하를 못 셰이핑. A안: 좌석페이지 진입 자체를 게이트 뒤로(조회 read 경로 + hold 양쪽)
  - **백엔드 조회 게이트:** `SeatService.getSeatsResponse(eventId, bookerId)`가 queueEnabled+토큰없음(익명 포함) fast-fail(`QueueAdmissionRequiredException`→429). `EventRepository.findQueueEnabledById` 경량 판정. `GET /events/**`는 permitAll 유지 — 게이트는 서비스가 판정(비-큐·비-booker 조회는 자유). admitted 집합(=ceiling 바운드)만 DB 접촉
  - **프론트 진입 게이트:** `EventDetailPage`가 `getQueueStatus`로 토큰 확인→없으면 그리드/폴링 미마운트하고 대기실 리다이렉트. 비-booker는 안내 문구. "대기열 입장하기" 버튼 제거→단일 hold 흐름. `useSeatPolling(eventId, enabled)`로 미입장 시 헛된 GET seats 차단. 결제페이지 이탈 처리(#18)는 그대로 재사용
  - **ADR-013 개정:** 거는 위치 이동 + "배치도는 먼저 보여준다(조회 자유)" 조항 반전(핫 이벤트에선 라이브 그리드 폴링이 곧 D-2 부하). 2층 구조(대기열=트래픽, 락=정합성) 불변. 향후 LB/엣지 승격·서명 토큰 stateless화 방향 명시
  - 테스트: `SeatQueryQueueGateTest` 4종(토큰없음/보유/익명/비활성) + 전체 그린. **Chrome 실브라우저 E2E**: 비-booker→안내·GET seats 미발생 / 미입장 booker→대기실 리다이렉트 후 네트워크 순서상 `status(false)→enter→status(true)→events→seats`로 **미입장 전 구간 GET seats 0건**, 입장 후에만 등장 / 입장 후 그리드+단일 hold 버튼 확인

- **Phase E-1: 이벤트 간 입장 분배 — 균등 RR + deficit 이월(DRR)** — PR #20 (`feature/queue-event-rr-admission`, **머지 완료 2026-07-06**, ADR-017)
  - 백로그 대기열 1번의 기아 방지 부분. 기존 `admitWaiting`은 `Set` 순회 선착순으로 이벤트마다 최대 rate명 시도 → 앞 이벤트가 틱 예산 독식 시 뒤 이벤트 0명(starvation). 목표를 비례 공정이 아닌 **기아 방지**로 확정 — 대기인원 비례는 같은 순번의 소형 이벤트 대기자가 수백 배 기다리는 구조(독식 정당화)라 수치 검증 후 기각
  - 틱 예산 ÷ 활성 이벤트 수(quantum) 균등 분할 + 정수화 끝수는 이벤트별 deficit 장부로 이월(largest-remainder 선지급 음수 → 수혜자 자동 교대, 커서 불요) + **짧은 큐 잉여는 장부 차감 없이 무상 재분배**(work-conserving — 몰수분은 공짜지 대출 아님, 구현 중 발견한 회계 결함을 2단계 분리로 수정) + 빈 큐 크레딧 축적 금지
  - **admit.lua 무수정** — 캡(ceiling·rate)=Lua 보장 / 분배=Java best-effort의 책임 분리(ADR-017 불변식). deficit 장부는 인메모리 단일 인스턴스 전제(ConcurrentHashMap, 동시 호출 시 정밀도만 근사). 단일 이벤트면 기존 동작과 동일(기존 테스트 무수정 통과)
  - 테스트 104 통과. 신규 단위 4종(균등 분할+잉여 재분배/소진 큐 몫 복귀/크레딧·대출 부재/**가동 중 신규 이벤트 등장 시 즉시 균등 몫** 8:0→4:4) + **`QueueStarvationScenarioTest` 33틱 실시간 실측**(rate 4, 핫 200 vs 소형 15×2 — 기아 0회·소진 후 전량 회수·틱당 캡·처리량 무손실 = 4×33 시계열 검증). 브라우저 E2E는 접촉면 없음(백엔드 한 파일)으로 생략
  - 남은 것: 이벤트별 **가중치**(quantum 값만 바꾸면 WRR/DRR 확장) + 저부하 프리패스. **대기실 UX 신호**(ceiling 정체 시 순번 멈춤 → 정직한 ETA·정체 문구)는 범위 제외 → 백로그 등재

- **Phase E-1: 이탈 시 상실 안내 문구** — PR #21 (`feature/queue-hold-ux-copy`, **머지 완료 2026-07-07**)
  - 이탈(새로고침·닫기) 시 대기 순번·좌석 점유가 사라진다는 사실이 페이지 체류 중엔 드러나지 않아, beforeunload 경고(실수 이탈 방지)로도 못 막는 오해가 있었음 → 상시 노출 안내 문구로 보완
  - `CheckoutPage.jsx`/`QueueWaitingPage.jsx`에 안내 문구 각 1줄 추가. 기능 변경 없음(카피 전용), 테스트 추가 없음

- **Phase E-2: 토스페이먼츠 PG 연동 (백엔드)** — PR #22 (`feature/payment-toss-integration`, **머지 완료 2026-07-30**, ADR-018)
  - 로드맵 체크리스트에서 마지막까지 비어 있던 핵심 어필 항목(결제-포인트 정합성/멱등성). 기존 충전은 쿠폰 상환뿐이라 "현금 → 포인트" 경로가 없었음
  - **구조는 OAuth 인가코드 플로우와 동형**(clientKey=client_id, paymentKey=code, secretKey confirm=토큰 교환). 다른 건 **부작용이 비가역적 "돈"**이라는 점뿐 — 이 PR의 안전장치는 전부 거기서 파생
  - **동기 승인(주) + 웹훅(보조) 이중화**: 둘 다 같은 멱등 코어 `PaymentSettlementService.settle`로 수렴. 동기만이면 누락 보정 없고, 웹훅만이면 즉시 반영 안 됨
  - **멱등 = orderId 비관적 락 + "이미 DONE이면 no-op"**(ADR-010 기조 재사용). **금액 위변조 방지** = 주문 생성 시 서버가 amount 확정 저장 후 confirm 요청액과 대조
  - **트랜잭션 경계(핵심):** 토스 호출을 `@Transactional` 안에 두면 네트워크 지연만큼 커넥션 점유 + 락 보유 중 데드락 위험 → ①검증(락 없이 fail-fast) → ②**트랜잭션 밖** 토스 호출 → ③멱등 적립(짧은 단일 tx)로 분리. self-invocation 무효화 회피를 위해 트랜잭션 메서드는 별도 빈에 집중
  - **웹훅은 permitAll이되 페이로드 불신** — paymentKey로 토스 재조회해 실제 DONE일 때만 적립(위조 웹훅 방어). `TossPaymentClient` 인터페이스로 외부 I/O 추상화(RestClient 구현)
  - **느린 외부 의존성 격리:** RestClient에 connect 3s / read 5s 타임아웃(ADR-018 5번). 승인 호출은 트랜잭션 밖이라 DB 커넥션은 안 잡지만 응답이 없으면 요청 스레드가 무한 점유돼, 하류 지연이 좌석 hold 등 무관한 경로로 전파된다 — **대기열(E-1)이 막지 못하는 장애 유형**(대기열은 유입 셰이핑). 응답 없는 소켓 서버로 발동 검증(단정문에 하한+상한 둬 즉시실패 통과 배제)
  - 테스트 117 통과(기존 104 + 결제 12 + 타임아웃 1). 멱등(confirm 2회→적립 1회)·**confirm×웹훅 동시 경합→적립 정확히 1회**(`@RepeatedTest(5)`, C-4·쿠폰 exactly-K와 동종)·금액 위변조 거부·승인 실패→FAILED·웹훅 위조 방어
  - **로컬 스모크(실서버 curl):** orders 200+DB READY 저장 확인 / confirm은 실제 `api.tosspayments.com`까지 도달해 진짜 `401 UNAUTHORIZED_KEY` 수신 → FAILED 전이·잔액 0·PointTransaction 0건(적립 안 샘) 확인
  - **함께 수정한 기존 결함(프로덕션 무관):** 대기열 게이트 테스트 2종이 `active:all`(ZSET, TTL 없음)을 정리하지 않아 다음 클래스의 전역 ceiling 슬롯을 잠식 → CI에서 "정확히 1 모자람"으로 발현(PR #13/#19 시절 잠복, 결제 테스트 추가로 실행 순서가 바뀌며 노출). 개별 수정 후 **정리를 `RedisIntegrationTest` 베이스로 일원화**(FLUSHDB + deficit 장부 초기화) — 키 목록 복붙 방식 자체를 제거. 음성 대조군(정리 끄면 9건 실패 재현)·`@AfterEach` 순서 프로브·역순 전체 실행으로 검증
  - ⚠️ **범위 밖:** 프론트 충전 UI는 후속 PR #23. 토스 테스트 키 미발급이라 실 샌드박스 E2E 미검증(목킹 대체, 키 주입만 하면 동작). **생애주기 축(취소·환불·대사·비동기수단·고아주문)은 통째로 미구현** → 아래 결제 백로그

- **Phase E-2: 프론트 충전 UI** — PR #23 (`feature/payment-frontend-ui`, **머지 완료 2026-08-03**)
  - PR #22가 API만 만들고 사용자 진입점이 없어 결제가 시작조차 되지 않던 상태를 해소. 구조는 백엔드와 같이 **OAuth 콜백과 동형** — 충전 페이지가 `orders`로 orderId·clientKey를 받아 토스 SDK 결제창을 열고, 토스가 `successUrl`로 리다이렉트한 쿼리스트링(paymentKey·orderId·amount)을 콜백 페이지가 파싱해 `confirm`으로 넘긴다(승인 권한은 서버 시크릿 키에만 → 프론트는 값 전달만)
  - 신규: `PointChargePage`(`/points/charge`), `PaymentSuccessPage`(`/payments/success`), `PaymentFailPage`(`/payments/fail`), `lib/tossSdk.js`(CDN 동적 로드 — 결제 무관 페이지에서 외부 스크립트 미수신), `api/payments.js`, 헤더 "충전" 링크, 결제 에러 메시지 2종
  - clientKey 미설정 시 `NO_CLIENT_KEY` 조기 실패 → 키 발급 안내 노출(키 없이도 주문 생성까지는 동작). 콜백 새로고침 시 confirm 재호출은 서버 멱등이라 안전, `useRef`로 StrictMode 이중 마운트 방지
  - **브라우저 E2E 검증**(백엔드+프론트+MySQL 실기동, 콘솔 에러 0): 충전 버튼→`POST /orders` 200·DB `30000/READY` 저장 / **금액 999로 위조한 콜백 → 거부 + DB `READY` 유지**(토스 호출 전 차단) / 정상 콜백 → confirm 400·`FAILED`·잔액 0·적립 0건 / `USER_CANCEL` 안내. 목킹으로 증명한 위변조 방어·무적립이 실제 브라우저 흐름에서 재현됨
  - ~~실제 결제창은 아직 안 뜸(키 미발급)~~ → **2026-08-04 실 토스 E2E로 해소** (아래 PR #24 항목)

- **Phase E-2: 결제 대사(reconciliation) + 실 토스 E2E** — PR #24·#25 (`feature/payment-reconciliation`, **머지 완료 2026-08-04**, ADR-018 개정)
  - 이중화(동기 confirm + 웹훅)가 **둘 다 실패**하는 구멍을 메우는 세 번째 층. 토스는 승인했는데 confirm 응답 전 서버가 죽고 웹훅마저 유실되면 주문은 READY로 남고 **시스템이 영원히 모른다** → 주기 배치로 토스에 되물어 스스로 발견·보정. "환불은 정책적 기능 추가지만 대사 부재는 사고"라 취소·환불보다 먼저 착수
  - **조회 축은 orderId.** 누락 주문은 paymentKey가 null이라 기존 `getPayment`로는 접근 불가 → `findByOrderId`(`GET /v1/payments/orders/{orderId}`) 신설. 404는 예외가 아닌 `Optional.empty()`("아직 결제창을 안 띄웠다"는 정상 구분)
  - **두 잡 분리, 만료 권한은 새벽 잡에만:** 보정 잡(5분, 생성 5분~2h, **종결 권한 없음**) / 만료 잡(cron 04:00 `zone=Asia/Seoul`, 24h 경과, EXPIRED 가능). 토스는 결제창 오픈 전 수 초간 404를 주는데 이때 성급히 종결하면 곧이어 결제한 사용자의 confirm이 거부돼 **막으려던 사고를 대사가 일으킨다** → 자주 도는 잡에서 종결 권한을 아예 제거해 구조적 차단. 보정 잡 상한(2h)은 이탈 주문 반복 조회(≈288회→~24회) 억제
  - 고아는 `FAILED`가 아닌 **`EXPIRED`** 로 구분("토스 거절" vs "시도조차 없었음"). 적립은 기존 멱등 코어 `settle()` 재사용(이미 처리 시 no-op) — 멱등을 코어로 분리해둔 ADR-018 설계의 배당금
  - **⚠️ 실 토스 검증 중 잡은 버그(PR #25):** 토스 404에는 **본문이 실려 온다**(`{"code":"NOT_FOUND_PAYMENT"}`). `onStatus`로 예외만 막고 역직렬화하면 **필드가 전부 빈 객체**가 생겨 `Optional`이 비지 않고 → 만료 잡이 고아를 영영 종결 못 하고 조용히 skipped. **대사 서비스 테스트가 `findByOrderId`를 목킹해 구조적으로 못 잡던 함정** → JDK `HttpServer` 스텁으로 실제 404를 흉내 내는 클라이언트 테스트 추가(수정 전 실패 재현 확인). `toEntity`로 상태 코드 명시 판정, 404 외 에러는 예외 유지(토스 5xx를 '주문 없음'으로 오해해 고아 종결하면 안 되므로)
  - **실 토스 E2E(테스트 키 주입):** 실제 결제창 → 카드 결제 완료 → 주문 DONE·잔액 10,000P·CHARGE 1건 / **API 버전 2025-06-01 응답도 4필드 매핑 정상** / 고아 주문 48h backdate 후 만료 잡 → 실제 404 수신 → `ReconcileResult[scanned=1, expired=1]`, DONE 주문 무영향
  - 테스트 134 통과(기존 117 + 대사 15 + 클라이언트 404/200 2). 대사×웹훅 동시 도착 → 적립 정확히 1회(`@RepeatedTest`)로 **삼중화에도 exactly-once 유지** 증명
  - ⚠️ 사용 중인 키는 **토스 공용 문서 테스트 키**(`mId=tvivarepublica`) — 결제·대사 검증엔 지장 없으나 개발자센터 '테스트 결제내역'이 내 계정에 안 묶여 '취소' 버튼(역방향 대사 실험)을 못 쓴다. 필요 시 토스 회원가입 후 본인 테스트 키로 교체
  - ⚠️ **머지 사고:** PR #24 머지 시 push 타이밍이 어긋나 수정 커밋이 누락 → main이 잠시 버그 상태로 있었고 PR #25로 보완. **PR 머지 전 커밋 수 확인 필요**

### 현재 상태
- **작업 브랜치:** `main` (**E-2 결제 전 구간 머지 완료**, 다음 작업 미착수)
- **마지막 main 커밋:** `Merge pull request #25` (8e5a40e)
  - PR #1~11, #13~25 머지 완료
  - **E-2 결제 완료:** 백엔드(#22, 07-30, ADR-018) + 프론트 충전 UI(#23, 08-03) + **대사·실 토스 E2E(#24·#25, 08-04)**.
    정합성 축(멱등·위변조·트랜잭션 경계·타임아웃) + 삼중화(동기·웹훅·대사)까지 완료.
    **실 토스 결제 관통 확인**(테스트 키). 남은 건 생애주기 축 — 취소·환불(아래 결제 백로그 1번)
  - E-3(조회 API + 프론트 연결 + 부호 버그 수정 + 서비스 테스트 16종) 완료
  - **E-1 대기열 전 구간 머지 완료:** 백엔드(#13, 06-27) + 프론트·입장재설계(#14, 06-29) + enter/status 캐싱(#15, 06-30, ADR-014) + confirm 토큰 회수(#16, 07-03) + 이탈 세션 회수(#17, 07-04, ADR-015) + 좌석 hold 반환(#18, 07-05, ADR-016) + A안 게이트 이동(#19, 07-05, ADR-013 개정) + 이벤트 간 균등 RR 분배(#20, 07-06, ADR-017) + 이탈 안내 문구(#21, 07-07)
- **실행 가능 API:**
  - POST /api/auth/signup - 회원가입, POST /api/auth/login - 로그인(JWT 발급)
  - POST /api/events - 이벤트 등록 (PROVIDER 토큰 필요)
  - GET /api/events - 이벤트 목록, GET /api/events/{id} - 상세, GET /api/events/{id}/seats - 좌석(heldUntil 포함, **queueEnabled면 입장 토큰 필요** ★ E-1 A안)
  - POST /api/seats/{seatId}/hold - 좌석 홀드 (BOOKER)
  - **POST /api/seats/{seatId}/release** - 좌석 hold 반환(이탈 시 즉시 회수, 멱등) (BOOKER) ★ E-1
  - POST /api/seats/{seatId}/confirm - 예약 확정 (BOOKER)
  - POST /api/points/redeem - 캠페인 쿠폰 상환→포인트 충전 (BOOKER)
  - **GET /api/reservations/my** - 내 예약 내역 (BOOKER) ★ E-3
  - **GET /api/points/history** - 포인트 이력 cursor 페이징 (BOOKER/PROVIDER) ★ E-3
  - **GET /api/providers/my/settlement** - 정산 잔액 + SETTLE 거래 목록 (PROVIDER) ★ E-3
  - **POST /api/queue/{eventId}/enter** - 대기열 진입(순번 반환) (BOOKER) ★ E-1
  - **GET /api/queue/{eventId}/status** - 순번·대기 인원·입장 여부·nextPollDelayMs (BOOKER) ★ E-1
  - **POST /api/queue/{eventId}/leave** - 대기열 이탈(순번+토큰 회수, 멱등) (BOOKER) ★ E-1
  - **POST /api/payments/orders** - 결제 주문 생성(orderId 발급 + 금액 서버 확정 저장) (BOOKER) ★ E-2
  - **POST /api/payments/confirm** - 동기 승인 → 포인트 적립(멱등) (BOOKER) ★ E-2
  - **POST /api/payments/webhook** - 토스 웹훅 보조 경로(permitAll, 재조회 검증) ★ E-2
  - POST /api/loadtest/coupons - 쿠폰 생성 (**loadtest 프로파일 전용**, 운영 404)
- **프론트엔드:** frontend/ (React+Vite+Tailwind). `cd frontend && npm install && npm run dev` → :5173. CORS는 WebConfig가 :5173 허용. 결제 페이지 3종(`/points/charge`, `/payments/success`, `/payments/fail`)은 PR #23 ★ E-2

### 다음 작업 (우선순위 순)
> E-2가 실 토스 E2E까지 완결됐다(#24·#25). 마스터플랜 어필 체크리스트 11개 중 **유일한 미확보 항목은 F-3의 "정산의 법적 맥락·README"** 하나다 — 코드로 증명할 것은 다 채웠고 남은 건 서술.
1. **F-3 포트폴리오 문서 마감** — README 확장(domain→problem→solution→result), ADR 18종 정리, 면접 스토리 2종(동시성 설계 서사 / 정산의 법적 맥락). **인프라 의존 없고, 체크리스트 마지막 칸을 직접 채운다.**
2. **취소·환불** — 결제 백로그 1번. 대사(#24)가 안전망으로 깔렸으니 순서상 지금이 착수 적기. 다만 "이미 써버린 포인트" 정책은 **인간 결정 선행**(도메인 규칙).
3. **E-4 분산 환경 동시성**(분산 락) — 마스터플랜 미착수 Phase. 멀티 인스턴스+LB 전제라 *인프라 의존*.
4. **F-1 CD**(AWS EC2 배포) — CI는 #12로 완료, 배포는 미착수. 마스터플랜상 "시간 여유 시".
5. 나머지는 결제/대기열 백로그 — 실측 계열은 인프라 의존.
- (선택) 토스 **회원가입 → 본인 테스트 키** 교체: 현재는 공용 문서 키라 '테스트 결제내역'이 내 계정에 안 묶인다. 역방향 대사·가상계좌 실험에 필요.

### 백로그 — 대기열 (우선순위 순)
> PR #14에서 E-1 입장 제어를 다듬으며 식별. 지금은 단일 인스턴스 전제로 충분. (~~confirm 토큰 반환~~ #16, ~~이탈 세션 회수~~ #17, ~~기아 방지 균등 분배~~ #20, ~~이탈 안내 문구~~ #21 완료)
1. **이벤트별 가중치(WRR/DRR) + 저부하 프리패스** — 균등 분배(#20)의 quantum 값만 가중치로 바꾸면 구조 변경 없이 확장되는 설계. 사업적 등급(대형/소형) 요구가 생기면 착수. 프리패스는 분배 안전망 위에서 재검토(PR #14에서 시기상조 제거).
2. **대기열 필요성 실측** — D-2는 생성기 병목으로 미입증. 부하생성기 별도 머신 + 핫좌석 경합 부하로 지속 과부하 구간 측정. *인프라 의존(별도 머신).*
3. **enter/status 캐싱 효과 실측** — PR #15(ADR-014)는 효과 미측정. 캐시 on/off로 existsById 쿼리 수·커넥션 점유 비교(생성기 분리 필요). *2번과 함께 묶을 수 있음.*
4. **대기실 UX 신호(정체 가시화)** — ceiling 가득+회전 없음이면 순번이 수 분간 정지해 고장과 구분 불가(최대 5분 유한 — 토큰 TTL 상한). 순번 대신 **이벤트별 최근 실제 입장 속도 기반 ETA**(현 `nextPollDelayMs`의 앞인원/rate 계산은 정체 시 거짓말) + status에 정체 플래그 → 프론트 문구 분기. PR #20 설계 논의에서 식별, 범위 제외로 분리.

### 백로그 — 결제(E-2) 미구현 영역
> PR #22 회고에서 식별. 정합성 축은 정석대로 채웠고 **대사·고아 정리는 PR #24·#25로 해소**.
> 남은 건 **생애주기 축의 나머지** — "포인트 충전을 구현했다"고 말할 때 함께 밝혀야 할 공백.
> (~~2. 대사~~ #24, ~~4. 고아 주문 정리~~ #24 완료)
1. **취소·환불 (다음 착수 대상, 정책 확정됨 2026-08-04)** — 토스 취소 API + 포인트 회수 경로가 0. 실서비스면 청약철회가 법적 요구라 필수. **보상 트랜잭션 문제**라 결제보다 어렵다 — 결제는 외부 성공+내부 실패 시 재시도로 복구되지만(멱등), 취소는 "돈은 돌려줬는데 포인트 회수 실패"가 순손실이다. 대사(#24)가 안전망으로 깔려 순서상 지금이 착수 적기.
   - **확정 정책(작성자 결정):**
     | 항목 | 결정 | 근거 |
     |---|---|---|
     | 환불 범위 | **미사용분만 부분 환불** | 잔액만큼만 취소(토스 부분 취소 지원). `PARTIAL_CANCELED` 상태 + 누적 취소액 필드 필요 |
     | 취소 주체 | **API만(관리자·수동)** | 환불은 **고객 문의를 통해 진행**하는 것이 맞다는 판단 → 셀프 UI 불필요. PR이 한 덩어리로 끝남 |
     | 취소 기한 | **7일 이내** | 전자상거래법 청약철회 기간. "정산의 법적 맥락" 어필 축과 연결 |
     | 보상 순서 | **포인트 선회수 → 토스 취소 → 실패 시 포인트 복구** | 우리 DB 롤백은 통제 가능하지만 토스 취소는 되돌릴 수 없다는 비대칭. 최악(복구까지 실패)은 대사가 탐지 |
   - 파생(별도 결정 불필요): `DONE→CANCELED/PARTIAL_CANCELED` 전이, `PointTransaction`에 **`REFUND` 타입 추가**(DEDUCT 재사용은 이력 혼동), 취소 멱등(비관적 락+상태 체크 재사용), `TossPaymentClient.cancel()` 추가
   - ⚠️ **구조적 공백:** `PointTransaction`에 `PaymentOrder` 연결 필드가 없어 "이 CHARGE가 어느 주문에서 왔는지" 추적 불가. **API만 방식에선 orderId를 직접 받으므로 이번 범위 밖**이지만, 훗날 셀프 환불 UI를 붙이면 선행 과제가 된다
2. **역방향 대사** — 우리 DONE인데 토스는 취소/실패인 경우. 현재는 토스 DONE 확인 후에만 DONE 전이하므로 **사후 취소가 있어야 발생 → 취소 기능이 없는 지금은 도달 불가**. 1번과 함께 붙인다.
3. **비동기 결제수단 미지원(가상계좌 등)** — 상태머신이 `READY→DONE/FAILED/EXPIRED`라 "입금 대기"(며칠 지속) 중간 상태를 표현 못 함. **웹훅이 진짜 필요해지는 대표 사례가 이것**인데 현재 웹훅은 confirm 누락 보정 용도로만 쓰인다. 프론트도 `requestPayment('카드')` 고정. 토스 테스트 결제내역의 '입금처리' 버튼으로 검증 가능.
4. **웹훅 서명 검증** — 현재는 재조회 검증까지(ADR-018 한계에 명시). 서명/IP 화이트리스트는 미적용. 보안 키는 이미 발급받아 둠.
5. **충전 한도·감사 로그·영수증** 없음. 포인트 충전은 규제상 단순 결제와 다르게 취급될 수 있음(선불 성격의 자금 보관 → 이용자 자금 보호 의무 등) — **실제 요건 확인 필요**. 프로젝트 어필 축인 "정산의 법적 맥락"과 직결되는 도메인 숙제.
6. **SDK v1 → v2 마이그레이션** — 프론트가 `js.tosspayments.com/v1/payment` 사용 중이고 토스는 v1 업데이트 중단을 공지. **백엔드·콜백은 무변경**(서버 REST API는 v1 그대로, 쿼리 파라미터 동일)이고 바뀌는 건 SDK 초기화·`requestPayment` 시그니처·`amount` 객체화 + `customerKey` 도입뿐이라 파일 2개 규모. 다만 v2의 주 매력(간편결제·결제위젯)이 **계약 없이는 대부분 잠겨 있어** 현재 이득이 거의 없다 → **트리거: 간편결제를 붙이거나 전자결제 계약을 진행할 때**.
7. (의도적 제외, 재검토 대상) 재시도·서킷브레이커·전용 스레드풀 격리 — 현 단계 과잉으로 판단해 타임아웃까지만 적용(ADR-018 5번).
8. **대사 batch-size vs 토스 API 제한** — 테스트 환경은 **분당 100건** 제한인데 `batch-size: 100`이 정확히 같다. 평상시 READY 잔여는 몇 건 수준이라 실제로 걸릴 일은 드물지만, 대량 누락 상황에선 한도를 소진할 수 있다. 값 하향 또는 건별 간격 검토.

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