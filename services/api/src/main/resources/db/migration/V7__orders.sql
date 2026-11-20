SET LOCAL lock_timeout = '5s';

-- "orders": ORDER is a reserved word. All six statuses are allowed from the start so later slices only add code.
CREATE TABLE orders (
    id uuid PRIMARY KEY,
    customer_subject text NOT NULL,
    email text,
    status text NOT NULL CHECK (status IN
        ('PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED', 'REFUND_PENDING', 'REFUNDED')),
    total_minor bigint NOT NULL CHECK (total_minor >= 0),
    currency text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    paid_at timestamptz,
    late_payment boolean NOT NULL DEFAULT false
);

CREATE INDEX orders_customer_created_idx ON orders (customer_subject, created_at DESC, id);
CREATE INDEX orders_pending_expiry_idx ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE order_line (
    order_id uuid NOT NULL REFERENCES orders (id),
    line_no integer NOT NULL,
    product_id bigint NOT NULL REFERENCES product (id),
    product_slug text NOT NULL,
    product_name text NOT NULL,
    unit_price_minor bigint NOT NULL CHECK (unit_price_minor >= 0),
    currency text NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE reservation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id),
    product_id bigint NOT NULL REFERENCES stock (product_id),
    quantity integer NOT NULL CHECK (quantity > 0),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
    created_at timestamptz NOT NULL
);

CREATE INDEX reservation_order_idx ON reservation (order_id);
