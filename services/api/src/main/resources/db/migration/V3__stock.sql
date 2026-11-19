SET LOCAL lock_timeout = '5s';

CREATE TABLE stock (
    product_id bigint PRIMARY KEY REFERENCES product (id),
    on_hand integer NOT NULL CHECK (on_hand >= 0),
    reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version bigint NOT NULL DEFAULT 0,
    CHECK (reserved <= on_hand)
);

INSERT INTO stock (product_id, on_hand) SELECT id, 0 FROM product;
