ALTER TABLE jobs ADD COLUMN next_fire_at TIMESTAMPTZ;

CREATE INDEX idx_jobs_scheduled_due ON jobs (scheduled_at)
    WHERE status = 'CREATED' AND scheduled_at IS NOT NULL;

CREATE INDEX idx_jobs_cron_due ON jobs (next_fire_at)
    WHERE status = 'CREATED' AND cron_expression IS NOT NULL AND next_fire_at IS NOT NULL;
