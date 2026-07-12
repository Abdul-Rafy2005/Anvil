# Anvil — Product Requirements Document (PRD)

**Product Name:** Anvil
**Tagline:** A Distributed Job Processing & Workflow Orchestration Platform
**Document Owner:** Engineering
**Status:** Draft v1.0
**Audience:** Engineering team (human + AI coding agents), reviewers

---

## 1. Overview

Anvil lets client applications offload long-running or resource-heavy work — report
generation, media processing, bulk email, data import/export — to a managed background
system instead of blocking the request thread. A client submits a **job**, gets back a
job ID immediately, and later polls or subscribes for status, progress, and results.

This is the same pattern used internally by GitHub Actions, Jenkins, Kubernetes Jobs,
AWS Batch, Netflix Conductor, Temporal, and Airflow. Anvil is a scoped, single-team
implementation of that pattern — not a Temporal clone, and not trying to be one. The
system is **job-type agnostic**: it does not know or care what "compress a video" means.
It only knows how to accept, queue, schedule, execute, retry, and report on jobs.

This PRD assumes Anvil is built as a real enterprise system meant to serve **thousands
of concurrent users and tens of thousands of jobs per day**, not a toy or a portfolio
demo. Every requirement below is written with that scale as the baseline assumption.

---

## 2. Goals

1. Accept job submissions via a REST API and return a job ID in well under 200ms (p95),
   regardless of how long the underlying job takes.
2. Execute jobs asynchronously across a horizontally scalable worker pool.
3. Provide accurate, near-real-time status and progress reporting per job.
4. Recover from worker crashes, transient failures, and infra hiccups without losing
   jobs or requiring manual intervention for the common case.
5. Give operators (admins) full visibility into system health: queue depth, worker
   status, failure rates, throughput.
6. Enforce role-based access control and maintain a tamper-evident audit trail of who
   did what to which job.
7. Be deployable and observable like a real production system: metrics, structured
   logs, health checks, graceful shutdown.

## 3. Non-Goals (v1)

- Anvil is **not** a general-purpose workflow engine with DAGs of interdependent tasks
  (no Airflow-style task graphs, no branching workflows). Each job is a single unit of
  work. Multi-step pipelines are a documented future extension, not v1 scope.
- No multi-tenancy / organization hierarchy in v1 — every user belongs to one flat
  user pool with two roles (USER, ADMIN).
- No custom job-type plugin marketplace. Job types are registered in code (see §9),
  not uploaded/dynamic at runtime.
- No mobile app. Web dashboard only.
- No billing/metering. That's a plausible v2 feature, not v1.

---

## 4. Personas

### 4.1 Normal User
A person or service account that submits work and checks on it.

**Can:**
- Register / log in
- Create a job (choose job type + parameters)
- View own job list, filter by status/type/date
- View a single job's detail: status, progress %, timestamps, result/error
- Cancel a job that is `QUEUED` or `RUNNING`
- Retry a job that is `FAILED` (subject to retry policy)
- Download a completed job's result artifact
- Receive notifications (in-app + optional email + WebSocket push)

**Cannot:** see other users' jobs, see worker/queue internals, access admin endpoints.

### 4.2 Administrator
An operator responsible for the health of the platform.

**Can (in addition to everything a Normal User can do, scoped to all jobs):**
- View all jobs across all users
- Monitor workers: list, status, current job, heartbeat age
- Pause / resume / restart a worker
- Monitor queue depth per priority tier
- View the Dead Letter Queue and requeue or permanently discard entries
- View system statistics dashboard (see §11)
- View the audit log (searchable, filterable)
- View structured application logs (or link out to the log aggregator)

---

## 5. Job Lifecycle

Every job — regardless of type — moves through the same state machine. This is the
core abstraction of the whole system.

```
CREATED → QUEUED → RUNNING → COMPLETED
                       │
                       ├─→ FAILED → RETRYING → RUNNING (loop back)
                       │              │
                       │              └─→ FAILED_PERMANENTLY (after max retries → DLQ)
                       │
                       └─→ CANCELLING → CANCELLED   (user-initiated, cooperative)
```

**State definitions:**

| State | Meaning |
|---|---|
| `CREATED` | Persisted, not yet enqueued (used for scheduled/delayed jobs before their run time). |
| `QUEUED` | Sitting in the queue, waiting for a worker. |
| `RUNNING` | A worker has claimed it and is executing. |
| `FAILED` | Execution threw an unrecoverable-for-this-attempt error. |
| `RETRYING` | Backoff timer active; will re-enter `QUEUED` when timer elapses. |
| `FAILED_PERMANENTLY` | Exhausted retries. Moved to the Dead Letter Queue. |
| `CANCELLING` | Cancellation requested; waiting for worker to acknowledge and stop. |
| `CANCELLED` | Worker confirmed stop; terminal state. |
| `COMPLETED` | Finished successfully; result available. |

**Invariants the implementation must guarantee:**
- A job has exactly one owner (the user who created it) for its entire lifetime.
- State transitions are one-directional per the diagram above — no skipping backward
  except the explicit `RETRYING → QUEUED` loop.
- Every transition is timestamped and written to the audit log.
- A job's `updated_at` must change on every state transition (used for stuck-job
  detection).

---

## 6. Functional Requirements

### 6.1 Authentication & Authorization
- Email + password registration and login.
- Passwords hashed with bcrypt (cost factor ≥ 12) — never stored or logged in plaintext.
- JWT access tokens (short-lived, 15 min) + refresh tokens (long-lived, revocable,
  stored server-side to allow forced logout).
- Role-based access control: `USER`, `ADMIN`. Enforce at the API gateway/controller
  layer via method-level security, never trust the client.
- Rate-limit login attempts per account and per IP to mitigate brute force.

### 6.2 Job Submission
- `POST /api/v1/jobs` accepts `{ jobType, payload, priority?, scheduledAt?, cronExpression? }`.
- Payload is validated against a per-job-type JSON schema before the job is accepted
  (fail fast — reject bad input at submission time, not inside the worker).
- Response returns immediately: `{ jobId, status: "QUEUED", createdAt }`.
- Idempotency: support an optional `Idempotency-Key` header so retried client requests
  (e.g. due to network blips) don't create duplicate jobs.

### 6.3 Supported Job Types (v1 seed set)
The system is job-type agnostic at its core, but ships with these registered types to
prove the abstraction and give the frontend something real to demo:

| Job Type | Simulated Duration | Notes |
|---|---|---|
| `REPORT_GENERATION` | 20–30s | Produces a downloadable PDF/CSV artifact. |
| `IMAGE_PROCESSING` | ~10s per batch | Simulates converting N images to WebP. |
| `CSV_IMPORT` | Scales with row count | Simulates processing up to 100,000 rows, reports progress per chunk. |
| `EMAIL_CAMPAIGN` | Scales with recipient count | Simulates sending up to 1,000 emails, reports progress per batch. |
| `AI_CONTENT_GENERATION` | 5–15s | Simulated delay, returns generated text payload. |
| `FILE_COMPRESSION` | 10–20s | Simulates compression, returns a downloadable ZIP artifact. |

Each job type is implemented as a class conforming to a `JobHandler` interface
(see AGENTS.md §"Job Handler Contract"). Adding a new job type must never require
touching queue, scheduler, or worker-pool code.

### 6.4 Queueing
- Jobs are queued with a **priority tier**: `HIGH`, `MEDIUM`, `LOW`. Workers always
  drain `HIGH` before `MEDIUM` before `LOW` (strict priority, with starvation
  safeguards — see §8 non-functional requirements).
- Queue backend must support: FIFO within a priority tier, at-least-once delivery,
  visibility timeout (so a crashed worker's claimed job becomes reclaimable), and
  a dead-letter mechanism.

### 6.5 Worker Pool
- Multiple stateless worker processes/instances, each independently polling the
  queue and claiming jobs.
- Workers send periodic heartbeats. If a heartbeat is missed beyond a threshold, the
  worker is marked `UNHEALTHY` and its in-flight job becomes reclaimable by another
  worker.
- Workers expose a local health endpoint for orchestrator (Kubernetes) liveness checks.
- Admins can pause a worker (stop claiming new jobs, finish current one) or force-kill
  a worker's current job and restart it.

### 6.6 Scheduler
- Supports: run immediately, run at a specific future timestamp, and cron-style
  recurring jobs.
- A scheduler process periodically scans for due `CREATED` (scheduled) jobs and
  `CRON` job definitions whose next fire time has passed, and enqueues them.
- Scheduler must run in a way that's safe with multiple scheduler replicas (no
  double-enqueue) — use a DB-level lock or leader election, not an in-memory timer.

### 6.7 Retry Mechanism
- Default retry policy: exponential backoff — 30s, 60s, 120s, 240s (configurable per
  job type, with a max attempt count, default 4).
- After exhausting retries, job moves to `FAILED_PERMANENTLY` and is inserted into the
  Dead Letter Queue with the full failure history (all attempt errors/stack traces).
- Admins can inspect DLQ entries and manually requeue (resets attempt count) or
  permanently discard.

### 6.8 Progress Tracking
- Job handlers report incremental progress (0–100) plus an optional human-readable
  status message (e.g. `"Processing row 42,000 / 100,000"`).
- Progress updates are persisted and pushed to subscribed clients via WebSocket; REST
  polling also reflects the latest persisted value.

### 6.9 Notifications
- In-app notification created on job completion/failure.
- WebSocket push to any connected client subscribed to that job or to "my jobs".
- Optional email notification (configurable per user, off by default for high-volume
  users) sent via an async notification worker — never inline in the request path.

### 6.10 Job Cancellation
- User requests cancellation of a `QUEUED` or `RUNNING` job.
- `QUEUED` jobs: removed from queue immediately, marked `CANCELLED`.
- `RUNNING` jobs: marked `CANCELLING`; the executing worker must check a cancellation
  flag at defined checkpoints (e.g. between chunks of a CSV import) and stop
  cooperatively, then mark `CANCELLED`. Document that not all job types can cancel
  instantly — cancellation is cooperative, not preemptive.

### 6.11 Timeout Detection
- Every job type has a max execution time (default 10 minutes, configurable per type).
- A watchdog process detects jobs stuck in `RUNNING` past their timeout and force-fails
  them (triggering the normal retry/DLQ path), and flags the owning worker for
  inspection if this recurs.

### 6.12 Audit Log
- Immutable, append-only record of: job created/cancelled/retried by whom and when,
  worker paused/restarted by whom and when, login/logout events, role changes.
- Queryable by admins with filters (user, action type, date range, job ID).
- Audit records are never deleted by application code; retention/purge is an infra
  concern (e.g. a scheduled archive job), not a user-facing delete.

### 6.13 Admin Dashboard (data requirements — see §11 for the metrics themselves)
- Real-time (or near-real-time, ≤5s staleness) view of system-wide counters.
- Drill-down from a summary metric into the underlying job/worker list.

---

## 7. High-Level Architecture

```
                        ┌─────────────┐
                        │   Client    │  (Web dashboard, or API consumer)
                        └──────┬──────┘
                               │ HTTPS (REST + WebSocket)
                               ▼
                     ┌───────────────────┐
                     │   API Gateway /   │   AuthN/AuthZ, rate limiting,
                     │   REST Layer      │   request validation
                     └─────────┬─────────┘
                               │
                               ▼
                   ┌────────────────────────┐
                   │  Job Management Service │  Creates job records,
                   │                        │  enforces business rules
                   └───────────┬────────────┘
                       ┌───────┴────────┐
                       ▼                ▼
                ┌─────────────┐   ┌──────────────┐
                │  Job Queue   │   │  Scheduler   │  (cron / delayed jobs)
                │ (per-priority│   └──────┬───────┘
                │  + DLQ)      │◄─────────┘ enqueues when due
                └──────┬───────┘
                       ▼
                ┌──────────────┐
                │  Worker Pool  │  N stateless workers, heartbeat,
                │              │  horizontal scaling
                └──────┬───────┘
                       ▼
                ┌──────────────────┐
                │ Job Execution     │  Job-type-specific handlers,
                │ Engine (per       │  progress reporting, cancellation
                │ worker)           │  checkpoints
                └──────┬────────────┘
                       ▼
                ┌──────────────┐        ┌────────────────────┐
                │  PostgreSQL   │◄──────►│ Notification Service│
                │ (jobs, users, │        │ (WebSocket, email)  │
                │  audit, DLQ)  │        └─────────┬───────────┘
                └──────┬───────┘                   │
                       ▼                            ▼
                ┌──────────────────────────────────────┐
                │           Admin / User Dashboard        │
                └──────────────────────────────────────┘
```

**Deployment shape:** API layer, worker pool, and scheduler are separate deployable
units (separate containers/pods) so they scale independently. Workers scale
horizontally based on queue depth. The API layer scales horizontally behind a load
balancer, statelessly (JWT auth, no server-side session affinity).

---

## 8. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Scale** | Support 10,000+ registered users, 5,000+ concurrent WebSocket connections, 50,000+ jobs/day sustained, bursts to 500 job submissions/minute. |
| **Latency** | Job submission API: p95 < 200ms, p99 < 500ms. Status query API: p95 < 100ms. |
| **Availability** | API layer and job durability target 99.9%. A worker or scheduler crash must never lose a job (at-least-once execution semantics with idempotent-safe handlers where feasible). |
| **Consistency** | Job state transitions must be atomic (DB transaction + queue ack/nack in the same logical unit, or an outbox pattern to avoid dual-write inconsistency). |
| **Scalability** | Worker pool scales horizontally with zero code change; queue and DB are the only shared state. |
| **Security** | All traffic over TLS. Secrets via environment/secret manager, never committed. Input validated server-side regardless of client validation. SQL access exclusively via parameterized queries/ORM. |
| **Fairness** | Strict priority queueing must not starve `LOW` priority indefinitely — apply an aging mechanism (e.g. after N minutes waiting, bump effective priority) or a capacity reservation (e.g. ≥10% of worker capacity always available to low priority). |
| **Observability** | Structured JSON logs with correlation/job IDs; metrics exported in Prometheus format; distributed tracing across API → queue → worker for a given job ID. |
| **Backward compatibility** | REST API versioned (`/api/v1/...`); breaking changes require a new version, not in-place mutation. |

---

## 9. Job Handler Contract (system extensibility model)

Every job type implements a common interface so the queue/worker/scheduler layers
never need to know about job-specific logic:

```
interface JobHandler<TPayload, TResult> {
  String jobType();                  // unique key, e.g. "REPORT_GENERATION"
  JsonSchema payloadSchema();         // for submission-time validation
  int defaultMaxRetries();
  Duration defaultTimeout();

  TResult execute(TPayload payload, JobExecutionContext ctx);
  // ctx exposes: reportProgress(int pct, String message)
  //              isCancellationRequested()
  //              jobId(), attemptNumber()
}
```

New job handlers are registered at startup (e.g. Spring bean discovery) and picked up
automatically by a generic dispatcher in the worker — no if/else chains keyed on job
type anywhere in queue/scheduler/worker code.

---

## 10. Data Model (core entities)

- **User**: id, email, password_hash, role, created_at, is_active
- **Job**: id, user_id, job_type, payload (JSONB), status, priority, progress_pct,
  progress_message, result (JSONB/reference to artifact), error_message,
  attempt_count, max_retries, scheduled_at, cron_expression, timeout_seconds,
  created_at, updated_at, started_at, completed_at
- **JobAttempt**: id, job_id, attempt_number, started_at, ended_at, status, error,
  stack_trace, worker_id — full history for the DLQ/audit view
- **Worker**: id, hostname, status (HEALTHY/UNHEALTHY/PAUSED), last_heartbeat_at,
  current_job_id, started_at
- **DeadLetterEntry**: id, job_id, reason, failure_history (JSONB), created_at,
  resolved_by, resolved_action (REQUEUED/DISCARDED)
- **Notification**: id, user_id, job_id, type, message, read_at, created_at
- **AuditLogEntry**: id, actor_user_id, action, target_type, target_id, metadata
  (JSONB), created_at

(Exact column types, indexes, and migration files are defined in the implementation
phase, not this PRD — see the phased implementation prompts.)

---

## 11. Admin Dashboard — Metrics

- Jobs waiting (by priority tier)
- Jobs running
- Jobs completed (last 1h / 24h)
- Jobs failed (last 1h / 24h)
- Workers online / paused / unhealthy
- Average processing time (overall + per job type)
- Queue size over time (chart)
- Worker utilization %
- Retry count / DLQ size
- p95/p99 job submission-to-completion latency per job type

---

## 12. Security & Compliance Notes

- JWT secrets and DB credentials via environment variables / secret manager only.
- Per-endpoint authorization tests are a required part of Definition of Done (not
  just "does the happy path work").
- No PII beyond email is required in v1; if job payloads may contain PII (e.g. an
  email campaign recipient list), document that payload JSONB fields are subject to
  the same access control as the job itself.
- Audit log is a compliance-relevant feature — treat it as append-only in code
  (no `UPDATE`/`DELETE` code paths against that table).

---

## 13. Success Metrics (product-level)

- Job submission → worker pickup latency (p95) under target queue depth.
- < 0.1% of jobs land in the DLQ under normal operation (excluding intentionally
  broken test payloads).
- Zero jobs lost across a simulated worker-crash chaos test.
- Admin dashboard staleness ≤ 5 seconds under load.

---

## 14. Explicit Out-of-Scope / Future Roadmap

- Multi-step DAG workflows (job A triggers job B on completion)
- Multi-tenant organizations / teams
- Usage-based billing
- Dynamic/plugin job types uploaded at runtime
- Mobile native apps

---

## 15. Frontend Product Requirements (high level)

The frontend is not an afterthought UI wrapper — it is the primary way users and
admins interact with the system and must read as a serious enterprise product.

- **Two distinct experiences:** a User workspace (submit/track own jobs) and an Admin
  console (system-wide monitoring), sharing a design system but with different
  information density — admin views are data-dense, user views are task-focused.
- **Visual bar:** modern, restrained enterprise aesthetic (think Linear, Vercel
  dashboard, Datadog) — not a generic AI-generated gradient-and-emoji SaaS landing
  page. Specific direction and constraints are defined in `AGENTS.md`.
- **Real-time by default:** job status/progress updates via WebSocket, not
  polling-only, with polling as a documented fallback.
- **Empty, loading, and error states are first-class** — every list/detail view needs
  explicit designs for zero jobs, loading skeleton, and failure-to-load, not just the
  happy path.
