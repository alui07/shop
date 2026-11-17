CREATE TABLE schema_info (
    id integer PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO schema_info (id) VALUES (1);
