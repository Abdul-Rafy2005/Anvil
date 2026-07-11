CREATE TYPE job_status AS ENUM (
    'CREATED', 'QUEUED', 'RUNNING', 'FAILED', 'RETRYING',
    'FAILED_PERMANENTLY', 'CANCELLING', 'CANCELLED', 'COMPLETED'
);

CREATE TYPE job_priority AS ENUM ('HIGH', 'MEDIUM', 'LOW');

CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    job_type        VARCHAR(100) NOT NULL,
    payload         jsonb,
    status          job_status NOT NULL DEFAULT 'CREATED',
    priority        job_priority NOT NULL DEFAULT 'MEDIUM',
    progress_pct    INTEGER,
    progress_message VARCHAR(500),
    result          jsonb,
    error_message   TEXT,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    max_retries     INTEGER NOT NULL DEFAULT 4,
    scheduled_at    TIMESTAMPTZ,
    cron_expression VARCHAR(100),
    timeout_seconds INTEGER NOT NULL DEFAULT 600,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_jobs_user_id ON jobs(user_id);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_job_type ON jobs(job_type);
CREATE INDEX idx_jobs_created_at ON jobs(created_at);

CREATE TABLE job_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number  INTEGER NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    status          job_status NOT NULL,
    error           TEXT,
    stack_trace     TEXT,
    worker_id       UUID
);

CREATE INDEX idx_job_attempts_job_id ON job_attempts(job_id);

CREATE TABLE audit_log_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id   UUID NOT NULL,
    action          VARCHAR(100) NOT NULL,
    target_type     VARCHAR(100) NOT NULL,
    target_id       UUID NOT NULL,
    metadata        jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_actor ON audit_log_entries(actor_user_id);
CREATE INDEX idx_audit_log_target ON audit_log_entries(target_type, target_id);
CREATE INDEX idx_audit_log_created_at ON audit_log_entries(created_at);

CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    user_id         UUID NOT NULL REFERENCES users(id),
    job_id          UUID NOT NULL REFERENCES jobs(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_idempotency_keys_key ON idempotency_keys(idempotency_key);
CREATE INDEX idx_idempotency_keys_user ON idempotency_keys(user_id);
