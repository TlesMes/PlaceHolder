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

## 현재 진행 상황 (2026.06.14 기준)

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

- **Phase D-2: hold knee point 측정** — PR #7 (`feature/loadtest-hold-confirm-knee`, **측정 종료**)
  - 방법: open-loop `ramping-arrival-rate`로 도착률을 올리며 **성공 지연 p99 임계 초과**를 knee로 abort(A안). 좌석 7.5만(재고 소진 방지) + 풀 크기 `HIKARI_POOL` env 가변
  - **풀 스윕 5/10/20 결과(이 박스 기준):** knee가 5→10 2.2배(풀-바운드) → 10→20 1.06배(플래토). 풀 5는 CPU 65%인데 꺾임=커넥션 부족. → **병목은 커넥션 풀이 아니라 ~풀10에서 박스 CPU**(단일 박스 app+MySQL+k6 공유, ~880/s). 적정 풀 ≈ 10(기본값), **튜닝 불필요**
  - **결론:** 시스템은 5xx 없이 **지연으로 graceful degrade** → 대기열(E-1)은 크래시 방지 아니라 **지연 SLO 보호**용. ⚠️ 분산 설계라 측정한 건 **인프라 처리량**이지 "경합 하 락 설계"가 아님(경합 정확성은 C-4가 증명). **confirm 미실행**(동일 양상 예상). 상세·정직한 한계: `loadtest/HANDOFF-D2.md`
  - 부산물: 측정이 가설을 3회 교정(풀=병목→아니다→10미만에선 맞다). **실질 perf 성과는 D-1(N+1)**, D-2는 보조 결론
  - 쿠폰 생성 API(`POST /api/loadtest/coupons`, `@Profile("loadtest")` 전용): 운영엔 빈 없어 404

### 현재 상태
- **작업 브랜치:** `feature/loadtest-hold-confirm-knee` (Draft PR #7, main 동기화 완료 — PR #10·#11 반영)
- **마지막 main 커밋:** `Merge pull request #11` (56480d5)
  - PR #1~6, #8, #5, #9, #10, #11 머지 완료. #7(D-2)은 측정 종료 → 정식 PR 전환 대기
  - E-3(조회 API + 프론트 연결 + 부호 버그 수정 + 서비스 테스트 16종)까지 완료
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
  - POST /api/loadtest/coupons - 쿠폰 생성 (**loadtest 프로파일 전용**, 운영 404)
- **프론트엔드:** frontend/ (React+Vite+Tailwind). `cd frontend && npm install && npm run dev` → :5173. CORS는 WebConfig가 :5173 허용.

### 다음 작업 (우선순위 순)
1. **PR #7 마무리:** D-2 측정 종료 → 스크립트 보정·결과 커밋됨. Draft #7을 정식 PR로 전환·머지 판단(인간). hold만 측정, confirm 미실행(결론상 보류)
2. **(선택) 경합 시나리오 측정:** D-2는 분산 설계라 "인프라 처리량"만 봄. 락 설계 자체를 보려면 핫좌석 경합 부하가 별도 필요 — 백로그
3. **정산 조회 cursor 페이징(측정 선행)**: `docs/performance/settlement-query-scalability.md` 백로그 — 정산 건수 증가 응답 곡선 측정 후 도입 판단
4. **(병렬 진행 중) CI 파이프라인:** GitHub Actions build+test — 별도 세션/브랜치 `feature/ci-github-actions`

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