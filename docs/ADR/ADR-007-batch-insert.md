# ADR-007. 좌석 일괄 생성 — 반복 INSERT vs Batch INSERT

## 상태

확정

## 컨텍스트

이벤트 등록 시 좌석을 N개 동시에 생성해야 한다. 좌석 수는 요청 DTO에 따라 수십~수백 개까지 가능하다.
두 가지 방식이 있다.

- **반복 INSERT**: `seatRepository.save(seat)`를 루프로 N번 호출 → N번의 DB 왕복
- **Batch INSERT**: `seatRepository.saveAll(seats)` + JDBC batch 설정 → 1번의 DB 왕복(또는 최소화)

## 결정

`saveAll()`을 사용하고, Hibernate batch insert 설정을 활성화한다.

```yaml
# application.yml
spring.jpa.properties.hibernate:
  jdbc.batch_size: 50
  order_inserts: true
```

```java
// SeatService
List<Seat> seats = seatMetadata.stream()
        .map(dto -> Seat.builder()...build())
        .toList();
seatRepository.saveAll(seats);
```

## 근거

- 반복 `save()`는 좌석 수만큼 네트워크 왕복이 발생한다. 100석 이벤트면 100번의 INSERT 요청이다.
- `saveAll()` + `order_inserts: true`는 Hibernate가 INSERT를 모아 한 번에 실행하도록 정렬한다.
- 이벤트 등록은 성능 민감 경로는 아니지만, 불필요한 N번 왕복을 막는 것은 기본적인 Best Practice다.

## 트레이드오프

MySQL에서 실제 JDBC 수준 batch가 동작하려면 JDBC URL에 `rewriteBatchedStatements=true`가 필요하다.
현재 미설정 상태로, Hibernate는 INSERT를 정렬·묶어서 보내지만 드라이버가 개별 statement로 처리할 수 있다.
정확한 batch 효과를 측정하려면 Phase D 부하 테스트 단계에서 `rewriteBatchedStatements=true` 추가 후 수치를 비교한다.
