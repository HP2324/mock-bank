-- One row per Kafka audit event. event_id is the producer's UUID, so redelivered messages
-- (at-least-once delivery) are ignored instead of duplicated.
CREATE TABLE consumed_audit_events (
    event_id        UUID         PRIMARY KEY,
    event_type      VARCHAR(40)  NOT NULL,
    operator_id     VARCHAR(40),
    member_id       VARCHAR(40),
    account_number  VARCHAR(40),
    message         VARCHAR(500) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    kafka_partition INT          NOT NULL,
    kafka_offset    BIGINT       NOT NULL,
    consumed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX consumed_audit_events_occurred_idx ON consumed_audit_events (occurred_at DESC);
CREATE INDEX consumed_audit_events_type_idx ON consumed_audit_events (event_type);
