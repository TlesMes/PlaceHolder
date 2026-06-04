# ADR-006. 회원가입 트랜잭션 — 계정 생성 범위

## 상태

확정

## 컨텍스트

BOOKER 또는 PROVIDER로 회원가입할 때, User 생성과 함께 BookerAccount/ProviderAccount도 생성해야 한다.
두 가지 방식이 있다.

- **단일 트랜잭션 포함**: 회원가입 요청 하나에서 User + Account를 원자적으로 생성
- **이벤트 분리**: User를 생성하고, 도메인 이벤트(또는 메시지)로 Account 생성을 비동기 처리

## 결정

User 생성과 BookerAccount/ProviderAccount 생성을 **단일 `@Transactional` 경계 안에 포함**한다.

```java
// AuthService.signup()
User savedUser = userRepository.save(user);              // 1
bookerAccountRepository.save(BookerAccount.builder()...); // 2 — 같은 트랜잭션
```

## 근거

- Account 없는 User가 DB에 남으면 이후 포인트 차감·정산 로직에서 NPE/데이터 정합성 오류가 발생한다. 원자성이 필수다.
- 이 프로젝트에는 이벤트 버스(Kafka, Spring ApplicationEvent 등) 인프라가 없다. 이벤트 분리를 도입하면 복잡도만 늘고 얻을 이점이 없다.
- 회원가입 트랜잭션은 쓰기가 최대 2~3 row(User + Account)로 짧고, 성능 문제가 발생할 규모가 아니다.

## 트레이드오프

Account 생성 로직이 회원가입 트랜잭션에 묶인다. 향후 가입 시 추가 작업(웰컴 포인트 지급 등)이 생기면 트랜잭션이 길어질 수 있으며, 그 시점에 이벤트 분리를 재검토한다.
