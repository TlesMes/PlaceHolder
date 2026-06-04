# ADR-005. 탈퇴 처리 전략 — Soft Delete + 명시적 삭제, Cascade 없음

## 상태

확정

## 컨텍스트

회원 탈퇴 시 관련 데이터(예약, 결제 이력 등) 처리 방식을 결정해야 한다.
JPA cascade / DB cascade / 명시적 삭제 중 어떤 방식을 쓸지도 결정이 필요하다.

## 결정

- 탈퇴 처리는 Soft Delete (`users.deleted_at`) 로 처리한다.
- 개인정보(email, password_hash)는 탈퇴 시점에 비식별화한다.
- JPA cascade와 DB cascade 모두 사용하지 않는다.
- 삭제가 필요한 시점에 JPQL bulk delete로 순서를 명시적으로 제어한다.

## 근거

- 전자상거래법상 결제/예약 이력은 5년 보존 의무가 있어 hard delete 불가.
- Soft delete 적용 시 JPA cascade remove를 같이 쓰면 부모는 soft delete, 자식은 hard delete되는 불일치 발생.
- JPA cascade는 객체 하나씩 delete 쿼리를 날려 대량 삭제 시 성능이 매우 느리다.
- DB cascade는 Spring 레이어 밖에서 동작해 제어권이 분산된다.
- 명시적 순서 제어(자식 → 부모 순서로 bulk delete)가 가장 안전하고 명확하다.

## 삭제 처리 패턴

```java
// 예시: Event 삭제 시
@Modifying
@Query("DELETE FROM Seat s WHERE s.event.id = :eventId")
void deleteByEventId(Long eventId);  // 1. 자식(Seat) 먼저

eventRepository.deleteById(eventId); // 2. 부모(Event) 삭제
```

## 탈퇴 처리 흐름

```
탈퇴 요청
→ users.deleted_at = NOW()
→ 개인정보 비식별화 (email → deleted_{id}@deleted.com 등)
→ N년 후 배치 스케줄러가 보존 기한 만료 레코드 hard delete
   (Reservation → PointTransaction 순서로 명시적 삭제)
```

## 트레이드오프

- 삭제 로직을 직접 작성해야 해서 코드가 늘어난다.
- 대신 삭제 순서와 범위가 명확하게 드러나고, soft delete와의 충돌이 없다.
