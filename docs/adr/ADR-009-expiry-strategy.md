# ADR-009. 좌석 홀드 만료 해제 전략 — lazy 재점유 vs 스케줄러 폴링 vs DB TTL

## 상태

채택

## 컨텍스트

Phase C-1(좌석 홀드)에서 홀드는 TTL(`held_until = now + ttl-minutes`)을 갖는다.
TTL이 지난 HELD 좌석은 더 이상 유효하지 않으므로 다른 예약자가 점유할 수 있어야 한다.

현재(C-1)는 **lazy 만료만** 동작한다:
- `Seat.isHoldable(now)`가 HELD이면서 `held_until`이 과거인 좌석을 "점유 가능"으로 판정한다.
- 즉, **새 hold 요청이 들어와야만** 만료가 사실상 반영된다.

이로 인한 문제(C-3 동기):
- 만료된 HELD 좌석을 아무도 재점유하지 않으면 DB `status`가 `HELD`로 박제된다.
- `GET /events/{id}/seats` 조회는 status를 `HELD`로 그대로 노출한다. 프론트엔드는 `held_until`로 effectiveStatus를 계산해 화면에서만 AVAILABLE로 보정하므로 **서버 진실(DB status)과 화면 표현이 불일치**한다.
- `findByEventIdAndStatus(AVAILABLE)` 같은 집계/조회가 실제 가용 좌석을 누락한다.

전제(코드/스키마):
- `Seat`: `status`(AVAILABLE/HELD/CONFIRMED), `heldBy`, `heldUntil`. `@Version` 없음.
- `seats`: `idx_seats_status_held_until` 인덱스 보유.
- `SeatRepository`: `findByIdForUpdate`(PESSIMISTIC_WRITE), 만료 후보 조회용 쿼리 보유.
- 락 기조: ADR-008에서 비관적 락 채택.

## 근거 (비교 분석)

| 전략 | 구현 방식(본 프로젝트) | 동작 | 장점 | 단점/리스크 |
|---|---|---|---|---|
| **lazy 재점유 only** | `isHoldable(now)`가 만료 HELD를 점유 가능으로 판정 (현행 C-1) | hold 요청이 와야 만료 반영 | 추가 컴포넌트 없음, 구현 단순 | **DB status가 HELD로 박제** → 조회/집계 부정확, 서버-프론트 불일치 |
| **스케줄러 폴링** | `@Scheduled`가 주기적으로 만료 후보를 조회해 AVAILABLE로 전이 | 주기마다 만료 좌석을 능동 해제 | DB status가 진실과 수렴, 조회/집계 정확, 운영 가시성(로그) | 스캔 주기 사이 빈틈 존재, 폴링 부하, confirm과의 경쟁 처리 필요 |
| **DB 이벤트/TTL** | DB 레벨 TTL이나 이벤트 스케줄러로 만료 행 갱신 | DB가 만료 처리 | 애플리케이션 코드 최소 | MySQL은 행 단위 TTL 미지원, 도메인 메서드(setter 금지) 우회, 락/트랜잭션 일관성 통제 어려움, 이식성·테스트성 저하 |

추가 분석 포인트:
- **스캔 주기 빈틈:** 스케줄러는 주기(`scan-interval-ms`) 사이에 만료가 발생하면 다음 스캔까지 DB status가 HELD로 남는다. 이 빈틈은 **lazy 재점유를 안전망으로 유지**하면 보정된다 — 그 사이 hold 요청이 오면 `isHoldable`이 즉시 만료를 인정한다.
- **confirm 경쟁 정합성:** 막 만료된 HELD 좌석에 (a)스케줄러 해제와 (b)정당한 holder의 confirm이 동시에 들어올 수 있다. 스케줄러가 좌석을 `findByIdForUpdate`로 **행별 비관적 락 재확인** 후 전이하면, confirm이 락을 먼저 잡은 경우 스케줄러는 CONFIRMED를 보고 건너뛴다(반대도 동일). 따라서 "차감됐는데 AVAILABLE" 같은 정합성 위반이 발생하지 않는다 — ADR-008 비관적 락 기조와 일관.
- **폴링 부하 완화:** 후보는 ID projection 쿼리(`findExpiredHeldSeatIds`)로 가볍게 조회하고 인덱스(`idx_seats_status_held_until`)를 활용한다. 각 좌석은 짧은 개별 락으로 처리해 락 보유 시간을 최소화한다(전체를 한 트랜잭션·한 락으로 묶지 않음).
- **측정 연계:** 적정 `scan-interval-ms`와 대량 만료 시 배치 분할 필요성은 Phase D 부하 측정 후 조정한다(이 ADR은 정성 결정까지).

## 결정

**스케줄러 폴링 + lazy 재점유 안전망 병행을 채택한다.**

- `@Scheduled` 스케줄러(`SeatExpiryScheduler`)가 `seat.expiry.scan-interval-ms`(기본 60초)마다 `SeatExpiryService.releaseExpiredSeats()`를 호출한다.
- 만료 처리는 후보 ID를 락 없이 조회 → 각 좌석을 `findByIdForUpdate`로 개별 재잠금 → 락 보유 상태에서 만료를 재확인 → `Seat.release()`로 AVAILABLE 전이한다.
- 기존 lazy 만료(`isHoldable`)는 제거하지 않고 스캔 주기 사이의 빈틈을 보정하는 안전망으로 유지한다.

근거 요약:
1. DB status를 진실과 수렴시켜 조회/집계 정확성과 서버-프론트 일관성을 확보한다.
2. 행별 비관적 락 재확인으로 confirm/재점유 경쟁에서 정합성을 보장한다(ADR-008과 일관).
3. lazy 안전망 병행으로 스케줄러 단독의 주기 빈틈 약점을 보완한다.

## 트레이드오프

- **스케줄러 채택 시:** 주기적 폴링 비용과 스캔 주기만큼의 해제 지연(빈틈)이 존재한다. 빈틈은 lazy 안전망이 보정하며, 주기/배치 크기는 Phase D 측정 후 조정한다.
- **운용 규칙:** 만료 처리 트랜잭션 내부에서 외부 I/O를 호출하지 않는다(ADR-008 운용 규칙과 동일). 단일 인스턴스 가정 — 다중 인스턴스로 확장 시 중복 스캔/락 경합은 허용 가능하나(락으로 정합성은 보장), 필요 시 분산 락·shedlock 도입을 재검토한다.
- **DB TTL 미채택:** 도메인 메서드 기반 상태 전이(setter 금지)와 트랜잭션/락 통제를 애플리케이션에 유지하기 위해 DB 레벨 TTL은 채택하지 않는다.
