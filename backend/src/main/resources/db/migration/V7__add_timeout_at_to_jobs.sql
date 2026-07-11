ALTER TABLE jobs ADD COLUMN timeout_at TIMESTAMPTZ;

CREATE INDEX idx_jobs_timeout ON jobs (timeout_at)
    WHERE status = 'RUNNING' AND timeout_at IS NOT NULL;
