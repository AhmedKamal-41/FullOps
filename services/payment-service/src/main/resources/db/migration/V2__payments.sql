-- Payment Service's own domain tables. See docs/ARCHITECTURE.md for the payment
-- lifecycle these constraints enforce, and docs/ARCHITECTURE.md
-- for why authorization outcomes are decided by simulator_rules instead of a real
-- payment network call.

-- One row per order Payment has decided on. order_id is UNIQUE: at most one
-- non-refunded payment can ever exist for an order, and REFUNDED is a status this
-- same row transitions into rather than a second row.
CREATE TABLE payments (
    payment_id             UUID PRIMARY KEY,
    order_id                UUID NOT NULL UNIQUE,
    customer_id             UUID NOT NULL,
    amount                  NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency_code           VARCHAR(3) NOT NULL,
    status                  VARCHAR(20) NOT NULL
                                 CHECK (status IN ('AUTHORIZED', 'DECLINED', 'REFUNDED')),
    decline_reason_code     VARCHAR(64),
    decline_reason_detail   VARCHAR(500),
    correlation_id          UUID NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_customer_id ON payments (customer_id);

-- Append-only audit trail of every attempt made against the (simulated) provider for
-- an order, including attempts that never reach payments at all because every one of
-- them failed. Keyed by order_id rather than payment_id because most attempts happen
-- before a payments row exists yet — see AuthorizationService.
CREATE TABLE payment_attempts (
    attempt_id       UUID PRIMARY KEY,
    order_id         UUID NOT NULL,
    attempt_number   INTEGER NOT NULL CHECK (attempt_number > 0),
    outcome          VARCHAR(20) NOT NULL
                          CHECK (outcome IN
                              ('APPROVED', 'DECLINED', 'TIMEOUT', 'TEMPORARY_ERROR', 'CIRCUIT_OPEN')),
    detail           VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, attempt_number)
);

CREATE INDEX idx_payment_attempts_order_id ON payment_attempts (order_id);

-- At most one refund per payment (a full refund of the original amount, not a
-- partial one) — enforced here at the database level as well as by RefundService's
-- own state check.
CREATE TABLE refunds (
    refund_id         UUID PRIMARY KEY,
    payment_id        UUID NOT NULL UNIQUE REFERENCES payments (payment_id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency_code       VARCHAR(3) NOT NULL,
    reason_code         VARCHAR(64) NOT NULL,
    correlation_id       UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ownership of a refund's Idempotency-Key is scoped to the operator/admin who sent
-- it, same shape as every other service's idempotency_requests table: the primary
-- key is what makes a replayed request either a safe replay (fingerprint matches) or
-- a rejected conflict (see RefundService).
CREATE TABLE idempotency_requests (
    actor_id             VARCHAR(255) NOT NULL,
    idempotency_key       VARCHAR(255) NOT NULL,
    request_fingerprint    VARCHAR(64) NOT NULL,
    refund_id              UUID NOT NULL REFERENCES refunds (refund_id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, idempotency_key)
);

-- The simulator's deterministic, documented decline/timeout rule table: the order
-- amount doubles as a "test token", the same magic-amount convention real card
-- processor sandboxes use (e.g. Adyen's per-amount test result codes) so that no
-- fictional token or card field ever has to travel through an event payload. Seeded
-- with a fixed set of demo amounts below; any amount not listed here approves.
-- failing_attempts is only meaningful for TIMEOUT/TEMPORARY_ERROR rows: attempt
-- numbers up to and including it fail with that outcome, and the next attempt
-- approves — 0 means it never recovers on its own.
CREATE TABLE simulator_rules (
    rule_id          UUID PRIMARY KEY,
    match_amount     NUMERIC(12, 2) NOT NULL UNIQUE,
    outcome          VARCHAR(30) NOT NULL
                          CHECK (outcome IN (
                              'APPROVE', 'DECLINE_INSUFFICIENT_FUNDS', 'DECLINE_CARD_DECLINED',
                              'TIMEOUT', 'TEMPORARY_ERROR'
                          )),
    failing_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failing_attempts >= 0),
    description      VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO simulator_rules (rule_id, match_amount, outcome, failing_attempts, description) VALUES
    (gen_random_uuid(), 1.00, 'DECLINE_INSUFFICIENT_FUNDS', 0,
        'Fictional demo/test amount: always a deterministic insufficient-funds decline.'),
    (gen_random_uuid(), 2.00, 'DECLINE_CARD_DECLINED', 0,
        'Fictional demo/test amount: always a deterministic card-declined decline.'),
    (gen_random_uuid(), 9997.00, 'TIMEOUT', 2,
        'Fictional demo/test amount: the first 2 attempts time out, the 3rd approves — for demonstrating retry recovery.'),
    (gen_random_uuid(), 9998.00, 'TEMPORARY_ERROR', 0,
        'Fictional demo/test amount: every attempt is a transient technical error, for demonstrating retry exhaustion and the circuit breaker opening.'),
    (gen_random_uuid(), 9999.00, 'TIMEOUT', 0,
        'Fictional demo/test amount: every attempt times out, for demonstrating retry exhaustion and the circuit breaker opening.');

-- Payment Service's own local projection of the order facts it needs, built from
-- consuming order-service's OrderPlaced.v1 (the sanctioned "read events, don't read
-- another service's tables" pattern — see docs/ARCHITECTURE.md).
-- Deliberately holds only order id, customer id, currency, and amount — never line
-- items, never anything card- or PII-shaped.
CREATE TABLE order_payment_context (
    order_id        UUID PRIMARY KEY,
    customer_id     UUID NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency_code   VARCHAR(3) NOT NULL,
    correlation_id  UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
