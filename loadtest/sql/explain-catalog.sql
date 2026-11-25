-- Usage: psql -v run=<RUN_ID>. EXPLAIN (ANALYZE, BUFFERS) of the catalog list query for each sort (ADR 0003 query-plan evidence).
-- The queries mirror ProductRepository: the active filter, the sort's ORDER BY, page 0 of 12; relevance with a text search.
\echo === name
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.slug, p.name, p.price_minor, p.currency, c.slug, c.name
FROM product p JOIN category c ON c.id = p.category_id WHERE p.active
ORDER BY p.name, p.slug LIMIT 12 OFFSET 0;
\echo === price_asc
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.slug, p.name, p.price_minor, p.currency, c.slug, c.name
FROM product p JOIN category c ON c.id = p.category_id WHERE p.active
ORDER BY p.price_minor, p.name, p.slug LIMIT 12 OFFSET 0;
\echo === price_desc
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.slug, p.name, p.price_minor, p.currency, c.slug, c.name
FROM product p JOIN category c ON c.id = p.category_id WHERE p.active
ORDER BY p.price_minor DESC, p.name, p.slug LIMIT 12 OFFSET 0;
\echo === name in category kitchen
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.slug, p.name, p.price_minor, p.currency, c.slug, c.name
FROM product p JOIN category c ON c.id = p.category_id WHERE p.active AND c.slug = 'kitchen'
ORDER BY p.name, p.slug LIMIT 12 OFFSET 0;
\echo === relevance for 'oak board'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.slug, p.name, p.price_minor, p.currency, c.slug, c.name
FROM product p JOIN category c ON c.id = p.category_id
WHERE p.active AND p.search_vector @@ websearch_to_tsquery('english', 'oak board')
ORDER BY ts_rank(p.search_vector, websearch_to_tsquery('english', 'oak board')) DESC, p.name, p.slug LIMIT 12 OFFSET 0;
\echo === count for the list
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM product p JOIN category c ON c.id = p.category_id WHERE p.active;
