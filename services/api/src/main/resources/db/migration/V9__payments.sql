SET LOCAL lock_timeout = '5s';

CREATE TABLE payment (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id),
    provider text NOT NULL,
    provider_payment_id text UNIQUE,
    redirect_url text,
    status text NOT NULL CHECK (status IN ('CREATED', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED')),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    currency text NOT NULL,
    received_amount_minor bigint,
    received_currency text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX payment_order_idx ON payment (order_id);
CREATE UNIQUE INDEX payment_one_open_per_order_idx ON payment (order_id) WHERE status = 'CREATED';

CREATE TABLE payment_webhook_event (
    event_id text PRIMARY KEY,
    type text NOT NULL,
    received_at timestamptz NOT NULL
);

CREATE INDEX payment_webhook_event_received_idx ON payment_webhook_event (received_at);

CREATE TABLE payment_webhook_unmatched (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text NOT NULL,
    provider_payment_id text NOT NULL,
    received_at timestamptz NOT NULL
);

CREATE INDEX payment_webhook_unmatched_received_idx ON payment_webhook_unmatched (received_at);

CREATE TABLE fake_psp_payment (
    provider_payment_id text PRIMARY KEY,
    token text NOT NULL UNIQUE,
    merchant_reference uuid NOT NULL UNIQUE,
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    currency text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'DECLINED', 'REFUNDED')),
    return_url text NOT NULL,
    event_id text UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    completed_at timestamptz
);

CREATE TABLE order_email (
    order_id uuid NOT NULL REFERENCES orders (id),
    kind text NOT NULL,
    status text NOT NULL CHECK (status IN ('SENDING', 'SENT', 'FAILED')),
    claimed_at timestamptz,
    sent_at timestamptz,
    PRIMARY KEY (order_id, kind)
);

ALTER TABLE orders
    ADD COLUMN payment_status text
        CHECK (payment_status IN ('CREATED', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED')),
    ADD COLUMN paid_payment_id uuid REFERENCES payment (id);
