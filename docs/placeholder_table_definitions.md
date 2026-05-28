# PlaceHolder — 테이블 정의서

> DBMS: MySQL 8.0  
> 작성일: 2026-05-26  
> 상태: 논리적 설계 단계

---

## 목차

1. [users](#1-users)
2. [booker_accounts](#2-booker_accounts)
3. [provider_accounts](#3-provider_accounts)
4. [events](#4-events)
5. [seats](#5-seats)
6. [reservations](#6-reservations)
7. [point_transactions](#7-point_transactions)

---

## 1. users

| 항목       | 내용                                    |
|----------|---------------------------------------|
| **테이블명** | users                                 |
| **설명**   | 서비스 사용자. 역할(role)에 따라 예약자/제공자/관리자로 구분 |

| # | 컬럼명           | 데이터 타입       | NULL | 기본값   | 제약조건               | 비고                          |
|---|---------------|--------------|------|-------|--------------------|-----------------------------|
| 1 | id            | BIGINT       | NO   | -     | PK, AUTO_INCREMENT |                             |
| 2 | email         | VARCHAR(255) | NO   | -     | UNIQUE             | 로그인 식별자                     |
| 3 | password_hash | VARCHAR(255) | NO   | -     |                    | BCrypt 해시                   |
| 4 | role          | VARCHAR(20)  | NO   | -     |                    | BOOKER \| PROVIDER \| ADMIN |
| 5 | created_at    | DATETIME     | NO   | NOW() |                    |                             |
| 6 | deleted_at    | DATETIME     | YES  | NULL  |                    | NULL이면 활성. 값 있으면 탈퇴 처리된 계정  |

**인덱스**

| 인덱스명           | 컬럼    | 종류      | 비고     |
|----------------|-------|---------|--------|
| PK             | id    | PRIMARY |        |
| uq_users_email | email | UNIQUE  | 로그인 조회 |

---

## 2. booker_accounts

| 항목       | 내용                                       |
|----------|------------------------------------------|
| **테이블명** | booker_accounts                          |
| **설명**   | 예약자 포인트 잔액. role=BOOKER인 user에 한해 1:1 생성 |

| # | 컬럼명     | 데이터 타입 | NULL | 기본값 | 제약조건                  | 비고        |
|---|---------|--------|------|-----|-----------------------|-----------|
| 1 | id      | BIGINT | NO   | -   | PK, AUTO_INCREMENT    |           |
| 2 | user_id | BIGINT | NO   | -   | UNIQUE, FK → users.id |           |
| 3 | balance | INT    | NO   | 0   | CHECK (balance >= 0)  | 사용 가능 포인트 |

**인덱스**

| 인덱스명                       | 컬럼      | 종류      | 비고 |
|----------------------------|---------|---------|----|
| PK                         | id      | PRIMARY |    |
| uq_booker_accounts_user_id | user_id | UNIQUE  |    |

---

## 3. provider_accounts

| 항목       | 내용                                                         |
|----------|------------------------------------------------------------|
| **테이블명** | provider_accounts                                          |
| **설명**   | 제공자 정산 예정액. role=PROVIDER인 user에 한해 1:1 생성. 현금 출금은 구현 범위 외 |

| # | 컬럼명                | 데이터 타입 | NULL | 기본값 | 제약조건                            | 비고        |
|---|--------------------|--------|------|-----|---------------------------------|-----------|
| 1 | id                 | BIGINT | NO   | -   | PK, AUTO_INCREMENT              |           |
| 2 | user_id            | BIGINT | NO   | -   | UNIQUE, FK → users.id           |           |
| 3 | settlement_balance | INT    | NO   | 0   | CHECK (settlement_balance >= 0) | 정산 예정액 집계 |

**인덱스**

| 인덱스명                         | 컬럼      | 종류      | 비고 |
|------------------------------|---------|---------|----|
| PK                           | id      | PRIMARY |    |
| uq_provider_accounts_user_id | user_id | UNIQUE  |    |

---

## 4. events

| 항목       | 내용                                        |
|----------|-------------------------------------------|
| **테이블명** | events                                    |
| **설명**   | 제공자가 등록하는 행사. 같은 장소라도 시간대가 다르면 별도 이벤트로 등록 |

| # | 컬럼명         | 데이터 타입       | NULL | 기본값   | 제약조건               | 비고                  |
|---|-------------|--------------|------|-------|--------------------|---------------------|
| 1 | id          | BIGINT       | NO   | -     | PK, AUTO_INCREMENT |                     |
| 2 | provider_id | BIGINT       | NO   | -     | FK → users.id      | role=PROVIDER인 user |
| 3 | title       | VARCHAR(255) | NO   | -     |                    | 행사명                 |
| 4 | venue       | VARCHAR(255) | NO   | -     |                    | 장소명 텍스트             |
| 5 | event_at    | DATETIME     | NO   | -     |                    | 행사 일시               |
| 6 | created_at  | DATETIME     | NO   | NOW() |                    |                     |

**인덱스**

| 인덱스명                   | 컬럼          | 종류      | 비고             |
|------------------------|-------------|---------|----------------|
| PK                     | id          | PRIMARY |                |
| idx_events_provider_id | provider_id | INDEX   | 제공자별 이벤트 목록 조회 |

---

## 5. seats

| 항목       | 내용                                               |
|----------|--------------------------------------------------|
| **테이블명** | seats                                            |
| **설명**   | 이벤트에 종속된 좌석. 동시성 제어의 핵심 테이블. Hold 상태를 Seat 행에 흡수 |

| # | 컬럼명        | 데이터 타입      | NULL | 기본값         | 제약조건               | 비고                             |
|---|------------|-------------|------|-------------|--------------------|--------------------------------|
| 1 | id         | BIGINT      | NO   | -           | PK, AUTO_INCREMENT |                                |
| 2 | event_id   | BIGINT      | NO   | -           | FK → events.id     |                                |
| 3 | label      | VARCHAR(50) | NO   | -           |                    | A1, B3 등 좌석 식별자                |
| 4 | price      | INT         | NO   | -           | CHECK (price > 0)  | 포인트 단위                         |
| 5 | status     | VARCHAR(20) | NO   | 'AVAILABLE' |                    | AVAILABLE \| HELD \| CONFIRMED |
| 6 | held_by    | BIGINT      | YES  | NULL        | FK → users.id      | 홀드 중인 예약자. HELD 상태일 때만 값 존재    |
| 7 | held_until | DATETIME    | YES  | NULL        |                    | 홀드 만료 시각. HELD 상태일 때만 값 존재     |

**인덱스**

| 인덱스명                        | 컬럼                   | 종류      | 비고                                                             |
|-----------------------------|----------------------|---------|----------------------------------------------------------------|
| PK                          | id                   | PRIMARY |                                                                |
| uq_seats_event_label        | (event_id, label)    | UNIQUE  | 동일 이벤트 내 좌석 라벨 중복 방지. 동시성 제어 핵심 제약                             |
| idx_seats_status_held_until | (status, held_until) | INDEX   | 만료 홀드 스캔 쿼리 최적화 (`WHERE status='HELD' AND held_until < NOW()`) |

**도메인 규칙**

- `status=AVAILABLE`: `held_by`, `held_until` 모두 NULL
- `status=HELD`: `held_by`, `held_until` 모두 NOT NULL. `held_until` 경과 시 AVAILABLE로 복귀
- `status=CONFIRMED`: `held_by`, `held_until` NULL로 초기화. reservations 레코드 존재

---

## 6. reservations

| 항목       | 내용                                               |
|----------|--------------------------------------------------|
| **테이블명** | reservations                                     |
| **설명**   | 확정 예약. 홀드 → 확정 트랜잭션 성공 시 생성. 포인트 차감 완료 상태의 영구 기록 |

| # | 컬럼명          | 데이터 타입   | NULL | 기본값   | 제약조건                    | 비고                              |
|---|--------------|----------|------|-------|-------------------------|---------------------------------|
| 1 | id           | BIGINT   | NO   | -     | PK, AUTO_INCREMENT      |                                 |
| 2 | booker_id    | BIGINT   | NO   | -     | FK → users.id           |                                 |
| 3 | seat_id      | BIGINT   | NO   | -     | UNIQUE, FK → seats.id   | 좌석당 확정 예약 최대 1개                 |
| 4 | paid_amount  | INT      | NO   | -     | CHECK (paid_amount > 0) | 확정 시점 포인트. 이후 price 변경과 무관하게 보존 |
| 5 | confirmed_at | DATETIME | NO   | NOW() |                         |                                 |

**인덱스**

| 인덱스명                       | 컬럼        | 종류      | 비고              |
|----------------------------|-----------|---------|-----------------|
| PK                         | id        | PRIMARY |                 |
| uq_reservations_seat_id    | seat_id   | UNIQUE  | 좌석당 확정 예약 1개 보장 |
| idx_reservations_booker_id | booker_id | INDEX   | 예약자별 예약 내역 조회   |

---

## 7. point_transactions

| 항목       | 내용                                                    |
|----------|-------------------------------------------------------|
| **테이블명** | point_transactions                                    |
| **설명**   | 포인트 변동 이력. 예약 확정 시 DEDUCT(예약자) + SETTLE(제공자) 2건 동시 생성 |

| # | 컬럼명            | 데이터 타입      | NULL | 기본값   | 제약조건                 | 비고                                    |
|---|----------------|-------------|------|-------|----------------------|---------------------------------------|
| 1 | id             | BIGINT      | NO   | -     | PK, AUTO_INCREMENT   |                                       |
| 2 | user_id        | BIGINT      | NO   | -     | FK → users.id        |                                       |
| 3 | type           | VARCHAR(20) | NO   | -     |                      | CHARGE \| DEDUCT \| SETTLE            |
| 4 | amount         | INT         | NO   | -     | CHECK (amount > 0)   | 항상 양수. 차감/정산 방향은 type으로 구분            |
| 5 | reservation_id | BIGINT      | YES  | NULL  | FK → reservations.id | CHARGE는 NULL. DEDUCT/SETTLE은 연관 예약 ID |
| 6 | created_at     | DATETIME    | NO   | NOW() |                      |                                       |

**인덱스**

| 인덱스명                  | 컬럼             | 종류      | 비고             |
|-----------------------|----------------|---------|----------------|
| PK                    | id             | PRIMARY |                |
| idx_pt_user_id        | user_id        | INDEX   | 사용자별 포인트 내역 조회 |
| idx_pt_reservation_id | reservation_id | INDEX   | 예약별 트랜잭션 조회    |

---

## 테이블 관계 요약

| 관계                                | 카디널리티 | 비고                |
|-----------------------------------|-------|-------------------|
| users → events                    | 1:N   | provider_id       |
| users → reservations              | 1:N   | booker_id         |
| users → booker_accounts           | 1:1   | role=BOOKER만 생성   |
| users → provider_accounts         | 1:1   | role=PROVIDER만 생성 |
| events → seats                    | 1:N   |                   |
| seats → reservations              | 1:1   | 좌석당 확정 예약 최대 1개   |
| reservations → point_transactions | 1:N   | 확정 시 2건 생성        |

