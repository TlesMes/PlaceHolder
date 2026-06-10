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

## 현재 진행 상황 (2026.06.10 기준)

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

- **포인트 충전 (쿠폰 상환)** — PR #5 (`feature/coupon-redeem`, 리뷰 대기)
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

- **Phase E-3: 프론트엔드 + 테마** — PR #9 (`feature/e3-frontend-ui`, 리뷰 대기)
  - 마이페이지(`/me`, BOOKER): 예약 내역 + 포인트 이력(타입별 색·기간/타입 필터·"더 보기" 시안) 탭 전환
  - 정산 대시보드(`/provider/settlement`, PROVIDER): 잔액 카드 + SETTLE 테이블
  - ProtectedRoute에 `requiredRole` 추가(역할 보호). **UI/UX 단계 — 모의 데이터 하드코딩**, 객체 형태만 백엔드 DTO와 1:1(API 연결은 다음)
  - 다크/라이트 테마: 의미 기반 색상 토큰(CSS 변수, `darkMode:'class'`) + 토글(localStorage 영속, FOUC 방지). 기존 색 하드코딩 전량 토큰화

### 현재 상태
- **작업 브랜치:** `feature/e3-frontend-ui` (PR #9 리뷰 대기)
- **마지막 main 커밋:** `docs: 마스터 플랜에서 기간 표기 제거` (69ccc0a)
  - PR #1~6, #8 머지 완료. #5(쿠폰)·#9(프론트/테마)는 리뷰 대기
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
- **프론트엔드:** frontend/ (React+Vite+Tailwind). `cd frontend && npm install && npm run dev` → :5173. CORS는 WebConfig가 :5173 허용.

### 다음 작업 (우선순위 순)
1. **E-3 프론트엔드 API 연결**: PR #9의 모의 데이터를 실제 API로 교체(`api/` 모듈, cursor 페이징 실동작, 기간/타입 필터 서버 반영)
2. **E-3 테스트 PR**: MyReservationsServiceTest / PointHistoryServiceTest / ProviderSettlementServiceTest. Docker 가능 환경에서 별도 진행
3. **Phase D-2**: hold/confirm knee point 측정 (별도 브랜치 `feature/loadtest-hold-confirm-knee`, draft PR #7 재개). 1차 측정 결과 5xx 0 → abort/knee 기준 재정의 결정 대기 중. 인수인계: `loadtest/HANDOFF-D2.md`

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
- **프론트엔드 구조:** `frontend/src/` — pages(Login/Signup/EventList/EventDetail/Checkout/MyPage/Settlement), components(SeatGrid/SeatCell/Header/Layout/ThemeToggle 등), context(Auth/Toast/Theme), hooks(useSeatPolling), lib(jwt/errors/format/seatStyle/theme), api(client/auth/events/seats). 폴링은 useSeatPolling 훅 경계로 분리(추후 SSE 교체점).
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