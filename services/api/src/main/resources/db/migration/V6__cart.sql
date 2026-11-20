SET LOCAL lock_timeout = '5s';

CREATE TABLE cart (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_subject text NOT NULL UNIQUE,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL
);

CREATE TABLE cart_line (
    cart_id bigint NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    product_id bigint NOT NULL REFERENCES product (id),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    added_at timestamptz NOT NULL,
    PRIMARY KEY (cart_id, product_id)
);
