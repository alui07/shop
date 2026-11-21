SET LOCAL lock_timeout = '5s';

-- The Spring Modulith 2.1 event publication registry (schema v2), created here instead of at startup.
CREATE TABLE event_publication (
    id                     uuid NOT NULL,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

-- Publications that failed for about a day are parked here; resubmit by hand with INSERT ... SELECT.
CREATE TABLE event_publication_dead_letter (
    id                     uuid NOT NULL,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz,
    moved_at               timestamptz NOT NULL,
    PRIMARY KEY (id)
);
