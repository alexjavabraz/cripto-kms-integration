--liquibase formatted sql

--changeset dpl_devops_liquibase:001_create_request_log
CREATE TABLE IF NOT EXISTS request_log (
    id              UUID                        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255)                NOT NULL,
    type            VARCHAR(50)                 NOT NULL,
    status          VARCHAR(20)                 NOT NULL DEFAULT 'RECEIVED',
    response_queue  VARCHAR(512),
    payload         TEXT,
    response        TEXT,
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_request_log_idempotency_key ON request_log(idempotency_key);
CREATE INDEX idx_request_log_type            ON request_log(type);
CREATE INDEX idx_request_log_status          ON request_log(status);
CREATE INDEX idx_request_log_created_at      ON request_log(created_at);

--rollback DROP TABLE IF EXISTS request_log;
