-- PlaceHolder DDL
-- DBMS: MySQL 8.0
-- 작성일: 2026-05-27

CREATE TABLE users
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20) NOT NULL COMMENT 'BOOKER | PROVIDER | ADMIN',
    created_at    DATETIME    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
);

CREATE TABLE booker_accounts
(
    id      BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_booker_accounts_user_id (user_id),
    CONSTRAINT fk_booker_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_booker_accounts_balance CHECK (balance >= 0)
);

CREATE TABLE provider_accounts
(
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    user_id             BIGINT NOT NULL,
    settlement_balance  INT    NOT NULL DEFAULT 0,
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
    INDEX idx_events_provider_id (provider_id)
);

CREATE TABLE seats
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    event_id   BIGINT      NOT NULL,
    label      VARCHAR(50) NOT NULL,
    price      INT         NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE | HELD | CONFIRMED',
    held_by    BIGINT      NULL,
    held_until DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_seats_event_label (event_id, label),
    CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_seats_held_by FOREIGN KEY (held_by) REFERENCES users (id),
    CONSTRAINT chk_seats_price CHECK (price > 0),
    INDEX idx_seats_status_held_until (status, held_until)
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
    INDEX idx_reservations_booker_id (booker_id)
);

CREATE TABLE point_transactions
(
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    user_id        BIGINT   NOT NULL,
    type           VARCHAR(20) NOT NULL COMMENT 'CHARGE | DEDUCT | SETTLE',
    amount         INT      NOT NULL,
    reservation_id BIGINT   NULL,
    created_at     DATETIME NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT fk_pt_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_pt_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id),
    CONSTRAINT chk_pt_amount CHECK (amount > 0),
    INDEX idx_pt_user_id (user_id),
    INDEX idx_pt_reservation_id (reservation_id)
);