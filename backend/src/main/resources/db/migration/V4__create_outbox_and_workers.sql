CREATE TYPE outbox_status AS ENUM ('PENDING', 'RELAYED', 'FAILED');
CREATE TYPE worker_status AS ENUM ('HEALTHY', 'UNHEALTHY', 'PAUSED');

CREATE TABLE outbox_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE REFERENCES jobs(id) ON DELETE CASCADE,
    priority        job_priority NOT NULL DEFAULT 'MEDIUM',
    status          outbox_status NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status ON outbox_entries(status);
CREATE INDEX idx_outbox_created_at ON outbox_entries(created_at);

CREATE TABLE workers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hostname            VARCHAR(255) NOT NULL UNIQUE,
    status              worker_status NOT NULL DEFAULT 'HEALTHY',
    last_heartbeat_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    current_job_id      UUID,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
