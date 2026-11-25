-- Usage: psql -v run=<RUN_ID>. Idempotent; does nothing when the setup never ran.
UPDATE product SET active = false, version = version + 1
WHERE active AND slug LIKE 'lt-' || :'run' || '-%';
