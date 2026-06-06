# ADR-010. 캠페인 쿠폰 상환 동시성 전략 — 비관적 락 vs 원자적 UPDATE vs Redis

## 상태

채택

## 컨텍스트

Phase D 부하 테스트에서 `confirm`(예약 확정) happy-path를 측정하려면 예약자(booker)에게 실제 포인트 잔액이 필요하다. 그런데 충전 수단이 전혀 없어(가입 시 잔액 0, `BookerAccount`에 `deduct()`만 존재) 충전 경로가 선결 과제가 되었다.

"아무나 호출하면 잔액이 오르는" 충전 API는 포인트 무한 발행을 허용해 마켓플레이스 정합성을 깬다. 충전의 **진입 경로(신뢰 경계)** 가 제한돼야 한다(쿠폰 / 관리자 지급 / PG 실결제). PG는 Phase E-2로 미루고, 이번에는 **캠페인 쿠폰 상환**을 선구현한다.

캠페인 쿠폰의 요구:
- 코드 하나(`WELCOME2026`)를 다수가 입력, **선착순 max_uses명** 한정 (1회용 기프트카드 = `max_uses=1` 특수케이스).
- **유저당 1회** 제한.

경합 단위는 **단일 Coupon 행(coupon.id)** 이며, 추가로 `(coupon_id, user_id)` 중복도 막아야 한다.

전제(코드/스키마):
- `Coupon`: `code`(unique), `amount`, `max_uses`, `used_count`. 도메인 메서드 `redeem()`(소진 검사 후 used_count 증가).
- `coupon_redemptions`: `(coupon_id, user_id)` 유니크 제약.
- `BookerAccount`: `findByUserIdForUpdate`(비관적 락) 기존 보유, `charge()` 추가.
- `PointTransaction.TransactionType.CHARGE` 기존 보유.

## 근거 (비교 분석)

총량(used_count 초과) 방어를 어떻게 할지가 핵심 결정이다. 세 후보를 비교했다.

| 전략 | 동작 | 장점 | 단점/리스크 |
|---|---|---|---|
| **비관적 락** (채택) | 쿠폰 행을 `findByCodeForUpdate`로 잠그고 검사·증가 | 데드락 없음(락 순서 일관), 재시도 불필요, **동작 예측 가능**, 좌석 hold/confirm·ADR-008과 일관 | 직렬화 오버헤드(고경합 시 대기 행렬) |
| **원자적 조건부 UPDATE** | `UPDATE ... SET used_count=used_count+1 WHERE used_count<max_uses`, 영향행 0=소진 | 락 보유 시간이 UPDATE 문장 한 개로 짧음(이론상 처리량↑) | 한 트랜잭션에 카운터 UPDATE + 유니크 INSERT + 잔액 락이 섞이며 **InnoDB 데드락 빈발** → 재시도 필요 |
| **Redis 원자 연산** | `DECR coupon:{id}:remaining`, 결과<0=소진 | 싱글스레드라 데드락·락·재시도 자체가 없음, 초고경합에 최적 | 외부 스택 추가, DB와의 정합(eventual) 설계 필요 |

### 구현·측정으로 확인한 사실 (원자적 UPDATE를 먼저 시도했다가 철회)

원자적 UPDATE를 먼저 구현했다. 이론은 "lost update만 막으면 되니 락 없이 카운터를 원자 증가하면 직렬화 손해를 피한다"였다. 그러나 동시성 테스트에서 `CannotAcquireLockException("Deadlock found")`가 빈발했다:
- 같은 쿠폰 행 동시 `UPDATE`, 그리고 같은 `(coupon,user)` 동시 `INSERT`(unique 인덱스 insert-intention lock)가 한 트랜잭션 안에서 엇갈려 **순환 대기 → 데드락**. InnoDB가 한쪽을 희생자로 롤백해, maxUses=3에 10명이 경쟁해도 1명만 성공하는 일이 발생했다.
- 데드락 희생자를 **새 트랜잭션으로 재시도**하면 정합성은 회복되지만(빈 분리 + 지터 백오프 필요), **재시도 비용이 비관적 락의 직렬화 이득을 상쇄**했다. 즉 "락 없이 빠르게"라는 원래 동기가 이 규모·이 트랜잭션 구성에서는 성립하지 않았다.

> **핵심 교훈:** 원자적 UPDATE/유니크 제약은 **정합성**은 주지만 **진행성(progress)** 은 보장하지 않는다. 고경합에서 DB는 데드락을 자동 해소하며 일부 트랜잭션을 희생시키므로 재시도가 필수가 되고, 그 비용을 합치면 단순한 비관적 락 대비 이득이 사라질 수 있다. "lock-free가 항상 빠르다"는 직관은 트랜잭션이 여러 락을 섞을 때 깨진다.

### 유저당 중복

`coupon_redemptions (coupon_id, user_id)` **유니크 제약**으로 막는다. 비관적 락으로 직렬화되므로 동시 INSERT가 데드락 없이 순차 처리되고, 둘째부터는 `DataIntegrityViolationException` → `CouponAlreadyRedeemedByUserException`.

### 잔액 적립 / 트랜잭션 순서

[쿠폰 락 → 중복기록 삽입 → 카운터 증가(`coupon.redeem()`) → 잔액 적립(`charge`) → CHARGE 기록]을 단일 트랜잭션으로 처리한다. 중복기록을 먼저 `saveAndFlush`해 유니크 위반을 즉시 평가하므로 보상 로직이 불필요하다. 어느 단계든 실패하면 전체 롤백.

## 결정

**캠페인 쿠폰 상환 동시성을 비관적 락으로 직렬화한다 (좌석 hold/confirm과 동일 기조).**

- 총량(used_count): `findByCodeForUpdate` **비관적 락** 보유 상태에서 `coupon.redeem()`이 소진 검사·증가.
- 유저당 중복: `(coupon_id, user_id)` **유니크 제약**.
- 잔액 적립: 기존 `findByUserIdForUpdate` **비관적 락** 재사용.

이 규모(부하 측정용 단일 인스턴스)에서는 비관적 락이 가장 단순·안전·예측 가능하며, ADR-008과 일관된 "경합 자원은 비관적 락" 서사를 완성한다.

## 트레이드오프 / 후속

- **직렬화 오버헤드:** 같은 코드 고경합 시 대기 행렬이 생긴다. 이 프로젝트 규모에선 수용 가능하며, 실제 한계는 Phase D 부하 측정으로 확인한다.
- **초고경합은 범위 밖 → Redis (Phase E):** 인기 콘서트/선착순 쿠폰처럼 순간 수천 TPS가 몰리는 시나리오는 비관적 락의 직렬화가 병목이 된다. 이때는 `DECR` 같은 Redis 원자 연산으로 차감을 DB 밖으로 빼는 것이 정석이며, 이는 Phase E 대기열(트래픽 보호 레이어)과 Redis 스택을 공유한다. **측정(Phase D)으로 필요성을 입증한 뒤** 도입한다.
- **충전 코어 분리:** `BookerAccount.charge()` + `PointTransaction(CHARGE)`를 진입 경로와 무관한 코어로 두었다. 미래 ADMIN 지급/PG 승인 경로가 같은 코어를 재사용한다(쿠폰 발급 API·ADMIN 권한 체계는 범위 밖).
