# PR #10 프론트 API 연결 — Chrome 수동 검증 보고서

> 임시 문서. PR `feature/e3-frontend-api` 수락 전 실제 브라우저 동작·디자인 확인용.
> 작성일: 2026-06-14 / 검증자: 수동(Claude + Chrome MCP)

## 1. 검증 대상

PR #10은 PR #9의 모의 데이터(MOCK_*)를 실제 조회 API 3종으로 교체한 변경.

| 화면 | 경로 | 연결 API | 역할 |
|---|---|---|---|
| 마이페이지 · 예약 내역 | `/me` | `GET /api/reservations/my` | BOOKER |
| 마이페이지 · 포인트 이력 | `/me` | `GET /api/points/history` | BOOKER |
| 정산 대시보드 | `/provider/settlement` | `GET /api/providers/my/settlement` | PROVIDER |

## 2. 검증 환경 구성

1. **Docker / MySQL**: Docker Desktop 기동 → `placeholder-mysql` 컨테이너 :3306 확인.
2. **백엔드**: `feature/e3-frontend-api` 체크아웃 후 `.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local` (백그라운드). :8080 기동 확인.
   - `ddl-auto: create`라 스키마가 매번 새로 생성됨 → DB가 비어 있어 테스트 데이터 시드 필요.
3. **프론트엔드**: `cd frontend && npm run dev` → Vite :5173.

### 테스트 데이터 시드 (실제 API 플로우로 생성)

빈 DB라 화면에 표시할 데이터를 실제 사용 플로우로 만들었다(직접 SQL 주입 최소화).

1. 쿠폰 1건만 SQL로 삽입 (생성 API가 없음): `code=WELCOME2026, amount=500000`.
2. `POST /api/auth/signup` — PROVIDER(`provider@test.com`), BOOKER(`booker@test.com`).
3. `POST /api/auth/login` — 양쪽 토큰 획득.
4. `POST /api/events` (PROVIDER) — `재즈 콘서트 2026` / `올림픽홀`, 좌석 A1~A5·B1~B5(각 50,000P).
5. `POST /api/points/redeem` (BOOKER) — 쿠폰 상환 → 잔액 500,000P (CHARGE 1건).
6. `POST /api/seats/{id}/hold` + `/confirm` (BOOKER) — 좌석 A1·A2·A3 확정.

**시드 결과 데이터**

- BOOKER: 예약 3건, 포인트 거래 4건(CHARGE +500,000 / DEDUCT 50,000 ×3), 잔액 350,000P
- PROVIDER: 정산예정 거래 3건(SETTLE 50,000 ×3), 정산 잔액 150,000P
- `point_transactions.amount`는 **타입과 무관하게 전부 양수 크기**로 저장됨(방향은 `type` 컬럼이 가짐). ← 아래 버그의 원인.

> 참고: PowerShell `Invoke-RestMethod`로 이벤트를 만들 때 한글이 `?`로 깨져 저장됐다(요청 본문 UTF-8 미지정). UTF-8 SQL로 `title`/`venue`를 바로잡음. **PR과 무관한 시드 스크립트 이슈.**

## 3. 검증 결과 요약

| 항목 | 결과 |
|---|---|
| 예약 내역 (API 연결) | ✅ 정상 |
| 포인트 이력 (API 연결·필터·페이징) | ⚠️ 동작 정상, **부호 표시 버그 1건** |
| 정산 대시보드 (API 연결) | ✅ 정상 |
| 기간 필터(1/3/6개월, 서버 `from`) | ✅ 동작 |
| 타입 필터(전체/충전/사용/정산, 클라이언트) | ✅ 동작 |
| 다크 모드 (3개 화면) | ✅ 토큰 기반 정상 렌더 |
| 콘솔 에러 | ✅ 없음 |

## 4. 화면별 상세

### 4-1. 예약 내역 (`/me`, BOOKER)
- 예약 3건 카드(A1·A2·A3). 각 카드에 `재즈 콘서트 2026` / `📍 올림픽홀` / 일시 / `결제 완료` / `50,000P` 표시.
- 실제 `GET /api/reservations/my` 응답이 그대로 렌더됨. 이상 없음.

### 4-2. 포인트 이력 (`/me`, BOOKER)
- 거래 4건 표시: `사용`(DEDUCT) 3건 + `충전`(CHARGE) 1건(+500,000P).
- 기간 필터 `3개월` 기본 선택, 타입 필터 `전체` 기본. `사용` 필터 클릭 시 DEDUCT 3건만 노출 — **클라이언트 필터 정상 동작**.
- 데이터 4건이라 페이징 없음 → 하단 `모든 이력을 불러왔습니다` 정상.
- **🐛 버그**: `사용`(DEDUCT) 행이 `+50,000P`로 표시됨(부호 플러스). 차감이므로 `-50,000P`여야 함. 글자 색은 빨강(`text-danger`)으로 올바름 → **부호만 틀림**.

### 4-3. 정산 대시보드 (`/provider/settlement`, PROVIDER)
- 정산 예정 잔액 카드: `150,000P` + 안내 문구(`현금 출금은 지원하지 않으며 적립 집계까지만 제공됩니다`) — 도메인 규칙과 일치.
- 정산 내역 테이블 3행: 일시 / 이벤트(`재즈 콘서트 2026`) / 좌석(A1·A2·A3 배지) / 적립액 `+50,000P`(녹색). 적립이라 플러스 정상.
- PR #10에서 고친 "SettlementPage 링크 버그(reservationId↔eventId 오용)" 관련, 좌석 라벨이 텍스트 배지로 정상 표시됨.

### 4-4. 다크 모드
- 3개 화면 모두 의미 토큰 기반으로 깔끔하게 전환(다크 네이비 배경, 그라데이션 잔액 카드, 테이블 대비 양호). 색 하드코딩 누락 없음.

## 5. 발견 이슈

### 🐛 [Bug] 포인트 이력 `사용`(DEDUCT) 부호가 `+`로 표시
- **현상**: 차감 거래가 `+50,000P`로 보임(색은 빨강으로 정상, 부호만 오류).
- **원인**: 백엔드는 `amount`를 타입 무관 양수 크기로 저장. 프론트는 부호를 `amount > 0`로 판단.
  - `frontend/src/pages/MyPage.jsx:268` — `{it.amount > 0 ? '+' : ''}`
  - `frontend/src/pages/MyPage.jsx:23` 주석은 "타입별 색상/**부호**"라고 하나, `TYPE_META`에 부호 정의가 빠져 있음.
- **수정안**: 부호를 타입에서 도출.
  ```jsx
  const TYPE_META = {
    CHARGE: { label: '충전', sign: '+', text: 'text-success', badge: '...' },
    DEDUCT: { label: '사용', sign: '-', text: 'text-danger',  badge: '...' },
    SETTLE: { label: '정산', sign: '+', text: 'text-primary', badge: '...' },
  };
  // line 268 → {meta.sign}{formatPoint(it.amount)}
  ```
- **영향 범위**: 포인트 이력 화면만. 정산 대시보드의 `+`는 적립이라 정상.

## 6. 결론

- API 연결 3종 모두 실제 데이터로 정상 동작, 디자인(라이트/다크) 양호.
- **머지 전 권장**: 위 부호 버그 1건 수정. 그 외 차단 이슈 없음.

---
### 후속 조치
- 발견한 부호 버그 수정 커밋: `fix: 포인트 이력 사용(DEDUCT) 거래 부호를 -로 표시`.
- 검증용 백그라운드 서버(:8080, :5173) 종료, 임시 로그 삭제 완료.
