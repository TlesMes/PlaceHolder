# ADR-016. 이탈 시 좌석 hold 반환 — release는 좌석만, 토큰은 프론트가 leave와 조합

## 상태

확정 — 구현 완료 (`feature/hold-release-on-leave`)

> 컨텍스트·고려안·트레이드오프·한계는 Claude 정리, **결정은 작성자 확정.**

## 컨텍스트

### 마지막 미반환 자원: 좌석 hold

PR #16(confirm 시 토큰 회수)·#17(이탈 시 순번+토큰 회수)로 대기열 **세션 자원**의 유령은
제거됐다. 그러나 **가장 희소한 자원인 좌석 hold**는 여전히 만료(5분)에만 의존한다. 그 결과
데드락이 재현된다:

1. 유저가 좌석 hold → 결제페이지 진입 → 이탈(뒤로가기·새로고침·닫기)
2. 좌석은 5분간 HELD로 잠긴 채 남는다 (본인조차 재선택 불가 — "내 홀드 재개" UI가 죽어있음)
3. 새로고침하면 PR #17이 **토큰까지 회수** → 재hold도 `isHoldable=false`로 거부
   → 재대기 + 재hold 거부의 **5분 데드락**

이 데드락은 PR #17이 만든 게 아니라 **기존 버그를 노출**한 것이다(뒤로가기만 해도 재현).
좌석이 이탈 즉시 AVAILABLE로 돌아오면 "내 홀드 재개" UI 자체가 불필요해지고 데드락 원천이 소멸한다.

### ADR-015와의 관계

ADR-015 §2는 "Checkout에는 leave를 부착하지 않는다 — confirm이 **토큰**을 요구하지 않으므로"라고
했다. 그 판단은 **토큰** 관점에서 여전히 옳다. 이번 결정이 Checkout에 부착하는 것은 **좌석 hold**
(다른 자원)의 반환이다. 두 판단은 충돌이 아니라 자원별로 분리된 것이다.

## 핵심 결정: release는 좌석만 반환, 토큰은 안 건드린다

`POST /api/seats/{seatId}/release`는 **좌석 hold만** AVAILABLE로 되돌린다. **입장 토큰은 건드리지
않는다.** 토큰 회수가 필요하면 프론트가 기존 `POST /api/queue/{eventId}/leave`(PR #17)를 **함께
호출**해 처리한다.

이렇게 분리해야 이탈 유형별로 다르게 동작할 수 있기 때문이다:

| 이탈 유형 | 트리거 | 좌석 hold | 입장 토큰 | 근거 |
|-----------|--------|-----------|-----------|------|
| SPA 뒤로가기·헤더 이동 | 컴포넌트 unmount | **반환** | **유지** | "좌석 바꾸기" 성립 — 재대기 없이 좌석페이지에서 다른 좌석 선택 |
| 닫기·새로고침·외부 이동 | `pagehide` | **반환** | **회수(leave)** | 완전 이탈 — 세션 전체 정리 |
| 결제 완료 | confirm 성공 | (CONFIRMED) | 회수(PR #16) | 정상 종료 |

만약 `/release`가 토큰까지 지웠다면 "뒤로가기 = 좌석만 반환" 이 불가능해진다. **토큰 결합 금지가
이 설계의 핵심**이다 — 반환 대상을 좌석으로 좁혀, 토큰의 거취는 호출자(프론트)가 유형에 따라 결정.

## 구현 결정

### 1. `POST /api/seats/{seatId}/release` — 본인 홀드만, 멱등 no-op

```java
@Transactional
public SeatReleaseResponse releaseSeat(Long seatId, Long bookerId) {
    Seat seat = seatRepository.findByIdForUpdate(seatId)   // 비관적 락 (ADR-008)
            .orElseThrow(() -> new SeatNotFoundException("좌석을 찾을 수 없습니다"));
    boolean mine = seat.getStatus() == HELD
            && seat.getHeldBy() != null
            && seat.getHeldBy().getId().equals(bookerId);
    if (mine) seat.release();                              // 기존 도메인 메서드 재사용
    return SeatReleaseResponse.builder()
            .seatId(seat.getId()).status(seat.getStatus().name()).released(mine).build();
}
```

- **본인 홀드(HELD && heldBy==나)만** 반환. 타인 홀드·CONFIRMED·이미 AVAILABLE은 **예외 없이
  no-op** — pagehide/unmount에서 중복·경쟁 호출(만료 스케줄러 선점 등)이 들어와도 멱등해야 한다
  (PR #17 leave와 동일 철학). no-op을 예외로 만들면 정상 이탈 정리가 실패로 보이게 된다.
- **락 획득 후 상태 재확인**은 `SeatExpiryService.releaseExpiredSeats`의 TOCTOU 방어와 같은 패턴.
- release는 자원 **반납**이므로 대기열 게이트(`enforceQueueAdmission`)를 호출하지 않는다.
- 응답 `released` 플래그로 실제 반환/no-op를 구분해 프론트가 알 수 있게 한다.

### 2. 프론트: 이탈 유형별 분기

- **`releaseSeatBeacon`(keepalive fetch + JWT 헤더)**: pagehide 언로드 중 전송. sendBeacon은 JWT
  헤더 불가라 fetch(keepalive)를 쓴다(ADR-015 §2와 동일 이유).
- **`releaseSeat`(일반 axios)**: SPA 이동은 페이지가 살아있으므로 일반 요청.
- `useCheckoutLeaveRelease` 훅: pagehide → 좌석 반환 + (queueEnabled면) leave / unmount → 좌석만
  반환. `done`(결제 완료) 시 아무것도 안 함 — 완료 좌석은 CONFIRMED라 설령 호출돼도 서버가 no-op.
- **StrictMode 안전장치(E2E에서 발견·수정):** unmount cleanup에서 좌석을 반환하는데, React
  StrictMode(개발)는 마운트 직후 동기적으로 setup→cleanup→setup을 돌린다. 그 probe cleanup이
  실행되면 결제페이지에 진입하자마자 좌석이 반환돼 버린다(E2E에서 hold 직후 release 관측). →
  `setTimeout(0)`으로 다음 틱에 `canReleaseRef`를 켜고, 그 전(probe cleanup)엔 반환을 건너뛴다.
  probe 언마운트는 canRelease가 켜지기 전(동기)이라 안전하고, 사람의 실제 이탈은 항상 이후라
  정상 반환된다. pagehide 경로는 window 이벤트라 애초에 StrictMode 영향 없음.
- 기존 `beforeunload` 경고 다이얼로그는 유지(실수 이탈 방지).

## release vs confirm 동시성

같은 좌석 행을 두고 반환과 확정이 경쟁하면 비관적 락이 직렬화한다:

- **확정 승리**: 좌석 CONFIRMED, 잔액 1회 차감, 반환은 뒤이어 no-op(HELD 아님).
- **반환 승리**: 좌석 AVAILABLE, 확정은 `validateHold`에서 거부(HELD 아님) → 잔액 차감·정산 흔적 없음.

어느 순서든 최종 상태가 **CONFIRMED xor AVAILABLE로 결정적**이고 잔액/정산이 상태와 일치한다.
`SeatReleaseConcurrencyTest`가 반복 시행으로 양 순서를 노출해 검증한다.

## ⚠️ 한계

- **best-effort다.** 크래시·모바일 강제종료·네트워크 단절은 `pagehide`가 안 뜨거나 요청이 유실된다
  → 이 경우 좌석 hold TTL(5분)이 안전망. 정상 이탈(대부분)이 0초로 당겨진 것이지 상한이 바뀐 건 아님.
- **heldByMe / "내 홀드 재개" UI는 복구하지 않는다**(작성자 결정). 이탈 즉시 좌석이 AVAILABLE이 되어
  그 UI가 불필요해지므로. seat DTO에 heldBy 노출(프라이버시 주석과 상충)은 별도 작업으로 분리.
- 이 결제페이지 이탈 처리는 후속 재설계(백로그: 대기열을 event 진입에 배치, A안 전환)에서도 그대로 쓰인다.

## 결정

**`POST /api/seats/{seatId}/release`는 본인 좌석 hold만 멱등 반환하고 입장 토큰은 건드리지 않는다.
토큰 회수는 프론트가 이탈 유형에 따라 기존 leave API와 조합한다(뒤로가기=좌석만·토큰 유지 /
닫기·새로고침=좌석+leave). 동시성은 비관적 락으로 직렬화한다.**

- 토큰 결합 금지가 핵심 — "좌석 바꾸기"(뒤로가기 시 토큰 유지) UX가 성립하려면 반환 대상을 좌석으로
  좁혀야 한다.
- 본인 홀드만 반환, 그 외는 no-op(멱등). 남의 좌석·CONFIRMED는 절대 불가침.

## 참조

- ADR-008: 비관적 락 전략 — `findByIdForUpdate` 직렬화
- ADR-009: 만료 전략 — `seat.release()` 도메인 메서드, TOCTOU 재확인 패턴
- ADR-015: 이탈 감지 — pagehide + keepalive fetch, leave 멱등 정리(토큰 조합 대상)
- PR #16/#17: 대기열 세션 유령 제거 — 같은 계열의 선행 작업
- `feature/hold-release-on-leave`: 구현 브랜치
