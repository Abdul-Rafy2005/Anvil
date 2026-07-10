# Anvil — Phased Implementation Plan & Agent Prompts

How to use this file: copy **one phase's prompt at a time** into your IDE agent
(Claude Code, Cursor, etc.), in order. Don't skip ahead — each phase assumes the
previous ones are done and tested. After the agent finishes a phase, run the "How
you test this phase" steps yourself before moving on. Both `PRD.md` and `AGENTS.md`
should already be in the repo root before you start Phase 0 — tell the agent to read
them first in every phase prompt (already included below).

---

## Phase 0 — Repo Scaffolding & Local Infra

**Goal:** empty-but-running skeleton: backend boots, DB migrates, frontend boots,
docker-compose brings up Postgres + Redis, nothing functional yet.

**Prompt to give the agent:**
> Read `PRD.md` and `AGENTS.md` in this repo fully before doing anything.
> Set up the initial project scaffolding for "Anvil" per the tech stack and package
> structure defined in `AGENTS.md`:
> 1. A Spring Boot 3 (Java 21, Maven) backend project named `anvil-backend` with the
>    package structure from AGENTS.md §4 (empty packages are fine for now, just the
>    shells).
> 2. Flyway configured, with an initial empty baseline migration.
> 3. A `docker-compose.yml` at the repo root bringing up Postgres 16 and Redis, with
>    healthchecks, and the backend's `application.yml` pointed at them via env vars.
> 4. A React 18 + TypeScript + Vite frontend project named `anvil-frontend` with
>    Tailwind configured and a placeholder page.
> 5. A root `.gitignore` covering `target/`, `node_modules/`, `.env`, IDE files.
> 6. A root `README.md` with setup instructions (prerequisites, how to run
>    docker-compose, how to run backend, how to run frontend).
> Do not implement any business logic yet — this phase is infrastructure only.

**How you test this phase:**
1. `docker-compose up -d` → confirm Postgres and Redis containers report healthy.
2. Run the backend → confirm it starts with no errors and Flyway reports the baseline
   migration applied (check logs).
3. Hit the backend's actuator/health endpoint (or whatever health endpoint was set
   up) → expect `200 OK`.
4. Run the frontend dev server → confirm the placeholder page loads in a browser with
   Tailwind styles visibly applied (not unstyled HTML).
5. Confirm `git status` shows no `target/`, `node_modules/`, or `.env` files tracked.

---

## Phase 1 — Auth & User Management

**Goal:** register, login, JWT issuance/refresh, role-based access control enforced.

**Prompt to give the agent:**
> Read `PRD.md` §4 (Personas) and §6.1 (Authentication & Authorization), and
> `AGENTS.md` §2, §5, §9 before starting.
> Implement:
> 1. `User` entity + Flyway migration (email unique, password_hash, role enum
>    USER/ADMIN, is_active, created_at).
> 2. Registration endpoint (`POST /api/v1/auth/register`) and login endpoint
>    (`POST /api/v1/auth/login`) with bcrypt password hashing (cost ≥ 12).
> 3. JWT access token (15 min expiry) + refresh token (stored server-side so it can
>    be revoked), with a `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`
>    endpoint.
> 4. Spring Security config enforcing role-based access; a sample protected
>    `/api/v1/admin/ping` endpoint restricted to ADMIN, and `/api/v1/users/me`
>    open to any authenticated user.
> 5. Rate limiting on the login endpoint (per account and per IP).
> 6. Unit tests for password hashing/validation and JWT generation/validation.
>    Contract tests for register/login/refresh/logout happy paths AND failure paths
>    (duplicate email, wrong password, expired/invalid token, non-admin hitting the
>    admin ping endpoint → expect 403).
> Follow the Definition of Done checklist in `AGENTS.md` §10 before reporting this
> phase complete.

**How you test this phase:**
1. Register a new user via `POST /auth/register` → expect 201 and no password in the
   response body.
2. Try registering the same email again → expect a 4xx with a clear error code, not
   a 500.
3. Log in with correct credentials → expect access + refresh tokens.
4. Log in with wrong password → expect 401, and confirm repeated attempts eventually
   get rate-limited.
5. Call `/api/v1/admin/ping` with a normal user's token → expect 403.
6. Manually flip that user's role to ADMIN in the DB, get a fresh token, call the
   same endpoint → expect 200.
7. Wait for/force access token expiry, call `/auth/refresh` with the refresh token →
   expect a new valid access token.
8. Call `/auth/logout`, then try reusing the same refresh token → expect it to be
   rejected.

---

## Phase 2 — Core Job CRUD (no queue/workers yet)

**Goal:** jobs can be created, listed, viewed, and cancelled at the DB/API level,
with the full state machine enforced — but nothing actually executes them yet.

**Prompt to give the agent:**
> Read `PRD.md` §5 (Job Lifecycle), §6.2 (Job Submission), §10 (Data Model), and
> `AGENTS.md` §3 rule 4 (centralized state machine) before starting.
> Implement:
> 1. `Job` and `JobAttempt` entities + migrations per PRD §10.
> 2. A `JobStateMachine` component that is the *only* place job status is mutated,
>    validating legal transitions per PRD §5 and writing an audit log entry (stub
>    the audit table/service now if not built yet — full audit log ships in a later
>    phase, but the write hook must exist).
> 3. `POST /api/v1/jobs` — validates payload against a per-job-type JSON schema
>    (start with a hardcoded schema for a single dummy job type if real handlers
>    aren't built yet — full handler contract ships in Phase 4), persists as
>    `CREATED`→`QUEUED` (queue push is a no-op/log statement for now), returns
>    `{ jobId, status, createdAt }`. Support the `Idempotency-Key` header.
> 4. `GET /api/v1/jobs` (paginated, filterable by status/jobType/date, scoped to the
>    caller's own jobs unless ADMIN).
> 5. `GET /api/v1/jobs/{id}` (404 if not found or not owned, unless ADMIN).
> 6. `POST /api/v1/jobs/{id}/cancel` — only legal from `CREATED`/`QUEUED`/`RUNNING`
>    per the state machine.
> 7. Unit tests for the state machine (every legal and illegal transition).
>    Contract tests for all endpoints above including ownership enforcement (user A
>    cannot see/cancel user B's job, admin can see everyone's).

**How you test this phase:**
1. Create a job as User A → confirm response has a `jobId` and status `QUEUED`.
2. Repeat the exact same request with the same `Idempotency-Key` → confirm no
   duplicate job is created (same jobId returned).
3. Fetch the job by ID as User A → 200. Fetch it as User B → 404 (not 403 — don't
   leak existence). Fetch it as an admin → 200.
4. List jobs as User A → only User A's jobs appear. List as admin → all jobs appear.
5. Cancel the job as User A while it's `QUEUED` → status becomes `CANCELLED`.
6. Try to cancel an already-`CANCELLED` job → expect a clear 4xx, not a crash.
7. In a DB client, manually confirm an audit log row (or stub) was written for the
   create and cancel actions.

---

## Phase 3 — Queue Abstraction & Worker Pool Skeleton

**Goal:** jobs actually leave the DB and get picked up by a running worker process —
still with a no-op "execute" (just flips status to COMPLETED after a fake delay) so
you can prove the pipe works before adding real job logic.

**Prompt to give the agent:**
> Read `PRD.md` §6.4 (Queueing), §6.5 (Worker Pool), and `AGENTS.md` §3 rules 2 and 5
> (queue-agnostic, outbox pattern) before starting.
> Implement:
> 1. A `Queue` interface (enqueue, claim-with-visibility-timeout, ack, nack-to-dlq)
>    and a Redis-backed implementation supporting three priority tiers
>    (HIGH/MEDIUM/LOW) with strict priority draining plus an aging safeguard so LOW
>    isn't starved indefinitely (per PRD §8 fairness requirement).
> 2. A transactional outbox: job creation writes the job row AND an outbox row in
>    one transaction; a relay process reads the outbox and pushes to the Redis
>    queue, then marks it relayed. (This replaces the Phase 2 "no-op" enqueue.)
> 3. A `Worker` runner (a Spring `@Component` with a polling loop, can be run as a
>    separate process/profile) that claims a job, marks it `RUNNING`, waits a fake
>    5-second delay, marks it `COMPLETED`. Real job execution logic comes in Phase 4.
> 4. Worker heartbeat: each worker writes a heartbeat row/key on an interval; a
>    watchdog marks a worker `UNHEALTHY` if the heartbeat goes stale, and its
>    claimed job becomes reclaimable (visibility timeout expiry).
> 5. Integration tests (Testcontainers for Postgres + Redis) proving: a created job
>    is eventually claimed and completed by a worker; killing a worker mid-job
>    (simulate by not renewing its claim) results in another worker reclaiming and
>    completing it.

**How you test this phase:**
1. Start two worker instances alongside the API (or two threads/profiles per
   AGENTS.md's process model). Create 5 jobs. Confirm in logs/DB that both workers
   pick up jobs (not just one).
2. Create a HIGH priority job while several LOW priority jobs are queued → confirm
   the HIGH one is claimed first.
3. Queue enough LOW priority jobs that they'd normally starve behind a constant
   stream of HIGH/MEDIUM ones → confirm the aging safeguard eventually lets a LOW
   job through (check its wait time in the DB).
4. Simulate a worker crash (kill the process or stop it mid-fake-delay) → confirm,
   after the visibility timeout, another worker claims and completes that job
   instead of it being stuck in `RUNNING` forever.
5. Stop Redis briefly and restart it → confirm the outbox relay catches up and
   nothing was silently lost (compare job count in DB vs. jobs that reached
   `COMPLETED`).

---

## Phase 4 — Real Job Handlers & Execution Engine

**Goal:** replace the Phase 3 fake delay with real, job-type-specific handlers per
PRD §6.3 and the `JobHandler` contract in PRD §9 / AGENTS.md §7.

**Prompt to give the agent:**
> Read `PRD.md` §6.3 (Supported Job Types), §9 (Job Handler Contract), §6.8
> (Progress Tracking), §6.10 (Cancellation), §6.11 (Timeout Detection), and
> `AGENTS.md` §7 before starting.
> Implement:
> 1. The `JobHandler<TPayload, TResult>` interface exactly as specified in PRD §9.
> 2. Concrete handlers for all six job types in PRD §6.3 table (
>    REPORT_GENERATION, IMAGE_PROCESSING, CSV_IMPORT, EMAIL_CAMPAIGN,
>    AI_CONTENT_GENERATION, FILE_COMPRESSION), each simulating realistic duration and
>    reporting incremental progress via `ctx.reportProgress(...)` at sensible
>    checkpoints (e.g. per chunk of rows for CSV_IMPORT).
> 3. A generic dispatcher in the worker that looks up the right handler by job type
>    (no if/else chains — a registered map/bean lookup) and invokes it, persisting
>    progress updates as they're reported.
> 4. Cooperative cancellation: each handler checks `ctx.isCancellationRequested()`
>    at its progress checkpoints and exits cleanly if true, letting the state
>    machine move the job to `CANCELLED`.
> 5. A timeout watchdog: if a job's `RUNNING` duration exceeds its `timeout_seconds`,
>    force-fail it (feeding into the Phase 5 retry mechanism).
> 6. Unit tests per handler (does it produce a plausible result, does it report
>    progress in increasing order, does it respect cancellation).
>    Integration test: submit a CSV_IMPORT job, cancel it mid-way, confirm it stops
>    within a reasonable number of checkpoints rather than running to completion.

**How you test this phase:**
1. Submit one job of each of the six types → poll `GET /jobs/{id}` and confirm
   progress climbs from 0 toward 100 over multiple polls, not a single jump.
2. Submit a CSV_IMPORT job, then cancel it after a couple of progress updates →
   confirm it lands in `CANCELLED` within a few seconds, not after running the full
   100,000-row simulation to completion.
3. Temporarily set a job type's timeout very low (e.g. 2 seconds) via config,
   submit that job type, and confirm the watchdog force-fails it rather than letting
   it run indefinitely.
4. Confirm `GET /jobs/{id}` for a completed job returns a sensible `result` payload
   matching what that job type is supposed to produce (e.g. a downloadable
   reference for FILE_COMPRESSION/REPORT_GENERATION).

---

## Phase 5 — Retry Mechanism & Dead Letter Queue

**Goal:** failed jobs retry with backoff, and permanently-failed jobs are visible and
manageable in a DLQ.

**Prompt to give the agent:**
> Read `PRD.md` §6.7 (Retry Mechanism) and §6.12 (Audit Log, for DLQ actions) before
> starting.
> Implement:
> 1. On handler failure, the state machine moves the job `RUNNING → FAILED →
>    RETRYING`, scheduling the next attempt using exponential backoff (30s, 60s,
>    120s, 240s by default, configurable per job type) via the scheduler/queue (not
>    an in-memory timer that dies with the process).
> 2. After exhausting `max_retries`, move to `FAILED_PERMANENTLY` and write a
>    `DeadLetterEntry` with the full `JobAttempt` failure history.
> 3. Admin endpoints: `GET /api/v1/admin/dlq` (paginated, filterable), `POST
>    /api/v1/admin/dlq/{id}/requeue` (resets attempt count, re-enters `QUEUED`),
>    `POST /api/v1/admin/dlq/{id}/discard`.
> 4. Both DLQ actions write an audit log entry with the acting admin's identity.
> 5. Integration test: a handler engineered to always fail should end up in the DLQ
>    after exactly `max_retries` attempts, with correct backoff timing recorded.

**How you test this phase:**
1. Temporarily configure or engineer a job type/handler to always throw → submit it,
   and watch it cycle `FAILED → RETRYING → RUNNING` across the configured backoff
   intervals (check timestamps in `JobAttempt` rows match the expected delays).
2. Confirm that after the configured max retries, it lands as `FAILED_PERMANENTLY`
   and a `DeadLetterEntry` exists with all attempts' error details.
3. As an admin, `GET /admin/dlq` → confirm the entry appears with its failure
   history. As a non-admin, hit the same endpoint → expect 403.
4. Requeue it via the admin endpoint → confirm attempt count resets and it goes
   through the normal lifecycle again (fix the underlying failure first if you want
   it to actually succeed this time, or leave it broken to confirm it re-enters
   the DLQ correctly).
5. Discard a different DLQ entry → confirm it's marked discarded and does not
   reappear as an active job.

---

## Phase 6 — Scheduler (Delayed & Cron Jobs)

**Goal:** jobs can be scheduled for the future or recur on a cron expression, safely
even with multiple scheduler replicas.

**Prompt to give the agent:**
> Read `PRD.md` §6.6 (Scheduler) and `AGENTS.md` §3 (config over hardcoding) before
> starting.
> Implement:
> 1. Extend job submission to accept `scheduledAt` (run at a future timestamp) or
>    `cronExpression` (recurring), validated at submission time (reject invalid cron
>    strings immediately, not at run time).
> 2. A scheduler process that periodically scans for `CREATED` jobs whose
>    `scheduledAt` has passed, and for cron job definitions whose next fire time has
>    passed, and enqueues them — using a DB-level lock (e.g. `SELECT ... FOR UPDATE
>    SKIP LOCKED` or an advisory lock) so multiple scheduler replicas never
>    double-enqueue the same due job.
> 3. For cron jobs, compute and persist the next fire time after each enqueue.
> 4. Integration test running two scheduler instances concurrently against the same
>    due jobs, asserting each due job is enqueued exactly once.

**How you test this phase:**
1. Submit a job with `scheduledAt` 30 seconds in the future → confirm it stays
   `CREATED` until that time, then transitions to `QUEUED` and executes shortly
   after, not before.
2. Submit a job with a cron expression firing every minute → let it run across two
   fire times, confirm two separate `Job`/`JobAttempt` executions occurred a minute
   apart, not zero, not duplicated.
3. Run two instances of the scheduler process at once → confirm (via logs/DB) that
   a given due job is only ever enqueued once, not twice.
4. Submit a job with a deliberately malformed cron expression → expect a 4xx at
   submission time, not a silent failure later.

---

## Phase 7 — Real-Time Notifications & WebSocket Progress

**Goal:** users see live progress and get notified on completion/failure without
polling.

**Prompt to give the agent:**
> Read `PRD.md` §6.8 (Progress Tracking), §6.9 (Notifications) before starting.
> Implement:
> 1. A WebSocket (STOMP) endpoint clients can subscribe to for a specific job ID or
>    for "my jobs" broadly, authenticated via the existing JWT.
> 2. Push progress updates and terminal-state notifications (completed/failed) over
>    this channel as they happen, in addition to persisting them for REST polling
>    fallback.
> 3. A `Notification` entity + `GET /api/v1/notifications` (paginated, unread
>    count) + `POST /api/v1/notifications/{id}/read`.
> 4. An async email notification path (can be a stub/log-based "sender" for now if
>    no real SMTP is configured) gated by a per-user preference, never sent inline
>    in the request path.
> 5. Test: subscribe a test WebSocket client to a job, submit that job, and assert
>    progress messages arrive over the socket in increasing order, ending with a
>    terminal notification.

**How you test this phase:**
1. Open a WebSocket connection (e.g. via a small test script or browser dev tools)
   subscribed to a specific job, then submit that job via REST → confirm progress
   messages stream in over the socket without any polling.
2. Disconnect the socket mid-job, then reconnect and hit `GET /jobs/{id}` → confirm
   the persisted progress is still correct (no data lost just because the socket
   dropped).
3. Let a job complete → confirm a `Notification` row was created and appears via
   `GET /notifications`, and its unread count increments.
4. Mark it read → confirm the unread count decrements and it no longer counts as
   unread on subsequent fetches.

---

## Phase 8 — Admin Statistics & Monitoring APIs

**Goal:** the data backend for the admin dashboard described in PRD §11.

**Prompt to give the agent:**
> Read `PRD.md` §11 (Admin Dashboard Metrics), §6.13, and §6.12 (Audit Log) before
> starting.
> Implement:
> 1. `GET /api/v1/admin/stats/overview` returning: jobs waiting by priority, jobs
>    running, jobs completed/failed (last 1h and 24h), workers online/paused/
>    unhealthy, average processing time overall and per job type, current queue
>    size, worker utilization %, DLQ size.
> 2. `GET /api/v1/admin/workers` (list with status/current job/heartbeat age) and
>    `POST /api/v1/admin/workers/{id}/pause` / `/resume` / `/restart`.
> 3. `GET /api/v1/admin/audit-log` (paginated, filterable by actor, action type,
>    date range, target job ID).
> 4. Ensure all of the above are ADMIN-only and covered by 403 tests for non-admins.
> 5. Performance: the overview endpoint must respond in a way that supports ≤5s
>    dashboard staleness under load — use aggregation queries/caching as needed
>    rather than naive full-table scans on every request.

**How you test this phase:**
1. As admin, call `/admin/stats/overview` → sanity-check every number against what
   you know is actually in the DB (e.g. manually count running jobs and compare).
2. As a non-admin, call every admin endpoint above → expect 403 on all of them.
3. Pause a worker via the API → confirm it stops claiming new jobs (submit new jobs
   and see they queue up without that worker touching them) while it finishes any
   job already in flight.
4. Resume it → confirm it starts claiming again.
5. Filter the audit log by a specific admin's actions from the last hour → confirm
   only matching entries appear.

---

## Phase 9 — Frontend: User Workspace

**Goal:** the primary user-facing UI: submit jobs, see status/progress in real time,
manage own jobs. This must look like enterprise software, not a demo app — see
`AGENTS.md` §6 before writing any component.

**Prompt to give the agent:**
> Read `PRD.md` §15 (Frontend Product Requirements) and `AGENTS.md` §6 (Frontend
> Design Constraints) in full before writing any UI code. Do not default to generic
> AI-app styling — follow the constraints in AGENTS.md §6 exactly (neutral base
> palette + single accent, consistent status color system, tabular numerals for
> stats, designed empty/loading/error states for every view).
> Implement:
> 1. Login/register screens.
> 2. A job list view: paginated, filterable by status/type/date, with a clear
>    visual status system (badges/colors consistent with the token system you
>    establish).
> 3. A job submission form per job type (dynamic fields based on the job type's
>    schema), with priority and optional scheduling (immediate/future/cron) inputs.
> 4. A job detail view showing live progress (WebSocket-driven progress bar),
>    timestamps, result download link on completion, and cancel/retry actions
>    where legal.
> 5. An in-app notification center (bell icon, unread count, list, mark-as-read).
> 6. Loading skeletons, empty states ("no jobs yet — submit your first job"), and
>    error states (failed to load, with retry) for every data view.
> 7. Frontend tests (Vitest + RTL) for the job list filtering logic and the
>    submission form's validation.

**How you test this phase:**
1. Register/login through the actual UI (not just the API) → confirm redirects and
   token storage work end to end.
2. Submit a job of each type through the form → confirm client-side validation
   catches bad input before hitting the API, and a correctly-filled form succeeds.
3. Watch the job detail page for a running job → confirm the progress bar updates
   live without a manual refresh.
4. Cancel a running job from the UI → confirm the state updates without a page
   reload.
5. Load the job list with zero jobs (fresh account) → confirm a real empty state
   appears, not a blank white screen.
6. Throttle/kill the network briefly while a list is loading → confirm a loading
   skeleton appears, and if the request ultimately fails, an error state with a
   retry option appears — not a silent blank page.
7. Have a colleague or fresh eyes look at it and answer: "does this look like
   something a real company would ship, or does it look AI-generated?" — if the
   latter, go back to AGENTS.md §6 before proceeding.

---

## Phase 10 — Frontend: Admin Console

**Goal:** the operator-facing dashboard: system health, workers, queue, DLQ, audit
log.

**Prompt to give the agent:**
> Read `PRD.md` §11 and §4.2 (Administrator persona) and `AGENTS.md` §6 before
> starting. This view is data-dense by design (per AGENTS.md §6) — prioritize
> scannability and information density over the more spacious user-facing views.
> Implement:
> 1. An overview dashboard: stat tiles (jobs waiting/running/completed/failed,
>    workers online, queue size, avg processing time, DLQ size) sourced from
>    `/admin/stats/overview`, refreshing on an interval (or via WebSocket if you
>    extend the notification channel to system-level events).
> 2. A worker management table: status, current job, heartbeat age, pause/resume/
>    restart actions.
> 3. A DLQ view: list of dead-lettered jobs with failure history expandable per
>    row, requeue/discard actions.
> 4. An audit log view: filterable table (actor, action, date range).
> 5. Route guarding so non-admin users cannot reach any of these routes even by
>    direct URL (not just hiding the nav link).

**How you test this phase:**
1. Log in as a non-admin and try to directly navigate to an admin route by URL →
   confirm you're redirected/blocked, not shown a broken or partial admin page.
2. Log in as admin, confirm the overview tiles match what you separately verified
   via the API in Phase 8.
3. Pause/resume/restart a worker from the UI → confirm the table reflects the new
   state without a manual refresh (or after a documented refresh interval).
4. Requeue and discard a DLQ entry from the UI → confirm both actions work and the
   list updates accordingly.
5. Filter the audit log by a date range and an actor → confirm results match.

---

## Phase 11 — Hardening, Observability, Load Testing

**Goal:** production-readiness pass.

**Prompt to give the agent:**
> Read `PRD.md` §8 (Non-Functional Requirements) and §13 (Success Metrics) before
> starting.
> Implement:
> 1. Structured JSON logging with correlation/job IDs threaded through API → queue
>    → worker.
> 2. Prometheus-format metrics: queue depth per priority, worker count/health, job
>    throughput, job latency histograms, DLQ size.
> 3. Health/readiness endpoints suitable for Kubernetes liveness/readiness probes
>    on the API, worker, and scheduler processes.
> 4. Graceful shutdown: a worker mid-job on SIGTERM should finish or cleanly abandon
>    (releasing its claim) rather than corrupting state.
> 5. A basic load test script (e.g. k6 or Gatling) simulating burst job submission
>    (per PRD §8 scale targets) and reporting p95/p99 latency for submission and
>    status endpoints.
> 6. A chaos test script that kills a worker mid-job repeatedly and confirms zero
>    job loss across N iterations.

**How you test this phase:**
1. Run the load test script at the target burst rate from PRD §8 → confirm p95/p99
   latencies meet the stated targets, and note where they don't.
2. Watch the Prometheus metrics endpoint during the load test → confirm queue
   depth, throughput, and latency histograms populate sensibly.
3. Send SIGTERM to a worker mid-job during a load test → confirm the job is either
   finished cleanly or reclaimed by another worker, never stuck or duplicated.
4. Run the chaos script for at least 20 kill/reclaim iterations → confirm zero jobs
   are lost (every job that was submitted ends in a terminal state, and no job is
   double-executed with conflicting results).
5. Check that structured logs for a single job's full lifecycle can be traced by
   its job ID/correlation ID across API, scheduler, and worker log lines.

---

## Notes on Sequencing

- Phases 0–6 are backend-only and can be fully verified via API calls/DB
  inspection/curl — don't wait for the frontend to validate backend correctness.
- Phases 9–10 (frontend) assume Phases 1–8 are functionally complete and tested;
  building UI against an unstable backend contract wastes effort.
- Phase 11 is not "polish at the end" — treat it as a real gate. A system that
  passes functional tests but hasn't been chaos/load tested is not done per the
  PRD's enterprise-scale requirement.
