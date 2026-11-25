-- Usage: psql -v run=<RUN_ID>. Creates the flash-sale product with 100 units; any earlier lt-/flash- product is deactivated.
BEGIN;
UPDATE product SET active = false, version = version + 1
WHERE active AND (slug LIKE 'lt-%' OR slug LIKE 'flash-%');
INSERT INTO category (slug, name) VALUES ('loadtest', 'Load test') ON CONFLICT (slug) DO NOTHING;
WITH created AS (
    INSERT INTO product (slug, name, description, price_minor, currency, category_id, active)
    SELECT 'flash-' || :'run', 'Flash sale ' || :'run', 'Limited stock for the load test flash sale.', 1990, 'EUR', c.id, true
    FROM category c WHERE c.slug = 'loadtest'
    RETURNING id
)
INSERT INTO stock (product_id, on_hand) SELECT id, 100 FROM created;
COMMIT;
