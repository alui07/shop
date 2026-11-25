-- Usage: psql -v run=<RUN_ID>. Counters and connection figures for the cache measurement and the connection budget (C4).
\pset format unaligned
\pset tuples_only on
SELECT 'run ' || :'run';
SELECT 'xact_commit ' || xact_commit || ' tup_returned ' || tup_returned || ' blks_hit ' || blks_hit || ' blks_read ' || blks_read
FROM pg_stat_database WHERE datname = current_database();
SELECT 'connections_user_shop ' || count(*) FROM pg_stat_activity WHERE usename = 'shop';
SELECT 'max_connections ' || current_setting('max_connections');
