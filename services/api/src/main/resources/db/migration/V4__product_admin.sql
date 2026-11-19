SET LOCAL lock_timeout = '5s';

ALTER TABLE product ADD COLUMN version bigint NOT NULL DEFAULT 0;
