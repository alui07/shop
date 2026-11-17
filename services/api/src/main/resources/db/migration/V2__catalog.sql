CREATE TABLE category (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug text NOT NULL UNIQUE,
    name text NOT NULL
);

CREATE TABLE product (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug text NOT NULL UNIQUE,
    name text NOT NULL,
    description text NOT NULL,
    price_minor bigint NOT NULL CHECK (price_minor >= 0),
    currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    category_id bigint NOT NULL REFERENCES category (id),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', name), 'A') || setweight(to_tsvector('english', description), 'B')
    ) STORED
);

CREATE INDEX product_search_vector_idx ON product USING gin (search_vector);
CREATE INDEX product_category_id_idx ON product (category_id);
