-- Usage: psql -v run=<RUN_ID>. Prints the number of PENDING_PAYMENT orders that include the flash-sale product.
\pset format unaligned
\pset tuples_only on
SELECT count(DISTINCT o.id)
FROM orders o
JOIN order_line ol ON ol.order_id = o.id
JOIN product p ON p.id = ol.product_id
WHERE o.status = 'PENDING_PAYMENT' AND p.slug = 'flash-' || :'run';
