-- Usage: psql -v run=<RUN_ID>. Deactivates every earlier lt-/flash- product, then creates five products with 1 000 000 units.
BEGIN;
UPDATE product SET active = false, version = version + 1
WHERE active AND (slug LIKE 'lt-%' OR slug LIKE 'flash-%');
INSERT INTO category (slug, name) VALUES ('loadtest', 'Load test') ON CONFLICT (slug) DO NOTHING;
WITH created AS (
    INSERT INTO product (slug, name, description, price_minor, currency, category_id, active)
    SELECT 'lt-' || :'run' || '-' || n, 'Load test product ' || n, 'Plentiful stock for the load test checkout.',
           500 + n * 250, 'EUR', c.id, true
    FROM category c CROSS JOIN generate_series(1, 5) AS n
    WHERE c.slug = 'loadtest'
    RETURNING id
)
INSERT INTO stock (product_id, on_hand) SELECT id, 1000000 FROM created;
COMMIT;
