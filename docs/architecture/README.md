# Architecture — 아키텍처 설계

> **목적:** 시스템의 구조와 설계를 문서화
> **중심:** WHAT — "무엇을 어떻게 구성했는가"

---

## 작성할 문서

### 1. `domain-model.md`
- 도메인 모델 다이어그램 (Mermaid/PlantUML)
- 엔티티 간 관계
- 작성 시점: Phase B 시작 전

### 2. `transaction-flow.md`
- 홀드→확정 시퀀스 다이어그램
- 트랜잭션 경계 명시
- 작성 시점: Phase C 시작 전

### 3. `concurrency-strategy.md`
- 락 적용 지점
- 격리 레벨 선택 근거
- 동시성 전략 종합
- 작성 시점: Phase C 완료 후

---

## 작성 원칙

- 다이어그램 포함 (Mermaid 권장)
- ADR과 연결 (결정 근거는 ADR에)
- 코드 링크 포함 (파일명:라인)
