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

## 현재 진행 상황 (2026.08.22 기준)

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

- **Phase E-2: 결제 취소·환불 (보상 트랜잭션)** — PR #26 (`feature/payment-cancel-refund`, **머지 완료 2026-08-19**, ADR-019)
  - ADR-018이 "가장 큰 구멍"으로 지목한 생애주기 축의 마지막 항목. **결제보다 취소가 어렵다** — 결제는 외부 성공+내부 실패를 멱등 재시도로 수렴시키지만, 취소는 "돈은 돌려줬는데 포인트 회수 실패"가 곧 순손실이다
  - **보상 순서(핵심):** ①[tx] 검증→포인트 선회수→REFUND 기록→취소 기록 → ②**[tx 밖]** 토스 취소 → ③[tx] 성공: 확정 기록 / 실패: ①을 통째로 롤백. **되돌릴 수 있는 쪽(우리 DB)을 먼저, 되돌릴 수 없는 쪽(토스)을 나중에**
  - **상태 전이를 외부 호출보다 먼저 커밋하는 것이 동시 취소 방어선.** 나중에 바꾸면 그 사이 들어온 두 번째 요청이 잔여액을 재계산해 토스에 중복 취소를 날린다(= 돈 두 번 환불, 포인트 1회 회수). 락을 네트워크 호출 내내 쥐는 것은 ADR-018이 금지하므로 선커밋 외 대안이 없음
  - `canceledAt`(우리 기록)과 `cancelConfirmedAt`(토스 확인) **분리 저장** — ①커밋 후 ②호출 전 크래시 창을 나중에 식별하기 위한 표식(역방향 대사 후보 조건)
  - 토스 `Idempotency-Key`는 **누적 취소액** 기준. 이번 취소액으로 만들면 같은 금액 부분취소 반복 시 키가 겹쳐 토스가 첫 결과를 재생 → 장부만 2회, 실제 환불 1회인 조용한 손실
  - **함께 고친 기존 결함 2건:** (1) 멱등 판정 `isDone`→`isSettled` — 취소된 주문도 "한 번 적립된" 주문이라 그렇지 않으면 **지연 도착 웹훅이 환불한 포인트를 되살린다**. 상태 추가가 기존 가드 의미를 뒤집는 전형적 함정. (2) confirm 응답의 하드코딩된 `DONE` 제거 → 실제 주문 상태 반영
  - **⚠️ CI에서 잡은 프로덕션 결함(`ac2a5dd`):** 읽기 타임아웃은 소켓 상한이 아니라 **진행 중 요청을 취소**하는 방식으로 구현돼 있다(Spring `JdkClientHttpRequest`가 감시자를 등록해 `future.cancel()`). Spring은 `ExecutionException`/`InterruptedException`만 잡아 포장하고 **`CancellationException`은 포장하지 않는다**(바이트코드 예외 테이블로 확인). 취소는 실패의 하위 종류가 아니라 별개 범주라 `catch(RestClientException)`을 그대로 통과 → confirm의 `markFailed`가 건너뛰어져 주문이 READY로 남고 사용자에겐 500. **번역할 예외를 열거하는 방식 자체가 오류**라 "이 계층 밖으로는 도메인 예외만 나간다"로 접근을 바꿈. 상한 1ms에서 19/20 발현(30ms↑ 0/20) — 평소 통과하다 CI 부하에서만 터지던 이유
  - 테스트 169 통과(기존 134 + 신규 35). 서비스 12 / 동시성 11(동시 취소 2건→환불 1회 `@RepeatedTest`, 취소×웹훅) / **HTTP 계층 7**(서비스 테스트로는 한 줄도 안 도는 `@PreAuthorize`·`@Valid`·상태코드 매핑) / **토스 클라이언트 실 HTTP 4**(경로·바디 키·`Idempotency-Key` 실전송 — 목킹이 구조적으로 못 보는 계층, PR #25 선례)
  - **실 토스 E2E:** 결제→취소 관통. `canceled_at`→`cancel_confirmed_at` 약 1초 차이로 **실제 네트워크 왕복이 설계한 순서대로 일어났음**을 관측. DB·잔액 0·REFUND 이력·마이페이지 화면 전부 정합
  - **기존 테스트 결함 발견:** 기한 테스트가 SQL `INTERVAL ? DAY` 파라미터 바인딩을 써 backdate가 의도한 시점에 안 찍혔는데, "거부"를 기대하는 테스트라 **빗나가도 통과**했다 → 증거가 아니었음. Java에서 시각 계산·직접 바인딩 + 갱신 행 수 단정으로 교체

- **포인트 재원 3계층 분리** — PR #27 (`feature/point-bucket-separation`, **머지 완료 2026-08-19**, ADR-020)
  - ADR-019 self-review에서 발견한 **금전적 구멍** 봉쇄. 환불액이 `min(주문 잔여액, 계정 잔액)`인데 `balance`가 쿠폰분·현금분이 섞인 단일 정수라 `현금 충전 → 좌석 소진 → 쿠폰 상환 → 결제 취소` 순서로 **쿠폰이 현금으로 환전**됐다(좌석은 그대로 남음). 주문 잔여액 상한은 이걸 못 막는다 — 그 주문은 실제로 그 금액이었고 취소된 적도 없기 때문
  - **게임 재화 표준형 3계층** `EVENT`(기간제 쿠폰)/`FREE`(무기한 쿠폰)/`PAID`(현금). 소모 `EVENT→FREE→PAID`, 환불은 `PAID`에서만(`min(주문 잔여액, refundableBalance())`). 소모 순서가 양끝을 막는다 — 이벤트분을 나중에 쓰면 **사용자**가 만료로 손해, 유료를 먼저 쓰면 **서비스**가 환불 재원을 잃는다
  - **버킷이 진실, 총합은 파생:** `balance` 컬럼 삭제 → 3컬럼, `getBalance()`는 합계 게터. 총합에서 버킷은 복원 불가(유도 방향이 한쪽뿐)라 병행 저장은 이중 진실. 별도 테이블이 아닌 컬럼이라 **기존 계정 락 범위 무변경**
  - **C-2 좌석 확정:** `deduct(price)`가 `PointAllocation`을 반환하고 DEDUCT 이력에 배분을 남긴다. **락 순서(좌석→계정)·트랜잭션 경계 무변경**. 배분은 계정 락을 쥔 자리에서만 계산(read-modify-write라 락 밖으로 새면 동시 확정 둘이 같은 잔액을 각각 소진했다고 계산)
  - **⚠️ SETTLE 예외:** `point_transactions`가 예약자·제공자 원장을 겸직 중이라 `amount = 버킷합` 불변식을 전체에 걸면 `amount>0, 버킷합=0`인 SETTLE 행이 거부되고 **좌석 확정이 통째로 롤백**된다(= 예약 불가). 제공자에겐 재원 축이 없으므로 불변식은 예약자 측 타입(CHARGE/DEDUCT/REFUND)에만 적용. 계획 단계에서 발견해 첫 실행 전에 반영
  - **신규 `GET /api/points/balance`** — 그전까지 현재 잔액을 노출하는 엔드포인트가 **아예 없었다**(프론트도 결제 성공 페이지 한 줄뿐). `refundable`을 `paid`와 별도 필드로 내보내 정책 분기 시 결합을 미리 끊음
  - 프론트: 마이페이지 잔액 카드(합산 + accordion 3계층) + **체크아웃 소모 미리보기**(접힌 카드가 못 보여주는 규칙을 여기서 메운다). 배분 계산은 `lib/pointAllocation.js` 한 곳, "표시용 추정 — 확정은 서버가 락 안에서" 명시
  - **음성 대조군 선행:** `PointBucketRefundExploitTest`를 구현 전에 돌려 **실패를 확인**(취소가 성공하고 로그에 `이번 취소액=10000`이 찍힘)한 뒤 착수. 통과했다면 구멍을 재현 못 한 것이라 안전망이 아니다
  - 테스트 180 통과(기존 169 + 구멍 재현/무료 우선 2 + 배분 단위 9). **C-2 회귀 무수정 통과**(`ReservationConfirmConcurrencyTest`·`SeatReleaseConcurrencyTest`)를 1차 안전 신호로 사용
  - **기존 테스트에서 발견한 결함:** `PaymentCancelServiceTest`의 부분취소 테스트가 주석에 `"(쿠폰 등)"`이라 적으며 **구멍을 정상 동작으로 단정**하고 있었다(PR #26 기한 테스트와 동종). 재원을 현금으로 바꾸고 의도를 주석에 명시
  - **실 토스 E2E 관통:** 쿠폰 8,000 → 좌석 확정 → 현금 10,000 충전 → 좌석 확정 → 취소.
    최종 원장에서 `DEDUCT: free 3,000 + paid 2,000`(**계층 경계 관통**)과 `REFUND: paid 8,000` 확인 —
    주문은 10,000이지만 2,000은 이미 좌석으로 나가 **유료 잔액 8,000이 상한**이 됐다(`PARTIAL_CANCELED`).
    좌석 둘 다 CONFIRMED 유지 = "환불받고 좌석도 갖는" 상태 없음. `canceled_at`→`cancel_confirmed_at`
    1.3초 차로 ADR-019 보상 순서도 재확인. SETTLE 3건이 버킷합 0으로 정상 저장돼 **예외 조항 실증**
  - **기간제 포인트(EVENT)는 명목상 유지 — 확장 보류(작성자 결정).** 소멸을 넣으면 lot 추적이 강제된다
    (정수 하나로는 만료일별 금액을 못 담음) → `point_lots` + 만료임박순 소모 + **C-2 재수정** +
    원장 정규화 압력까지 따라와 이번 분리보다 큰 작업. 발급 수단(`Coupon` 기한 필드·ADMIN 경로)조차 없음.
    ⚠️ **용어:** 예약 대상 `Event` 엔티티와 무관하며, 좌석 홀드/토큰 만료(점유 해제)와 달리 **가치 소멸**이다
  - ⚠️ **미완:** ADR-020 결론부 작성자 검토. 역방향 대사는 후속(PR-B)

- **제공자 정산액 lost update 차단** — PR #28 (`fix/provider-settlement-lost-update`, **머지 완료 2026-08-21**)
  - ADR-020 결론부 검토 중 발견. 확정 경로가 제공자 계정을 **락도 `@Version`도 없이** read-modify-write 하고 있었다. 좌석 락(다른 행)·예약자 계정 락(다른 행)은 이들을 직렬화하지 못하고, JPA 더티 체킹은 절대값(`SET settlement_balance = ?`)을 써서 적립이 서로 덮어써진다
  - **재현 선행:** `ProviderSettlementConcurrencyTest` — 서로 다른 예약자 × 같은 제공자 × 다른 좌석 10건 동시 확정. **SETTLE 이력은 10행 정상인데 잔액은 5,000**(50,000이어야 함). 10건 중 9건 유실, 3회 반복 전부 실패. **이력은 INSERT라 덮어쓸 게 없고 잔액은 UPDATE라 덮어써진다** — 같은 사실을 두 곳에 저장하며 갱신 방식이 달라 갈라진 것
  - **인기 이벤트의 정상 트래픽이 정확히 이 모양**이다(한 제공자의 좌석 여러 개를 여러 사람이 동시 구매). 기존 동시성 테스트 2건은 **같은 좌석 경합**만 봐서 이 축이 비어 있었다
  - `findByUserIdForUpdate` 추가. **락 순서 규약 확장: 좌석 → 예약자 계정 → 제공자 계정**(제공자를 잠그는 경로는 확정뿐이라 순환 대기 없음). 락 획득을 **트랜잭션 맨 마지막으로 이동** — 락은 획득부터 커밋까지 유지되므로 언제 잡느냐가 곧 보유 시간
  - ⚠️ **남은 한계(의도적):** 같은 제공자의 판매가 그 행에서 **직렬화**된다. 상한을 없애려면 정산 잔액을 원장에서 파생시켜야 하며(ADR-020 "버킷이 진실, 총합은 파생"을 제공자 측에 적용), **측정 후 판단**으로 미룸 → `docs/performance/provider-settlement-throughput.md`(측정 설계·DB vs Redis 평가·스냅샷 재설계안)
  - 테스트 183 통과 (기존 180 + 신규 3)

- **환불 차감 시그니처 좁히기** — PR #29 (`refactor/deduct-refundable`, **머지 완료 2026-08-21**)
  - `deductFrom(int, PointBucket)`은 EVENT/FREE도 받았지만 **호출은 처음부터 PAID뿐**이었다(프로덕션 1곳·테스트 1곳). 쓰지 않는 유연성이고, 그 유연성이 열어두는 것이 하필 ADR-020이 막은 구멍 — `deductFrom(amount, FREE)`가 문법적으로 가능했다
  - `deductRefundable(int)`로 좁혀 **"환불은 유료 재원에서만"을 런타임 조건이 아니라 타입으로 강제.** 잘못된 호출이 실행 시 실패하는 게 아니라 **작성 자체가 불가능**해진다(setter 없는 엔티티와 같은 기조). `refundableBalance()`와 짝을 이뤄 "상한을 묻고 그만큼 뺀다"로 읽힘
  - 3분기 `switch`와 `requireEnough` 헬퍼가 사라져 **16줄 추가 / 26줄 삭제**(순감소). 동작 변경 0, 테스트 180 통과
  - ⚠️ `charge(int, PointBucket)`는 **좁히지 않았다** — 적립은 경로마다 재원이 다르고(쿠폰→FREE, 결제→PAID, 향후 EVENT) 세 값 모두 정당한 호출이 있다. 차감 쪽은 한 값뿐이라 인자가 표현력이 아니라 오용 여지였다

- **F-3 포트폴리오 문서 마감** — main 직커밋 (**완료 2026-08-21**)
  - **README 전면 개편.** 기존 README는 **2026-06-07에 멈춰 있었다** — 대기열·결제·포인트 버킷·N+1·부하측정·프론트가 전부 누락, ADR 목록은 4개까지만(실제 20), 실행 방법은 "구현 진행 후 업데이트 예정"
  - 핵심 작업 5개를 `domain→problem→solution→result`로 정리: ①좌석 동시성 ②N+1→집계(**유일하게 측정 수치가 뒷받침**) ③대기열 ④결제 삼중화+보상 트랜잭션 ⑤**self-review가 찾은 금전 구멍 2건**(음성 대조군 방법론). mermaid 2층 아키텍처, 법적 맥락 표, 실행 방법, 검증된 수치(테스트 183·ADR 20·PR 28)
  - **⚠️ 톤 조정(작성자 지적):** 대기열 절을 "필요성 미입증"으로 쓰니 **기능이 미완인 것처럼** 읽혔다. 커넥션 풀 고갈은 교과서적 실패 모드이고 실측 불가는 1인 환경 제약이므로, **"알려진 실패 모드에 대한 예방 설계"** + "확인한 것/확인하지 못한 것" 구조로 전환. 미확보는 기능 타당성이 아니라 **파라미터 근거**(입장 상한·rate는 추정)로 좁혔다. **직접 발견한 결함(5절)에서는 정직 어조가 강점이지만 환경 제약에까지 적용하면 자기 저평가가 된다**
  - **ADR 인덱스 신설**(`docs/adr/README.md`) — 20종을 주제별로 묶고 각각 *결정 / 기각한 대안* 병기. 핵심은 **결정 간 관계도**: `004→013→개정`, `018→019→(self-review)→020`, `008 기조 계승→010·018·019·020`. **문서화 작업이 결함을 드러낸 흐름**(020이 019 검토에서, #28이 020 결론부 검토에서)을 명시
  - **문구 중립화:** `면접 포인트`→`기술적 초점`, `포트폴리오 타겟`→`관심 영역`, docs 가이드의 `채용 담당자/면접관이 볼` 제거. 공개 문서에서 "면접" 표현 전부 삭제
  - **면접 스토리 2종은 범위 밖으로 이동**(작성자 결정) — 여러 프로젝트를 놓고 축을 배분하는 문제라 **통합 포트폴리오 단계**에서. 마스터플랜 F-3에 취소선+사유 기록. 재료(ADR 20종·측정 문서·README 5절)는 저장소에 남아 있어 미루는 비용 없음

- **역방향 대사 + 환불 상태 가시화** — PR #30 (`feature/payment-cancel-reconciliation`, **머지 완료 2026-08-21**, ADR-019 절 추가)
  - ADR-019 보상 순서가 남긴 크래시 창(①포인트 회수 커밋 후 ②토스 취소 호출 전 사망 → **포인트만 회수되고 현금은 안 돌아감**)을 배치가 밖에서 메운다. PR #27 E2E에서 두 시각이 1.3초 벌어지는 것을 실측 — 그 사이가 창이다
  - **⚠️ 문서화됐던 후보 조건 `cancel_confirmed_at IS NULL`은 불완전했다.** `markCanceled`가 `canceled_at`을, `confirmCancel`이 `cancel_confirmed_at`을 매번 덮어쓰므로 **부분 취소 2회차 크래시**는 1회차 확인 시각이 남아 non-null → 후보에서 빠진다(막으려던 상태가 배치에 안 보임). 두 시각을 **비교**하는 조건으로 교체. `<`가 아니라 `<=`인 것은 **위험의 비대칭** — 오탐은 멱등 키로 무해, 누락은 영구적 금전 손실
  - **재시도 금액을 로컬에서 복원할 수 없다** (저장된 건 누적액뿐, 이번 시도분은 어디에도 없음) → 대사답게 **토스에 물어서 차액**을 취소한다. `TossPaymentResult`에 `balanceAmount` 추가. `delta=0`이면 "②는 성공, ③만 미커밋"이므로 **재호출 없이 확인 기록만**, `delta<0`(토스가 더 취소)은 포인트 추가 회수라 **자동 판단 대상 아님 → ERROR 로그 + skip**
  - **되돌릴 권한은 새벽 잡에만** (재시도 5분 / 포기 04:20 KST). revert는 "현금 환불 포기하고 포인트로 돌려준다"는 금전적 결정이라, 자주 도는 잡이 가지면 **토스 일시 장애가 곧바로 환불 포기**가 된다 — 정방향 대사의 만료 권한 분리와 같은 방어. **revert는 `delta>0`일 때만** (실제 환불된 건에 포인트까지 주면 돈과 포인트를 둘 다 갖는다)
  - **대사가 스스로 크래시 창을 만들지 않게 — 누적액 가드:** 대사는 락 없이 읽고 → 트랜잭션 밖에서 토스 호출 → 마지막에 기록한다. 그 사이 사용자가 새 부분 취소를 하면, 그대로 스탬프를 찍는 순간 **아직 토스에 가지도 않은 취소가 "확인됨"이 되어 영영 발견되지 않는다.** `confirmCancelIfUnchanged`/`revertCancelIfUnchanged`로 차단 — **가드를 끄면 동시성 테스트 5/5 실패**(음성 대조군 확인)
  - **사용자 가시화(작성자 지적으로 범위 확대):** 취소 ①이 커밋되면 포인트 이력에 `REFUND`가 즉시 찍히고 잔액도 줄어 **화면이 "환불됐다"고 거짓말**한다. 게다가 그전까지 결제 상태 조회 엔드포인트가 **아예 없었고**, 유일한 창구인 취소 API 동기 응답은 **정의상 이 창에서는 도달하지 못한 그 응답**이라 알릴 수단이 없었다 → `GET /api/payments/my` + 파생 `refundStatus` + 마이페이지 `결제·환불` 탭. **"환불 처리 중"(아직 안 나감)과 "환불 완료"(카드사 반영 대기)를 분리** — 합치면 다시 거짓말
  - 테스트 207 통과(기존 183 + 24). 대사 서비스 12(차액 3분기·권한 분리·부분취소 2회차 후보 판정 **음성 대조군은 옛 `IS NULL` 조건을 실제로 실행해 0건임을 단정**) + 동시성 5(`@RepeatedTest`) + HTTP 6 + 클라이언트 실 HTTP 1(`balanceAmount` 매핑 — 목킹이 구조적으로 못 보는 계층, PR #25 선례)
  - **실 토스 관통 E2E 완료(2026-08-21):** 실제 카드 결제 10,000원 → **DB에서만 취소 기록**(포인트 회수 + REFUND + `canceled_at`, 토스엔 미전송 = ①만 커밋되고 죽은 상태 재현) → 이때 **포인트 이력엔 `환불 −10,000P`가 찍혀 있는데 토스는 `balanceAmount: 10000`**(돈 안 나감)인 거짓말 상태를 실측 → 결제·환불 탭은 **"환불 처리 중"** 표시 → 5분 뒤 재시도 잡 자동 발화(19:01:08) → **실제 토스에 취소 전송**. 토스 `cancels[0].cancelReason = "환불 재시도 (역방향 대사)"`로 **이 배치가 보낸 것임이 토스 측 기록으로 확인됨**, `balanceAmount 10000→0`, `status CANCELED`. 우리 DB `cancel_confirmed_at` 기록, 화면 **"환불 완료"** 전환. **포인트·이력 추가 변동 0**(이중 환불·헛 적립 없음)
  - ⚠️ **남은 한계:** 포기(revert) 후 사용자에게 사유가 안 보이는 것 — `PointTransaction↔PaymentOrder` 연결 부재와 같은 뿌리라 별도 건. `delta<0`은 자동 복구 안 함(로그만)

- **Phase D-3: 제공자 정산 처리량 측정** — PR #31 (`feature/settlement-throughput-measure`, **PR 오픈 2026-08-22**)
  - #28이 lost update를 락으로 막으며 남긴 "같은 제공자의 판매가 계정 행 하나에서 직렬화" 상한이 **실제 병목인지** 판정. **상한 존재 ≠ 병목**이라 재설계 전에 측정이 먼저였다
  - **측정 대상을 knee point → 형태로 전환(작성자 지적).** 부하생성기를 분리할 수 없는 환경이고 D-2가 그 조건에서 실패했다. 더 근본적으로 **한 PC의 절대 처리량은 그 PC에서만 참이라 판단 입력이 못 된다** → 제공자 수(1/2/4/8)를 대조 변수로 두면 두 실험군이 같은 오염을 공유해 **비율에서 상쇄**된다. 자릿수는 부하를 밀어서가 아니라 **1/락보유시간**에서 얻는다
  - **인프로세스 하네스**(JUnit+Testcontainers, `SettlementThroughputBenchmark`, 프로덕션 무변경): `confirmReservation`을 스레드풀 32로 직접 호출 → **부하생성기가 원리적으로 사라져** coordinated omission이 성립하지 않는다. k6 불필요 = *인프라 의존 해소*. 판정 기준·요구 기준선(≈100건/초)은 **측정 전에 문서에 고정**(ADR-020 음성 대조군 기조)
  - **판정: 병목이 맞다.** `1/락보유시간`으로 유도한 상한(91/83건per초)과 실측 P=1 처리량(88.1/81.2)이 **오차 4% 안에서 일치** — 부하를 민 값이 아니라 다른 경로로 계산한 값이 실측과 만나 **원인 귀속이 닫혔다**(D-2에 없던 내부 지표). P=1에서 지연의 **93%가 락 대기**이고 P가 커질수록 줄어드는 건 대기뿐(303→27ms). 제공자당 ≈85건/초로 요구 기준선 미달, 박스 용량(250~350)의 1/3~1/4
  - **⚠️ 설계 단계에서 못 본 것 — 비교군 ②는 재설계 상한이 아니었다:** 잠금 조회만 없애도 `UPDATE settlement_balance`가 **플러시 시점에 같은 행을 다시 X락으로 직렬화**해 1.2~1.4x에 그친다. 락 보유 11ms는 대부분 **커밋 비용**이므로(락을 트랜잭션 맨 마지막에 잡음) **트랜잭션 안에서 그 행을 건드리는 어떤 설계든 커밋 시간만큼 직렬화를 문다** → 원자적 `SET balance = balance + ?` 같은 값싼 대안 탈락, **쓰기를 없애는 것만이 탈출구**. 재설계의 대상이 "잠금 조회"에서 "그 행에 대한 쓰기"로 바뀜
  - 정직하게 남긴 것: `R`은 회차 간 3.13~3.99로 흔들려 기준표의 `≥4`는 1회차만 충족(결론은 배수가 아니라 상한·실측 일치에 의존) / `P=8`이 선형 미달인 건 행 경합이 풀리며 이 PC가 한계에 든 것(보유 12→30ms) / **확정 트랜잭션 처리량이지 API 처리량이 아니다** / 단일 인스턴스 전제
  - **하네스 함정 3종(전부 측정 코드 결함, 제품 무관):** ①출발선 래치를 작업 수(240)로 잡아 스레드 32와 어긋나 **영구 대기**(29분 무출력) ②`invocation.callRealMethod()`는 Spring Data가 **구현 없는 인터페이스 메서드**라 불가 → 240건 전량 실패 ③`getSpiedInstance()`는 `@MockitoSpyBean`이 원본을 default answer로 감싸 **null** → 결국 같은 JPQL·같은 락 모드를 `EntityManager`로 직접 실행. **②는 catch가 예외를 삼켜 "0건 성공"만 남아 원인을 잃었다** — 측정 코드에서도 실패를 삼키면 안 된다
  - 계측 오류도 하나 고쳤다: 락 보유를 `afterCompletion`에서 찍으면 확정 경로의 **Redis 토큰 회수(`afterCommit`)가 보유 시간에 섞인다** → 등록 순서를 이용해 `afterCommit` 앞에서 시각을 찍도록 수정(첫 실행의 "상한 4건/초"는 그래서 나온 값이라 폐기)
  - 전체 207건 무변동 통과. 벤치마크는 클래스명이 `...Benchmark`라 surefire 기본 패턴에 안 걸려 **CI 자동 실행 없음**(pom 무수정). 상세: `docs/performance/provider-settlement-throughput.md` 7절
  - **다음:** 파생 재설계가 열렸으나 문서 3절은 스케치라 **별도 계획**(스냅샷 분리 여부 + 재설계 후 같은 하네스로 재측정하는 검증 설계). 재설계까지 가면 D-1(N+1)에 이어 **측정이 설계를 바꾼 두 번째 사례**

### 현재 상태
- **작업 브랜치:** `feature/settlement-throughput-measure` (**PR #31 오픈, 리뷰 대기**)
- **마지막 main 커밋:** `Merge pull request #30` (be4abfe)
  - PR #1~11, #13~30 머지 완료
  - **E-2 결제 완료:** 백엔드(#22, 07-30, ADR-018) + 프론트 충전 UI(#23, 08-03) + 대사·실 토스 E2E(#24·#25, 08-04) + **취소·환불(#26, 08-19, ADR-019)**.
    정합성 축(멱등·위변조·트랜잭션 경계·타임아웃) + 삼중화(동기·웹훅·대사) + **생애주기 축(취소·환불 보상 트랜잭션)** + **역방향 대사·환불 가시화(#30, 08-21)**까지 완료.
    **실 토스 결제·취소 양방향 + 크래시 창 자동 복구까지 관통 확인**(테스트 키). 알려진 미복구 경로 없음
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
  - **GET /api/points/balance** - 보유 포인트 합산 + 재원 계층 분해(event/free/paid/refundable) (BOOKER) ★ ADR-020
  - **GET /api/providers/my/settlement** - 정산 잔액 + SETTLE 거래 목록 (PROVIDER) ★ E-3
  - **POST /api/queue/{eventId}/enter** - 대기열 진입(순번 반환) (BOOKER) ★ E-1
  - **GET /api/queue/{eventId}/status** - 순번·대기 인원·입장 여부·nextPollDelayMs (BOOKER) ★ E-1
  - **POST /api/queue/{eventId}/leave** - 대기열 이탈(순번+토큰 회수, 멱등) (BOOKER) ★ E-1
  - **POST /api/payments/orders** - 결제 주문 생성(orderId 발급 + 금액 서버 확정 저장) (BOOKER) ★ E-2
  - **POST /api/payments/confirm** - 동기 승인 → 포인트 적립(멱등) (BOOKER) ★ E-2
  - **POST /api/payments/webhook** - 토스 웹훅 보조 경로(permitAll, 재조회 검증) ★ E-2
  - **POST /api/payments/{orderId}/cancel** - 결제 취소·환불(미사용분 부분 취소, 7일 이내, 멱등) (BOOKER 본인) ★ E-2
  - **GET /api/payments/my** - 내 결제·환불 내역 + `refundStatus`(NONE/PENDING/COMPLETED 파생) (BOOKER) ★ 역방향 대사
  - POST /api/loadtest/coupons - 쿠폰 생성 (**loadtest 프로파일 전용**, 운영 404)
- **프론트엔드:** frontend/ (React+Vite+Tailwind). `cd frontend && npm install && npm run dev` → :5173. CORS는 WebConfig가 :5173 허용. 결제 페이지 3종(`/points/charge`, `/payments/success`, `/payments/fail`)은 PR #23 ★ E-2

### 다음 작업 (우선순위 순)
> #26이 드러낸 금전적 구멍은 #27로, ADR-020 검토 중 발견한 정산액 lost update는 #28로,
> 취소의 크래시 창은 #30(역방향 대사)으로 봉쇄됐다.
> **알려진 정합성 결함·미복구 경로가 현재 없다.** 어필 체크리스트 11개도 F-3로 전부 확보됐다.
> 남은 것은 측정과 미착수 Phase다.
1. ~~**역방향 대사 (PR-B)**~~ → **PR #30 머지 완료 2026-08-21.** 테스트 207 + 실 토스 관통 E2E(크래시 창 재현 → 재시도 잡이 실제 취소 재전송 → 토스 `cancels` 기록으로 확인). **이로써 결제 생애주기에 알려진 미복구 경로가 없다.**
2. ~~**F-3 포트폴리오 문서 마감**~~ → **완료 2026-08-21.** README 전면 개편 + ADR 인덱스. 면접 스토리는 통합 포트폴리오 단계로 이동. **마스터플랜 어필 체크리스트 11개 전부 확보됨**(마지막 미확보였던 "정산의 법적 맥락·README" 해소).
3. ~~**제공자 정산 처리량 측정**~~ → **PR #31 오픈 2026-08-22.** **병목으로 판정**(1/락보유시간 ≈ 실측 P=1 처리량, 오차 4%). 남은 것은 **파생 재설계 계획** — 다만 없앨 대상은 잠금 조회가 아니라 **그 행에 대한 쓰기**임이 측정으로 드러났다(비교군 ②가 1.2~1.4x에 그침). 상세: `docs/performance/provider-settlement-throughput.md` 7절
4. **E-4 분산 환경 동시성**(분산 락) — 마스터플랜 미착수 Phase. 멀티 인스턴스+LB 전제라 *인프라 의존*.
5. **F-1 CD**(AWS EC2 배포) — CI는 #12로 완료, 배포는 미착수. 마스터플랜상 "시간 여유 시".
6. 나머지는 결제/대기열/기타 백로그 — 실측 계열은 인프라 의존. 프론트 401 인터셉터(~10줄)는 기타 백로그.
- (선택) 토스 **회원가입 → 본인 테스트 키** 교체: 현재는 공용 문서 키라 '테스트 결제내역'이 내 계정에 안 묶인다. 역방향 대사·가상계좌 실험에 필요.

### 백로그 — 대기열 (우선순위 순)
> PR #14에서 E-1 입장 제어를 다듬으며 식별. 지금은 단일 인스턴스 전제로 충분. (~~confirm 토큰 반환~~ #16, ~~이탈 세션 회수~~ #17, ~~기아 방지 균등 분배~~ #20, ~~이탈 안내 문구~~ #21 완료)
1. **이벤트별 가중치(WRR/DRR) + 저부하 프리패스** — 균등 분배(#20)의 quantum 값만 가중치로 바꾸면 구조 변경 없이 확장되는 설계. 사업적 등급(대형/소형) 요구가 생기면 착수. 프리패스는 분배 안전망 위에서 재검토(PR #14에서 시기상조 제거).
2. **대기열 필요성 실측** — D-2는 생성기 병목으로 미입증. 부하생성기 별도 머신 + 핫좌석 경합 부하로 지속 과부하 구간 측정. *인프라 의존(별도 머신).*
3. **enter/status 캐싱 효과 실측** — PR #15(ADR-014)는 효과 미측정. 캐시 on/off로 existsById 쿼리 수·커넥션 점유 비교(생성기 분리 필요). *2번과 함께 묶을 수 있음.*
4. **대기실 UX 신호(정체 가시화)** — ceiling 가득+회전 없음이면 순번이 수 분간 정지해 고장과 구분 불가(최대 5분 유한 — 토큰 TTL 상한). 순번 대신 **이벤트별 최근 실제 입장 속도 기반 ETA**(현 `nextPollDelayMs`의 앞인원/rate 계산은 정체 시 거짓말) + status에 정체 플래그 → 프론트 문구 분기. PR #20 설계 논의에서 식별, 범위 제외로 분리.

### 백로그 — 결제(E-2) 미구현 영역
> PR #22 회고에서 식별. 정합성 축은 정석대로 채웠고 **대사·고아 정리는 #24·#25, 취소·환불은 #26으로 해소**.
> (~~1. 취소·환불~~ #26, ~~2. 대사~~ #24, ~~4. 고아 주문 정리~~ #24 완료)
1. ~~**환불 재원 구분 없음**~~ — ADR-020(3계층 버킷)으로 해소. 다만 `PointTransaction`에 `PaymentOrder` 연결이 없다는 **구조적 공백은 그대로**다(충전 건별 귀속 = FIFO lot 추적은 범위 밖). 현금↔현금이라 손실은 없다.
2. ~~**역방향 대사**~~ — **구현 완료 2026-08-21**(ADR-019 절 추가). 후보 조건은 `IS NULL`이 아니라 `canceled_at`/`cancel_confirmed_at` **두 시각 비교**였다(부분취소 2회차 크래시를 놓침). 재시도 금액은 토스 `balanceAmount`로 차액 산출.
3. **비동기 결제수단 미지원(가상계좌 등)** — 상태머신이 `READY→DONE/FAILED/EXPIRED`(+`DONE→PARTIAL_CANCELED/CANCELED`)라 "입금 대기"(며칠 지속) 중간 상태를 표현 못 함. **웹훅이 진짜 필요해지는 대표 사례가 이것**인데 현재 웹훅은 confirm 누락 보정 용도로만 쓰인다. 프론트도 `requestPayment('카드')` 고정. 토스 테스트 결제내역의 '입금처리' 버튼으로 검증 가능.
4. **웹훅 서명 검증** — 현재는 재조회 검증까지(ADR-018 한계에 명시). 서명/IP 화이트리스트는 미적용. 보안 키는 이미 발급받아 둠.
5. **충전 한도·감사 로그·영수증** 없음. 포인트 충전은 규제상 단순 결제와 다르게 취급될 수 있음(선불 성격의 자금 보관 → 이용자 자금 보호 의무 등) — **실제 요건 확인 필요**. 프로젝트 어필 축인 "정산의 법적 맥락"과 직결되는 도메인 숙제.
6. **SDK v1 → v2 마이그레이션** — 프론트가 `js.tosspayments.com/v1/payment` 사용 중이고 토스는 v1 업데이트 중단을 공지. **백엔드·콜백은 무변경**(서버 REST API는 v1 그대로, 쿼리 파라미터 동일)이고 바뀌는 건 SDK 초기화·`requestPayment` 시그니처·`amount` 객체화 + `customerKey` 도입뿐이라 파일 2개 규모. 다만 v2의 주 매력(간편결제·결제위젯)이 **계약 없이는 대부분 잠겨 있어** 현재 이득이 거의 없다 → **트리거: 간편결제를 붙이거나 전자결제 계약을 진행할 때**.
7. (의도적 제외, 재검토 대상) 재시도·서킷브레이커·전용 스레드풀 격리 — 현 단계 과잉으로 판단해 타임아웃까지만 적용(ADR-018 5번).
8. **대사 batch-size vs 토스 API 제한** — 테스트 환경은 **분당 100건** 제한인데 `batch-size: 100`이 정확히 같다. 평상시 READY 잔여는 몇 건 수준이라 실제로 걸릴 일은 드물지만, 대량 누락 상황에선 한도를 소진할 수 있다. 값 하향 또는 건별 간격 검토.

### 백로그 — 기타
- **프론트 401 응답 인터셉터 없음 (인증 UX 갭):** `frontend/src/api/client.js`에 요청 인터셉터(토큰 부착)만 있고 **응답 인터셉터가 없다** → 서버가 401을 줘도 토큰 삭제·로그인 리다이렉트가 일어나지 않아 "화면은 로그인, 서버는 미인증" 상태가 유지된다. `AuthContext`가 `exp`를 로컬 검사하긴 하지만 **마운트 시점에만** 돌고, 만료와 무관한 거부 사유는 잡지 못한다. 재현되는 실제 경로: ①페이지를 열어둔 채 토큰 만료 ②soft delete된 사용자(`findByEmailAndDeletedAtIsNull`가 걸러냄) ③JWT 시크릿 교체. 2026-08-19 버킷 PR E2E 중 발견 — `ddl-auto: create`로 DB가 재생성되자 유효기간이 남은 토큰이 401을 받는데도 화면은 로그인 상태로 남았다(실측: 사라진 계정 토큰 → 401, 살아있는 계정 → 200). 응답 인터셉터 ~10줄로 세 경로 모두 해소. *인증 계층이라 포인트 버킷 PR 범위 밖으로 분리.*
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