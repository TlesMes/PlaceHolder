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

## 현재 진행 상황 (2026.06.09 기준)

### 완료된 작업 ✅
- **Phase A:** 설계 완료
  - 7개 엔티티 구현 (User, BookerAccount, ProviderAccount, Event, Seat, Reservation, PointTransaction)
  - ERD, 테이블 정의, 초기 ADR

- **Phase B-1:** Repository 계층
  - 7개 JpaRepository 인터페이스 구현
  - 커스텀 쿼리 메서드 정의 (findByEventId, findByStatusAndHeldUntilBefore 등)

- **Phase B-4:** 기본 CRUD API
  - Exception 인프라 (GlobalExceptionHandler, ErrorResponse, 커스텀 예외 4개)
  - DTO 레이어 (Event 4개, Seat 1개)
  - Service 레이어 (EventService, SeatService)
  - Controller 레이어 (EventController - 4개 엔드포인트)
  - SecurityConfig (임시 - 모든 요청 허용)
  - application.yml batch insert 설정
  - **API 테스트 완료:** 이벤트 등록/조회 정상 동작

- **Phase B-3:** 회원가입 트랜잭션
  - User + BookerAccount/ProviderAccount 단일 트랜잭션 생성
  - SignupRequest/Response DTO
  - 역할 검증 fail-fast (지원하지 않는 역할 → InvalidUserRoleException)

- **Phase B-2:** JWT 인증/인가
  - 로그인 API (JWT 발급), JwtProvider/JwtAuthenticationFilter
  - SecurityConfig 교체 (providerId 파라미터 제거 → SecurityContext)
  - 역할 기반 접근 제어 (@PreAuthorize + @EnableMethodSecurity)

- **보안/테스트 개선** (Phase B 후속)
  - soft delete 쿼리 필터링 (findBy...AndDeletedAtIsNull) 적용
  - 401/403 의미 분리 (JwtAuthenticationEntryPoint → 인증 실패 401, @PreAuthorize → 인가 실패 403)
  - 인증 DTO @Builder 적용 (테스트 리플렉션 제거)
  - 테스트: AuthServiceTest, SignupTransactionTest, AccessControlTest, UserRepositoryTest

- **Phase C-1:** 좌석 홀드 (비관적 락) — PR #1
  - SELECT ... FOR UPDATE(findByIdForUpdate)로 좌석 행 잠금 → 동시 요청 시 한 명만 성공
  - status AVAILABLE → HELD, held_by/held_until(TTL 5분) 설정
  - lazy 만료 정책(ADR-008): held_until 경과한 HELD는 재점유 가능
  - POST /api/seats/{seatId}/hold (BOOKER), 동시성 테스트 포함

- **Phase C-2:** 예약 확정 (트랜잭션 원자성) — PR #2
  - 좌석·BookerAccount 비관적 락 → [포인트 차감 + 제공자 정산 적립 + 좌석 CONFIRMED + Reservation + PointTransaction 2건] 단일 트랜잭션
  - 잔액 부족 시 InsufficientPointException으로 전체 롤백
  - POST /api/seats/{seatId}/confirm (BOOKER)

- **Phase F-2:** BOOKER 좌석 예약 프론트엔드 — PR #3
  - React + Vite + TailwindCSS. 동시성(홀드/확정)을 시각적으로 검증하는 용도
  - 좌석 그리드(AVAILABLE/HELD/CONFIRMED 3색), useSeatPolling 2.5초 폴링 실시간 갱신
  - 홀드 → 결제 페이지(URL 이동) → 결제수단·예약자정보 입력 → 확정 흐름
  - 만료된 HELD를 AVAILABLE로 취급(effectiveStatus, 서버 lazy 만료와 일치)
  - 백엔드: SeatResponse.SeatInfo에 heldUntil 추가 (프론트 만료 시각 인식용)

- **Phase C-3:** 좌석 홀드 자동 만료 해제 (스케줄러 폴링) — PR #4
  - SeatExpiryScheduler(@Scheduled, fixedDelay)가 SeatExpiryService(@Transactional)에 위임 (self-invocation 함정 회피)
  - 후보 ID를 락 없이 조회(findExpiredHeldSeatIds, ID projection) → 행별 findByIdForUpdate로 개별 재잠금 → 락 보유 상태에서 만료 재확인 → Seat.release()로 AVAILABLE 전이 (TOCTOU 방어)
  - 기존 lazy 만료(isHoldable)는 스캔 주기 빈틈 보정 안전망으로 유지
  - 만료 해제 전략 결정: ADR-009 (스케줄러 폴링 + lazy 안전망 병행). 폴링 부하/배치 분할은 Phase D 측정 후 조정
  - scan-interval-ms 기본 60초(application.yml), 테스트는 사실상 비활성 후 직접 호출

- **Phase C-4:** 동시성 정합성 테스트 — PR #4
  - SeatExpiryConcurrencyTest: 만료 vs confirm/hold 동시 경쟁 5개 시나리오 (CountDownLatch/ExecutorService)
  - "차감됐는데 AVAILABLE"·중복 점유 같은 정합성 위반이 없음을 증명
  - 부수: 통합 테스트 이메일 충돌 격리 결함 수정(uniqueId() JVM 단일 시퀀스로 통일), ADR 경로 docs/adr 소문자 통일

- **포인트 충전 (캠페인 쿠폰 상환)** — PR #5 (`feature/coupon-redeem`, 리뷰 대기)
  - 목적: Phase D에서 confirm happy-path 측정에 필요한 booker 잔액 확보. 무제한 충전 대신 신뢰 경계(쿠폰 상환)로 진입 제한
  - 충전 코어 분리: BookerAccount.charge() + PointTransaction(CHARGE) — 미래 ADMIN/PG 경로가 재사용
  - 캠페인 쿠폰: 선착순 max_uses + 유저당 1회 (1회용=max_uses=1로 통합). POST /api/points/redeem (BOOKER)
  - 동시성: 비관적 락(findByCodeForUpdate, 좌석 hold와 동일 기조) + (coupon_id,user_id) 유니크 제약
  - 동시성 테스트 4종(exactly-K, 유저당 1회, 미존재, 소진). 전체 39개 통과
  - ADR-010: 원자적 UPDATE 시도 → 트랜잭션 내 다중 락 혼합 데드락 빈발 → 재시도 비용이 비관적 락 이득 상쇄 → 비관적 락 채택. 초고경합(티켓팅/선착순)은 Redis로 분리(Phase E)
  - 부수: README/master_plan에서 TeamMoa 비교 언급 제거(별도 docs 커밋)

- **Phase D-1: N+1 발견→해결 서사** — PR #6 (머지 완료)
  - 목적: 부하 측정의 첫 작업으로 **환경 무관 before/after 서사** 확보. 절대 RPS는 하드웨어 종속이라 약하고, 쿼리 수·상대 개선율은 환경 불변
  - 실제 필요 기능("목록에 잔여/총 좌석 수") 추가 → 카운트를 이벤트마다 질의하는 최초 구현이 N+1(1+2N) → 측정으로 정량 확인 → GROUP BY 집계로 해결 → 재측정 검증
  - k6 인프라 최초 구축: `loadtest/`(lib/setup.js 시드, event-list.js 부하). 측정 전용 프로파일 application-loadtest.yml(generate_statistics로 쿼리 수)
  - 측정(로컬, MySQL 8.0 Docker): 쿼리 1+2N→**2**(이벤트 100개 기준 201→2), list p95 **357ms→36ms(~10x)**, 처리량 ~11x
  - ADR-011: fetch join 대신 GROUP BY 집계 채택 — 카운트 목적에 좌석 전체 로딩(fetch join)은 낭비, 단방향 설계 유지, 의도-쿼리 일치. fetch join은 상세 페이지(좌석 실제 표시)용
  - 정확성 테스트: EventListSeatCountTest로 status별 집계·좌석 0개·이벤트 간 누수 검증 (k6는 성능만 증명하므로 별도 보강)

### 현재 상태
- **작업 브랜치:** main (origin/main 동기화 완료)
- **마지막 커밋:** `test: 이벤트 목록 좌석 통계 집계 정확성 검증` (91736c6)
  - C-1~C-4 + F-2 + 쿠폰 충전 + Phase D-1까지 머지 완료(PR #1~6)
- **실행 가능 API:**
  - POST /api/auth/signup - 회원가입, POST /api/auth/login - 로그인(JWT 발급)
  - POST /api/events - 이벤트 등록 (PROVIDER 토큰 필요)
  - GET /api/events - 이벤트 목록, GET /api/events/{id} - 상세, GET /api/events/{id}/seats - 좌석(heldUntil 포함)
  - POST /api/seats/{seatId}/hold - 좌석 홀드 (BOOKER)
  - POST /api/seats/{seatId}/confirm - 예약 확정 (BOOKER)
  - POST /api/points/redeem - 캠페인 쿠폰 상환→포인트 충전 (BOOKER)
- **프론트엔드:** frontend/ (React+Vite+Tailwind). `cd frontend && npm install && npm run dev` → :5173. CORS는 WebConfig가 :5173 허용.

### 다음 작업 (우선순위 순)
1. **Phase D-2:** hold/confirm knee point 측정. 부하를 올리며 포화점(무릎)과 직전 안정 p99 측정 — 절대 RPS가 아니라 곡선 형태. 별도 PR. 수치 해석·판단은 인간 몫

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
- **프론트엔드 구조:** `frontend/src/` — pages(Login/Signup/EventList/EventDetail/Checkout), components(SeatGrid/SeatCell/Header/Layout 등), context(Auth/Toast), hooks(useSeatPolling), lib(jwt/errors/format/seatStyle), api(client/auth/events/seats). 폴링은 useSeatPolling 훅 경계로 분리(추후 SSE 교체점).

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