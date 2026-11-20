SET LOCAL lock_timeout = '5s';

-- A replay renders the order's current state, so only the order id and the status are stored.
CREATE TABLE idempotency_key (
    customer_subject text NOT NULL,
    key uuid NOT NULL,
    request_hash text NOT NULL,
    response_status integer,
    order_id uuid,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (customer_subject, key)
);

CREATE INDEX idempotency_key_expires_idx ON idempotency_key (expires_at);
