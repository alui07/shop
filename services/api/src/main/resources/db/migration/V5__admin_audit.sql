SET LOCAL lock_timeout = '5s';

CREATE TABLE admin_audit_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_subject text NOT NULL,
    actor_name text NOT NULL,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id bigint NOT NULL,
    changes jsonb NOT NULL,
    trace_id text
);

CREATE FUNCTION refuse_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_event is append-only (% refused)', TG_OP;
END;
$$;

CREATE TRIGGER admin_audit_event_append_only
    BEFORE UPDATE OR DELETE ON admin_audit_event
    FOR EACH ROW EXECUTE FUNCTION refuse_audit_change();

CREATE TRIGGER admin_audit_event_no_truncate
    BEFORE TRUNCATE ON admin_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_audit_change();
