# 사업자 API 강화 + 인덱스 측정 PR 설계 (Phase E-3 후속)

> 상태: 계획 (별도 PR로 진행 예정)
> 선행: Phase E-3 (`feature/e3-history-queries`) 머지
> 후속 ADR 후보: ADR-013

## 배경

Phase E-3에서 조회 API 3종을 추가하면서 인덱스를 새로 깔지 않고 단일 `idx_pt_user_id`를 유지하기로 결정했다(ADR-012). 그 결정의 가정은 **"사용자당 거래 행 수가 작다"**였다.

이 가정은 **고객(BOOKER) 측에서는 견고**하지만, **사업자(PROVIDER) 측에서는 약하다**. 인기 사업자(대형 공연장·콘서트 기획사)는 일 단위로 정산 거래가 누적되어 사용자당 수만~수십만 행 규모가 현실적이다.

문제는 인덱스가 **테이블 단위 전역**이라는 점이다. `point_transactions`에 깔린 인덱스는 BOOKER 쿼리든 PROVIDER 쿼리든 똑같이 사용된다. 따라서 인덱스 결정은 **쿼리 주체별로 분리할 수 없고**, 가장 부담 큰 쿼리 기준으로 정당화돼야 한다.

본 문서는 사업자 API 강화 + 인덱스 측정 PR의 설계안을 정리한다. 측정 결과로 ADR-013을 작성하고, 정산 API의 cursor 페이징·기간 필터 도입 여부도 함께 결정한다.

---

## 목표

1. **사업자 API 강화**: 정산 조회에 cursor 페이징 + 기간 필터 추가 (고객 측과 동일 패턴)
2. **인덱스 측정**: 단일 `(user_id)` vs 복합 `(user_id, created_at)`을 실제 사업자 데이터 규모로 정량 비교
3. **측정 기반 결정**: ADR-013으로 인덱스 추가 여부 결정 (또는 도메인 가정의 정당성 재확인)
4. **포트폴리오 서사 확장**: D-1 N+1 측정에 이은 **인덱스 측정 챕터**. "고객 API → 사업자 API 확장 시 동일 테이블의 쿼리 부담 차이를 측정으로 식별"

---

## 데이터 규모 시나리오 (도메인 분석)

`point_transactions`에 PROVIDER 사용자당 누적되는 SETTLE 거래 행 수를 도메인 가정으로 추정.

| 시나리오 | 가정 | 연간 SETTLE 행 | 5년 누적 |
|---------|------|---------------|---------|
| A. 일반 사업자 | 연 10이벤트 × 500석 × 점유율 70% | 3,500 | 17,500 |
| B. 인기 기획사 | 월 5이벤트 × 1,000석 × 점유율 95% | 57,000 | 285,000 |
| C. 대형 공연장 | 일 1공연 × 2,000석 × 점유율 90% | 657,000 | 3,285,000 |

→ B/C 구간에서 단일 인덱스로는 filesort 비용이 체감되기 시작할 가능성이 높다. 측정으로 확인한다.

**부하 데이터 셋업 목표:**
- PROVIDER 사용자 100명 (시나리오 A/B/C 분포 — 예: A 80명, B 18명, C 2명)
- BOOKER 사용자 10,000명
- `point_transactions` 전체 약 300만~500만 행
- 한 PROVIDER의 SETTLE 거래에 시나리오 분포 반영

---

## 측정 항목

| 지표 | 도구 | 환경 무관도 | 비고 |
|------|------|------------|------|
| `EXPLAIN` Extra (filesort 발생 여부) | MySQL 직접 | ★★★ | "Using filesort" 유무가 핵심 |
| `EXPLAIN` rows (스캔 행 수 추정) | MySQL 직접 | ★★★ | 옵티마이저 추정치, 행 수 비례 |
| `EXPLAIN` key, key_len, possible_keys | MySQL 직접 | ★★★ | 어느 인덱스를 실제로 쓰는가 |
| 쿼리 실행 시간 | `SHOW PROFILE` / `performance_schema` | ★★ | 평균/최대 |
| API 응답 p95 / p99 | k6 | ★ | 환경 종속, 참고치 |
| 인덱스 디스크 사용량 | `information_schema.TABLES` (INDEX_LENGTH) | ★★★ | 복합 인덱스 비용 |
| INSERT 처리량 | k6 (hold/confirm 부하) | ★★ | 인덱스 추가로 쓰기 영향 |
| ALTER TABLE 실행 시간 | 직접 측정 | ★★ | 운영 마이그레이션 비용 |

**환경 무관도 기준 핵심 지표는 `EXPLAIN` Extra 와 인덱스 디스크 사용량.** 응답 시간은 환경 종속이라 상대 비교로만 해석.

---

## 측정 절차

### 1. 시드 스크립트 작성
- `loadtest/seed-provider-scale.js` (또는 SQL bulk insert)
- 정상 흐름(hold/confirm)으로 시뮬레이션하면 너무 느리므로 직접 INSERT
- 정상 흐름의 정합성은 기존 통합 테스트가 검증하므로 별도 보강 불필요

### 2. 측정 사이클
각 인덱스 구성에서 동일 측정 반복:

```
[단일 인덱스 상태]
  1. EXPLAIN SELECT ... WHERE user_id=? AND type='SETTLE' ORDER BY created_at DESC LIMIT 20
  2. EXPLAIN SELECT ... WHERE user_id=? AND created_at >= ? AND created_at < ? ORDER BY created_at DESC LIMIT 20
  3. 시나리오 A/B/C 사업자별로 1, 2를 각각 실행 → Extra/rows/실행시간 기록
  4. k6 정산 조회 부하 (PROVIDER 토큰 사용) → p95/p99
  5. k6 hold/confirm 부하 → INSERT 처리량
  6. information_schema.TABLES → 인덱스 디스크 크기

[ALTER TABLE ADD INDEX (user_id, created_at)]
  - 실행 시간 기록

[복합 인덱스 상태]
  - 위 1~6 반복

[DROP INDEX]
  - 원상복귀
```

### 3. 결과 비교 매트릭스

| 케이스 | 인덱스 | 시나리오 A (3.5K행) | 시나리오 B (28만행) | 시나리오 C (65만행) |
|--------|--------|---------------------|--------------------|--------------------|
| 단일 `(user_id)` | 기본 | filesort?/p95? | filesort?/p95? | filesort?/p95? |
| 복합 `(user_id, created_at)` | 추가 | filesort?/p95? | filesort?/p95? | filesort?/p95? |

추가로 기록:
- 디스크 사용량 변화 (절대값/비율)
- INSERT 처리량 변화 (%)
- ALTER 실행 시간

---

## 결정 분기

측정 결과에 따라 다음 중 하나로 ADR-013 작성:

**A. 복합 인덱스 채택**
- 인기 사업자 시나리오에서 filesort 발생 + 응답시간 체감 차이
- 디스크/쓰기 비용은 허용 범위
- "BOOKER 측만 보면 과잉이지만, PROVIDER 누적 위험을 차단하려 채택"

**B. 단일 인덱스 유지 + 응답 캐싱/집계**
- 복합 인덱스 비용이 이득 대비 큼
- 대신 사업자 대시보드의 자주 보는 데이터는 캐시(Redis) 또는 사전 집계 테이블 도입
- "쿼리 패턴별 인덱스 분리가 불가능하니, 부하 큰 쪽은 별도 레이어로 분리"

**C. 단일 인덱스 유지 (도메인 가정 재확인)**
- 사업자 누적량이 시뮬레이션상 우려 수준이 아님
- 단일 인덱스로도 응답시간 안정
- "도메인 분석 가정이 실제 측정으로 확인됨"

---

## 사업자 API 강화 (코드 변경)

### 정산 API에 cursor 페이징 + 기간 필터 추가

**현 상태**: `GET /api/providers/my/settlement` — settlementBalance + SETTLE 거래 목록 전체 반환

**개선 후**:
```
GET /api/providers/my/settlement?from=&to=&cursor=&size=
```
- `settlementBalance`: 페이징 무관 전체 누적 잔액 (한 행 조회)
- `settlements[]`: cursor 페이징된 SETTLE 거래 목록
- `nextCursor`: 마지막 item의 createdAt
- 기간 default: 최근 3개월 (포인트 이력과 동일 정책)
- size default: 20, max: 100 (clamp)

### 변경 범위
- `PointTransactionRepository.findSettlementsByProviderId` → cursor 버전으로 교체 (기존 메서드는 제거 또는 유지하고 신규 추가)
- `ProviderAccountService.getMySettlement` → 쿼리 파라미터 처리
- `ProviderController` → `?from=&to=&cursor=&size=` 받기
- `SettlementResponse` → `nextCursor` 필드 추가
- 기존 `PointHistoryService`의 default 로직을 공용 유틸로 추출 검토 (중복 회피)

---

## 산출물

1. **ADR-013**: 인덱스 결정 (측정 기반)
2. **부하 측정 데이터**: 환경 정보(CPU/RAM/MySQL 버전/Docker) + EXPLAIN 캡처 + p95/p99 그래프 + 디스크 사용량
3. **사업자 API 강화**: 정산 cursor 페이징 + 기간 필터
4. **CLAUDE.md 갱신**: 진행상황 + 신규 엔드포인트 변경 반영
5. **ADR-012 본문 보강**: "사업자 측 부담은 ADR-013에서 다룸" 링크

---

## 진행 순서 권장

1. 시드 스크립트 작성 + 측정 환경 셋업
2. 단일 인덱스 측정 (EXPLAIN + k6)
3. ALTER + 복합 인덱스 측정
4. 결과 분석 → ADR-013 결정 분기 선택
5. 사업자 API 강화 코드 (cursor 페이징)
6. ADR-013 / ADR-012 수정 / CLAUDE.md 작성
7. 통합 테스트 추가 (cursor 페이징 동작 검증)

테스트는 측정과 별개로 Docker 가능 환경에서 진행.

---

## 협업 경계 리마인드 (CLAUDE.md)

- 시드/측정 스크립트 작성·실행 = Claude
- **수치 해석·인덱스 결정 = 인간** (ADR-013 결론)
- 측정 환경(CPU/RAM/MySQL/위치) 반드시 병기 — 절대 RPS가 아니라 상대 비교·곡선 형태가 핵심
