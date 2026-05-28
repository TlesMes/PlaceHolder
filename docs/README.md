# PlaceHolder — 문서 구조 가이드

> 이 문서는 프로젝트의 모든 문서 구조와 활용 방법을 정의합니다.
> 새로운 세션에서 작업 시 이 문서를 먼저 참고하세요.

---

## 📁 문서 구조

```
docs/
├── README.md                   # 이 파일 (문서 구조 가이드)
├── placeholder_master_plan.md  # 전체 Phase 플랜 (2026.05~10)
├── placeholder_table_definitions.md # 테이블 정의서
├── sql/
│   └── schema.sql             # DDL
├── ADR/                       # Architecture Decision Records
│   ├── ADR-001-hold-modeling.md
│   ├── ADR-002-point-account.md
│   ├── ADR-003-user-role.md
│   ├── ADR-004-queue.md
│   ├── ADR-005-soft-delete.md
│   └── ADR-00X-*.md           # 결정 지점마다 추가
├── architecture/              # 아키텍처 설계 문서
│   ├── domain-model.md        # 도메인 모델 다이어그램
│   ├── transaction-flow.md    # 트랜잭션 흐름 (특히 Phase C)
│   └── concurrency-strategy.md # 동시성 전략 종합
├── performance/               # Phase D 측정 결과
│   ├── load-test-baseline.md  # 부하 테스트 초기 수치
│   ├── bottleneck-analysis.md # 병목 분석
│   └── metrics/               # 측정 데이터 파일 (.csv, .json)
├── implementation/            # 구현 가이드
│   ├── jwt-setup.md          # Phase B JWT 구현 가이드
│   ├── hold-confirm-flow.md  # Phase C 핵심 플로우
│   └── test-scenarios.md     # 동시성 테스트 시나리오
└── portfolio/                 # 포트폴리오 산출물
    ├── highlights.md         # 핵심 어필 포인트 요약
    ├── interview-scenarios.md # 면접 스토리 2종
    └── before-after/         # 개선 전후 비교 (측정 기반)
```

---

## 📂 각 폴더의 목적

### 1. `ADR/` — Architecture Decision Records
**언제:** 결정 지점마다 작성 (Phase C 락 전략, 트랜잭션 격리 레벨, 만료 처리 방식 등)
**포맷:**
```markdown
# ADR-XXX: [결정 제목]

## 상황 (Context)
## 고려한 옵션 (Options)
## 결정 (Decision)
## 결과 (Consequences)
```
**중심:** **WHY** — "왜 이 선택을 했는가"

### 2. `architecture/` — 아키텍처 설계
**언제:** Phase 시작 전 또는 설계 변경 시
**내용:**
- 도메인 모델 다이어그램 (Mermaid/PlantUML)
- 트랜잭션 흐름도 (홀드→확정 시퀀스)
- 동시성 전략 전체 뷰 (락 적용 지점, 격리 레벨)

**중심:** **WHAT** — "무엇을 어떻게 구성했는가"

### 3. `performance/` — 측정 결과
**언제:** Phase D (부하 테스트) 전용
**내용:**
- 부하 테스트 수치 (RPS, 95th %ile, 에러율)
- 병목 분석 결과
- 측정 데이터 파일 (k6 결과 JSON 등)

**중심:** **측정→판단 서사의 근거**
Phase E 대기열 도입 판단의 정량적 근거가 되는 폴더.

### 4. `implementation/` — 구현 가이드
**언제:** 주요 기능 구현 완료 후
**내용:**
- JWT 설정 과정 기록
- 홀드-확정 플로우 구현 노트
- 동시성 테스트 시나리오 설명

**중심:** **HOW** — "어떻게 구현했는가"
회고, 블로그 작성, 다음 프로젝트 참고용.

### 5. `portfolio/` — 포트폴리오 산출물
**언제:** 각 Phase 완료 시 업데이트, Phase F에서 최종 정리
**내용:**
- 핵심 어필 포인트 요약 (동시성 제어, 측정 기반 판단 등)
- 면접 스토리 2종 (TeamMoa 연결, 정산 법적 맥락)
- 개선 전후 비교 (Phase D 수치 기반)

**중심:** 채용 담당자/면접관이 볼 **요약 문서**

---

## 🔄 Phase별 문서 작성 흐름

### Phase A (완료) — 설계
- ✅ `placeholder_table_definitions.md`
- ✅ `sql/schema.sql`
- ✅ `ADR-001` ~ `ADR-005`

### Phase B (6월 초) — 기반 인프라
1. 작업 시작 전: `architecture/domain-model.md` 작성 (도메인 구조 정리)
2. 구현 중: 코드 작성 (Claude Code 활용)
3. 작업 완료 후: `implementation/jwt-setup.md` 작성 (회고용)
4. 결정 지점 발생 시: ADR 추가 (예: 계정 생성 전략)

### Phase C (6월 중~7월) — 핵심 루프 ★
1. 작업 시작 전: `architecture/transaction-flow.md` 작성
2. 락 전략 3가지 구현 (Claude Code)
3. 동시성 테스트 작성 및 검증
4. **ADR-006 (락 전략)**, **ADR-007 (트랜잭션 격리)**
5. `architecture/concurrency-strategy.md` 종합 정리
6. `implementation/hold-confirm-flow.md` 작성
7. `portfolio/highlights.md` 업데이트 (동시성 제어 항목)

### Phase D (7월~8월) — 부하 테스트 ★
1. 부하 테스트 스크립트 작성 (Claude Code)
2. 측정 실행 → `performance/metrics/` 저장
3. **`performance/load-test-baseline.md`** 작성 (수치 기록)
4. **`performance/bottleneck-analysis.md`** 작성 (분석)
5. `portfolio/highlights.md` 업데이트 (측정 수치 항목)
6. Phase E 우선순위 판단 근거 확보

### Phase E/F — 확장 + 배포
- Phase E: 대기열 설계 시 ADR 추가
- Phase F: `portfolio/` 전체 정리, README 완성

---

## 📝 작업 시작 체크리스트

새로운 세션에서 작업 시작할 때:

1. [ ] `placeholder_master_plan.md`에서 현재 Phase 확인
2. [ ] 해당 Phase의 목표 확인
3. [ ] `CLAUDE.md`에서 개발 규칙 재확인
4. [ ] 이 문서(`docs/README.md`)에서 작성할 문서 종류 확인
5. [ ] 브랜치 생성: `feature/[phase-feature-name]`
6. [ ] Claude Code 세션 시작 (목표 명시)

---

## 🎯 문서 작성 우선순위

### 반드시 작성 (포트폴리오 핵심)
- ADR (결정 지점마다)
- `performance/load-test-baseline.md` (Phase D)
- `performance/bottleneck-analysis.md` (Phase D)
- `portfolio/highlights.md` (Phase 완료 시마다)

### 선택적 작성 (시간 여유 시)
- `architecture/` (다이어그램 포함)
- `implementation/` (회고용)

---

## 🔗 관련 파일 참고

- 프로젝트 개요 및 개발 규칙: [CLAUDE.md](../CLAUDE.md)
- 전체 Phase 플랜: [placeholder_master_plan.md](placeholder_master_plan.md)
- 테이블 정의: [placeholder_table_definitions.md](placeholder_table_definitions.md)
