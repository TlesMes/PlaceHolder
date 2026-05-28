# PlaceHolder

> 좌석 제공자가 등록한 좌석을 예약자가 포인트로 예약하는 양면 마켓플레이스.  
> 동시 요청이 몰리는 좌석 점유 시나리오에서 정합성을 보장하는 동시성 제어를 핵심 주제로 한다.

**포트폴리오 타겟:** 실시간 처리 / 동시성이 핵심인 서비스 회사 (핀테크, 커머스 플랫폼, 협업 도구)  
**개발 기간:** 2026.05 ~  
**참여 인원:** 1인 (백엔드 단독)

---

## 기술 스택

| 분류        | 기술                             |
|-----------|--------------------------------|
| Language  | Java 17                        |
| Framework | Spring Boot 3, Spring Security |
| ORM       | Spring Data JPA (Hibernate)    |
| DB        | MySQL 8.0                      |
| Auth      | JWT (jjwt 0.12.3)              |
| Test      | JUnit 5, AssertJ               |
| Infra     | AWS EC2, GitHub Actions CI     |

---

## 핵심 설계 포인트

### 1. 좌석 점유 동시성 제어

같은 `(event_id, seat_id)`에 동시 요청이 몰릴 때 한 명만 성공하도록 보장한다.  
락 단위를 전역이 아닌 **(이벤트, 좌석) 행 단위**로 좁혀 불필요한 경합을 최소화한다.  
락 전략 선택 근거는 ADR로 문서화한다.

### 2. 확정 트랜잭션 원자성

예약 확정 시 아래 세 작업을 단일 트랜잭션으로 처리한다.

```
[예약자 포인트 차감] + [제공자 정산예정액 적립] + [좌석 상태 CONFIRMED]
```

잔액 부족 시 홀드를 해제하고 실패를 반환한다.

### 3. 홀드 → 확정 → 자동 만료 흐름

```
AVAILABLE → (홀드 요청) → HELD → (확정) → CONFIRMED
                              ↓ TTL 만료
                          AVAILABLE 복귀
```

포인트 차감은 홀드 시점이 아닌 **확정 시점**에만 발생한다.  
홀드는 좌석 점유만 담당하며, 만료 시 자동으로 해제된다.

### 4. 정산 ≠ 지급 (법적 맥락)

실제 티켓 플랫폼의 제공자 정산은 PG 정산·결제대금예치 레일을 통한 계좌 송금으로,  
전자금융업·결제대금예치업 라이선스가 필요한 영역이다.  
이 프로젝트에서 제공자에게 흐르는 포인트는 **정산 예정액 집계**로 모델링하며,  
실제 현금 출금/송금은 의도적으로 구현 범위에서 제외한다.

---

## 프로젝트 구조

```
PlaceHolder/
├── docs/
│   ├── adr/                  # Architecture Decision Records
│   │   ├── ADR-001-hold-modeling.md
│   │   ├── ADR-002-point-account.md
│   │   ├── ADR-003-user-role.md
│   │   └── ADR-004-queue.md
│   ├── sql/
│   │   └── schema.sql        # DDL
│   ├── schema.dbml           # DBML (dbdiagram.io)
│   └── table_definitions.md  # 테이블 정의서
└── src/
```

---

## 설계 문서

| 문서      | 링크                                                     |
|---------|--------------------------------------------------------|
| 테이블 정의서 | [docs/table_definitions.md](docs/table_definitions.md) |
| DDL     | [docs/sql/schema.sql](docs/sql/schema.sql)             |
| DBML    | [docs/schema.dbml](docs/schema.dbml)                   |
| ADR 목록  | [docs/adr/](docs/adr/)                                 |

---

## 실행 방법

> 구현 진행 후 업데이트 예정

---

## 면접 포인트

- **동시성 제어:** 같은 좌석에 동시 N건 요청이 몰릴 때 좌석/포인트/정산이 깨지지 않음을 테스트로 증명
- **트랜잭션 설계:** 두 계정에 걸친 정산 원자성, `@Transactional` 전파/격리 실전 적용
- **TeamMoa 연결:** TeamMoa에서 Last Write Wins로 처리했던 충돌을 이번엔 명시적 락 전략으로 정합성 보장
- **도메인 깊이:** 정산 구조 설계 과정에서 전자금융업 규제 맥락까지 파악하고 의도적으로 범위 설정