# 정산 조회(`/api/providers/my/settlement`) 무페이징 한계 — 측정·페이징 백로그

> 상태: **백로그 (측정 선행 후 판단)**
> 작성: 2026-06-14 (E-3 프론트 API 연결 세션 중 발견)
> 관련: [ADR-012](../adr/ADR-012-point-history-cursor-pagination.md), `feature/e3-frontend-api`

## 한 줄 요지

정산 조회 API는 **페이징 없이 SETTLE 거래 전건을 반환**한다. ADR-012는 "정산 건수는 본질적 상한이 있어 작다"고 보고 페이징을 의도적으로 생략했으나, 그 가정은 **예약자(BOOKER) 기준**이다. **제공자(PROVIDER)의 SETTLE은 자기가 등록한 모든 좌석의 판매 합산**이라 좌석 판매량에 비례해 단조 증가한다 — 가정이 PROVIDER 쪽에서 무너질 수 있다.

## 현황 (코드 근거)

- 쿼리: [`PointTransactionRepository.findSettlementsByProviderId`](../../src/main/java/com/placeholder/domain/point/repository/PointTransactionRepository.java)
  ```jpql
  select pt from PointTransaction pt
    join fetch pt.reservation r
    join fetch r.seat s
    join fetch s.event
  where pt.user.id = :providerId
    and pt.type = SETTLE
  order by pt.createdAt desc
  ```
  → **LIMIT/cursor 없음.** 해당 provider의 SETTLE 거래를 전부 메모리에 적재 후 DTO 매핑.
- 서비스: [`ProviderAccountService.getMySettlement`](../../src/main/java/com/placeholder/domain/provider/service/ProviderAccountService.java) — 전건을 `settlements` 리스트로 그대로 응답.
- 응답 DTO: `SettlementResponse { settlementBalance, List<SettlementItem> settlements }` — `nextCursor` 없음.

대조: **포인트 이력**(`/api/points/history`)은 cursor 페이징 O + 기간 필터 O.
→ 같은 `point_transactions` 테이블을 읽는데 **두 조회 경로의 확장성 정책이 비대칭**이다.

## 왜 ADR-012의 가정이 PROVIDER에서 약한가

ADR-012는 거래 트리거를 이렇게 분석하며 정산을 "상한 있음"으로 분류했다:

> 정산(SETTLE): 제공자가 좌석을 확정받을 때만 — 사용자당 연 수십~수백 행 수준

이 "수십~수백"은 **한 사용자(주로 예약자)가 만드는 거래량** 직관에 기댄 수치다. 그러나:

- **BOOKER의 거래량** = 본인이 충전·예약한 횟수 → 개인 활동량. 작다는 가정 타당.
- **PROVIDER의 SETTLE 거래량** = 본인이 등록한 **모든 이벤트의 모든 좌석 판매 합산**.
  인기 이벤트 1개(좌석 수백 석)가 매진되면 그 한 건으로 SETTLE 수백 행. 이벤트를 여러 개 운영하면 누적은 더 빨라진다.
  → 개인 활동량이 아니라 **사업 규모에 비례**하는 양이라 본질적 상한이 약하다.

즉 ADR-012의 "append-only는 포인트 이력만, 정산은 상한 있음" 이분법에서 **정산(PROVIDER 시점)도 사실상 단조 증가축에 가깝다.**

## 영향 (페이징 없을 때)

ADR-012가 페이징 도입 근거로 든 문제들이 그대로 정산에 적용된다:
- 서버 메모리 압박(결과 전체 적재 후 직렬화)
- DB→앱 전송·JPA 매핑 비용이 건수에 선형
- 응답 지연(TTFB) 증가
- **운영 시한폭탄**: 적을 땐 멀쩡하다 임계 넘는 순간 갑자기 느려짐

## 제안 (별도 PR — 측정 선행)

ADR-012의 정신("데이터량 실측 없이 인덱스/페이징 정책 결정 금지")을 그대로 따른다.

1. **측정 먼저**: 정산 건수를 늘려가며(예: 100 → 1k → 10k 행) `getMySettlement` 응답시간/메모리 곡선 측정.
   Phase D의 k6 인프라(`loadtest/`) 재사용. knee point 확인.
2. **수치 기반 판단(인간 결정 영역)**: 곡선이 실제로 꺾이면 정산 조회에 **cursor 페이징 도입**
   (포인트 이력과 동일 패턴 — `WHERE created_at < cursor ORDER BY created_at DESC LIMIT`, `nextCursor` 응답).
   필요 시 `(user_id, type, created_at)` 복합 인덱스 검토(ADR-012의 단일 인덱스 결정도 함께 재평가).
3. 결론은 **ADR-012 보강 또는 신규 ADR**로 남긴다.

## 결정 보류 사유

현 단계 데이터량에선 문제 미발현 → 지금 페이징을 넣는 것은 ADR-012가 경계한 "측정 없는 선제 최적화"가 된다.
**측정으로 knee point를 확인한 뒤** 도입 여부를 판단한다. 이번 E-3 프론트 API 연결 세션의 범위 밖이라 백로그로 분리.

## 연관 메모

- 포인트 이력의 **타입 필터(CHARGE/DEDUCT/SETTLE)는 클라이언트에서 처리**하기로 결정(같은 세션).
  근거: `type`이 이미 응답에 포함 + BOOKER 거래량 적음 전제 → 페이지 내 필터 균열 실질 미발생.
  이 결정 역시 "BOOKER 거래량 적음" 전제에 의존하므로, 위 정산 한계와 같은 가정의 양면이다.
