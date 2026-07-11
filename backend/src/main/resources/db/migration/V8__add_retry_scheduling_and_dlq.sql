ALTER TABLE jobs ADD COLUMN next_retry_at TIMESTAMPTZ;
CREATE INDEX idx_jobs_retrying_due ON jobs(next_retry_at) WHERE status = 'RETRYING';

CREATE TABLE dead_letter_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE,
    job_type        VARCHAR(100) NOT NULL,
    user_id         UUID NOT NULL,
    reason          TEXT NOT NULL,
    failure_history JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_by     UUID,
    resolved_action VARCHAR(20),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX idx_dlq_created_at ON dead_letter_entries(created_at);
CREATE INDEX idx_dlq_job_type ON dead_letter_entries(job_type);
