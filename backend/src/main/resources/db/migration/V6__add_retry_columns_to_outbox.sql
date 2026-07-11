ALTER TABLE outbox_entries
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE outbox_entries
    ADD COLUMN next_retry_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_status_retry ON outbox_entries (status, next_retry_at, created_at);
