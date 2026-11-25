-- Usage: psql -v run=<RUN_ID>, run after the settle wait. One PASS or FAIL line per check (ADR 0009: sold units are PAID order lines).
\pset format unaligned
\pset tuples_only on
WITH target AS (
    SELECT p.id, s.on_hand, s.reserved
    FROM product p JOIN stock s ON s.product_id = p.id
    WHERE p.slug = 'flash-' || :'run'
),
sold AS (
    SELECT COALESCE(sum(ol.quantity), 0) AS units
    FROM order_line ol JOIN orders o ON o.id = ol.order_id AND o.status = 'PAID'
    WHERE ol.product_id = (SELECT id FROM target)
),
consumed AS (
    SELECT COALESCE(sum(r.quantity), 0) AS units
    FROM target t LEFT JOIN reservation r ON r.product_id = t.id AND r.status = 'CONSUMED'
),
active AS (
    SELECT COALESCE(sum(r.quantity), 0) AS units
    FROM target t LEFT JOIN reservation r ON r.product_id = t.id AND r.status = 'ACTIVE'
),
checks (position, name, ok, detail) AS (
    SELECT 0, 'product_exists', EXISTS (SELECT 1 FROM target), 'slug flash-' || :'run'
    UNION ALL SELECT 1, 'sold_at_most_100', (SELECT units FROM sold) <= 100, 'sold=' || (SELECT units FROM sold)
    UNION ALL SELECT 2, 'sold_equals_consumed', (SELECT units FROM sold) = (SELECT units FROM consumed),
        'sold=' || (SELECT units FROM sold) || ' consumed=' || (SELECT units FROM consumed)
    UNION ALL SELECT 3, 'sold_equals_100_minus_on_hand',
        EXISTS (SELECT 1 FROM target) AND (SELECT units FROM sold) = 100 - (SELECT on_hand FROM target),
        'sold=' || (SELECT units FROM sold) || ' on_hand=' || COALESCE((SELECT on_hand::text FROM target), 'none')
    UNION ALL SELECT 4, 'reserved_equals_active_reservations',
        EXISTS (SELECT 1 FROM target) AND (SELECT reserved FROM target) = (SELECT units FROM active),
        'reserved=' || COALESCE((SELECT reserved::text FROM target), 'none') || ' active=' || (SELECT units FROM active)
    UNION ALL SELECT 5, 'sold_something', (SELECT units FROM sold) > 0, 'sold=' || (SELECT units FROM sold)
)
SELECT CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END || ' ' || name || ' (' || detail || ')'
FROM checks ORDER BY position;
