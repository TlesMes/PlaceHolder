-- PlaceHolder DDL
-- DBMS: MySQL 8.0
-- 작성일: 2026-05-27

CREATE TABLE users
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL COMMENT 'BOOKER | PROVIDER | ADMIN',
    created_at    DATETIME     NOT NULL DEFAULT NOW(),
    deleted_at    DATETIME NULL COMMENT 'NULL이면 활성. 값 있으면 탈퇴 처리된 계정',
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
);

-- 잔액은 재원 계층별로 나눠 저장한다 (ADR-020).
-- 합계 컬럼은 두지 않는다 — 버킷에서 총합은 언제나 구할 수 있지만 총합에서 버킷은 복원할 수 없어,
-- 둘 다 저장하면 어느 한쪽만 갱신하는 코드가 생기는 순간 진실이 갈라진다.
CREATE TABLE booker_accounts
(
    id            BIGINT NOT NULL AUTO_INCREMENT,
    user_id       BIGINT NOT NULL,
    event_balance INT    NOT NULL DEFAULT 0 COMMENT '기간제 이벤트 쿠폰분 (환불 불가, 만료 미구현)',
    free_balance  INT    NOT NULL DEFAULT 0 COMMENT '무기한 쿠폰 상환분 (환불 불가)',
    paid_balance  INT    NOT NULL DEFAULT 0 COMMENT '현금 결제 충전분 (유일한 환불 재원)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_booker_accounts_user_id (user_id),
    CONSTRAINT fk_booker_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_booker_accounts_event CHECK (event_balance >= 0),
    CONSTRAINT chk_booker_accounts_free CHECK (free_balance >= 0),
    CONSTRAINT chk_booker_accounts_paid CHECK (paid_balance >= 0)
);

CREATE TABLE provider_accounts
(
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    user_id            BIGINT NOT NULL,
    settlement_balance INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_provider_accounts_user_id (user_id),
    CONSTRAINT fk_provider_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_provider_accounts_settlement CHECK (settlement_balance >= 0)
);

CREATE TABLE events
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    provider_id BIGINT       NOT NULL,
    title       VARCHAR(255) NOT NULL,
    venue       VARCHAR(255) NOT NULL,
    event_at    DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT fk_events_provider FOREIGN KEY (provider_id) REFERENCES users (id),
    INDEX       idx_events_provider_id (provider_id)
);

CREATE TABLE seats
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    event_id   BIGINT      NOT NULL,
    label      VARCHAR(50) NOT NULL,
    price      INT         NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE | HELD | CONFIRMED',
    held_by    BIGINT NULL,
    held_until DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_seats_event_label (event_id, label),
    CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_seats_held_by FOREIGN KEY (held_by) REFERENCES users (id),
    CONSTRAINT chk_seats_price CHECK (price > 0),
    INDEX      idx_seats_status_held_until (status, held_until)
);

CREATE TABLE reservations
(
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    booker_id    BIGINT   NOT NULL,
    seat_id      BIGINT   NOT NULL,
    paid_amount  INT      NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE KEY uq_reservations_seat_id (seat_id),
    CONSTRAINT fk_reservations_booker FOREIGN KEY (booker_id) REFERENCES users (id),
    CONSTRAINT fk_reservations_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT chk_reservations_paid_amount CHECK (paid_amount > 0),
    INDEX        idx_reservations_booker_id (booker_id)
);

CREATE TABLE point_transactions
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    type           VARCHAR(20) NOT NULL COMMENT 'CHARGE | DEDUCT | SETTLE | REFUND',
    amount         INT         NOT NULL,
    -- 이 거래가 각 재원 계층에서 얼마씩 움직였는지 (ADR-020).
    -- amount = bucket_event + bucket_free + bucket_paid 를 애플리케이션이 검증한다.
    -- 단 SETTLE(제공자 원장)은 재원 계층이라는 축이 없어 세 값이 모두 0이고 검증에서 제외된다.
    bucket_event   INT         NOT NULL DEFAULT 0,
    bucket_free    INT         NOT NULL DEFAULT 0,
    bucket_paid    INT         NOT NULL DEFAULT 0,
    reservation_id BIGINT NULL,
    created_at     DATETIME    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT fk_pt_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_pt_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id),
    CONSTRAINT chk_pt_amount CHECK (amount > 0),
    INDEX          idx_pt_user_id (user_id),
    INDEX          idx_pt_reservation_id (reservation_id)
);
