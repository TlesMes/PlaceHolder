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

## 현재 진행 상황 (2026.06.01 기준)

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

### 현재 상태
- **작업 브랜치:** main (origin/main 동기화 완료)
- **마지막 커밋:** `Merge: Testcontainers MySQL 통합 테스트 DB 격리` (1b2e482)
  - Phase B 후속(401/403 분리·@Builder·테스트)과 테스트 DB 격리까지 모두 커밋·머지·푸시 완료
- **실행 가능 API:**
  - POST /api/auth/signup - 회원가입, POST /api/auth/login - 로그인(JWT 발급)
  - POST /api/events - 이벤트 등록 (PROVIDER 토큰 필요)
  - GET /api/events - 이벤트 목록, GET /api/events/{id} - 상세, GET /api/events/{id}/seats - 좌석

### 다음 작업 (우선순위 순)
1. **Phase C:** 동시성 제어 ⭐ (프로젝트 핵심)
   - C-1: 좌석 홀드 (락 전략 결정)
   - C-2: 예약 확정 (트랜잭션 원자성)
   - C-3: 자동 만료 해제
   - C-4: 동시성 정합성 테스트
   - **선행 완료 ✅:** 테스트 DB 격리(Testcontainers MySQL) — 동시성 테스트 기반 마련됨

### 중요 메모
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
  ├── seat/ (entity, repository, dto, service)
  ├── reservation/, point/ (entity, repository)
  global/
  ├── exception/ (GlobalExceptionHandler, ErrorResponse, custom/)
  ├── jwt/ (JwtProvider, JwtAuthenticationFilter)
  ├── security/ (CustomUserDetails(Service), JwtAuthenticationEntryPoint)
  └── config/ (SecurityConfig)
  ```

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