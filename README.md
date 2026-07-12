# Anvil

**Submit a job. Don't block the request. Get results when they're ready.**

Anvil is a distributed job processing platform. Clients submit work (CSV imports, report generation, image processing, etc.) via a REST API and receive a job ID immediately. A worker pool picks up jobs asynchronously, executes them with automatic retries and backoff, and pushes real-time progress updates over WebSocket. Admins get operational visibility through stats, worker management, audit logs, and a dead letter queue.

This is the backend and frontend implementation covering Phases 0-11 of the build. See [`docs/PRD.md`](docs/PRD.md) for the full product spec and [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for the phased build approach.

## Current Status

**Implemented (Phases 0-11):**

- Authentication and role-based access control (register, login, JWT refresh/logout, rate limiting)
- Job CRUD with idempotency, cancellation, status tracking, and priority tiers (HIGH/MEDIUM/LOW)
- Redis-backed priority queue with transactional outbox pattern
- Worker pool with heartbeat monitoring, automatic reclamation of stalled jobs, and pause/resume
- 7 job handlers (CSV Import, Email Campaign, File Compression, Image Processing, Report Generation, AI Content Generation, Always-Fail)
- Automatic retry with exponential backoff and dead letter queue (requeue/discard)
- Scheduled and cron-based job scheduling (cron-utils validated)
- WebSocket notifications (STOMP over SockJS) with per-user authorization and job progress streaming
- Admin stats overview, worker management, audit log querying, and DLQ management
- **Frontend user workspace:** login/register, job list with filtering, job submission form with validation, job detail with live WebSocket-driven progress, notification center
- **Frontend admin console:** system overview dashboard with auto-refreshing stat tiles, worker management table with pause/resume/restart, dead letter queue with expandable failure history and requeue/discard actions, audit log with actor/action filtering, route guarding for non-admin users
- **Production hardening:** structured JSON logging with correlation IDs, Prometheus metrics (counters, timers, gauges for queue depth, worker health, job throughput, DLQ size), Kubernetes liveness/readiness probes, graceful shutdown, orphan job reclamation on startup
- **Load tested:** p95=17ms, p99=34ms submission latency, 3857 jobs/min throughput (target: 500/min)
- **Chaos tested:** 20 kill/restart iterations, 60 jobs, zero job loss — outbox + orphan recovery confirmed

See [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for the full phase list and what comes next.

---

## Architecture

### System Overview

```
                            ┌─────────────────────────────────────────────────┐
                            │                  Client (Browser)               │
                            │   React SPA  ──── REST API ──── WebSocket      │
                            └───────────┬─────────────────────┬───────────────┘
                                        │                     │
                              ┌─────────▼─────────┐   ┌──────▼──────────┐
                              │   nginx (Docker)   │   │   STOMP/SockJS  │
                              │   :80               │   │   connection    │
                              └─────────┬──────────┘   └──────┬──────────┘
                                        │                     │
                          ┌─────────────▼─────────────────────▼─────────────┐
                          │              Spring Boot Backend (:8080)        │
                          │                                                 │
                          │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
                          │  │ REST API │  │ WebSocket│  │  Scheduler   │  │
                          │  │ (MVC)    │  │ (STOMP)  │  │ (cron/delay) │  │
                          │  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
                          │       │              │               │          │
                          │       │              │               │          │
                          │  ┌────▼──────────────▼───────────────▼───────┐  │
                          │  │           JobStateMachine                  │  │
                          │  │  CREATED → QUEUED → RUNNING → COMPLETED   │  │
                          │  │              ↘ FAILED → RETRYING → QUEUED │  │
                          │  │              ↘ FAILED_PERMANENTLY (DLQ)   │  │
                          │  └────────────────────┬──────────────────────┘  │
                          │                       │                         │
                          │  ┌────────────────────▼──────────────────────┐  │
                          │  │        Transactional Outbox Table         │  │
                          │  │   (DB write + queue enqueue = atomic)     │  │
                          │  └────────────────────┬──────────────────────┘  │
                          │                       │                         │
                          │  ┌────────────────────▼──────────────────────┐  │
                          │  │          Outbox Relay (1s interval)       │  │
                          │  │   Moves outbox entries → Redis sorted sets │  │
                          │  └────────────────────┬──────────────────────┘  │
                          │                       │                         │
                          │  ┌────────────────────▼──────────────────────┐  │
                          │  │            Worker (polls every 2s)        │  │
                          │  │  claim → execute handler → ack/nack       │  │
                          │  │  heartbeat → watchdog (15s interval)      │  │
                          │  └────────────────────┬──────────────────────┘  │
                          │                       │                         │
                          │  ┌────────────────────▼──────────────────────┐  │
                          │  │      Metrics + Logging + Health           │  │
                          │  │  Micrometer/Prometheus, Logback JSON,     │  │
                          │  │  CorrelationFilter, K8s liveness/readiness │  │
                          │  └───────────────────────────────────────────┘  │
                          └───────────┬─────────────────────┬───────────────┘
                                      │                     │
                    ┌─────────────────▼───┐     ┌───────────▼───────────┐
                    │   PostgreSQL 16      │     │      Redis 7          │
                    │   (Flyway managed)   │     │  Queue (sorted sets)  │
                    │                      │     │  Heartbeats           │
                    │  jobs, users,        │     │  Claimed set          │
                    │  attempts, outbox,   │     │  Session cache        │
                    │  audit_log,          │     │                       │
                    │  notifications,      │     │                       │
                    │  dead_letter_entries │     │                       │
                    └──────────────────────┘     └───────────────────────┘
```

### Job Lifecycle — State Machine

```
                    ┌──────────┐
                    │ CREATED  │  ← Job submitted via API
                    └────┬─────┘
                         │ outbox relay enqueues to Redis
                         ▼
                    ┌──────────┐
            ┌──────▶│  QUEUED  │  ← Waiting in Redis priority queue
            │       └────┬─────┘
            │            │ worker claims job from Redis
            │            ▼
            │       ┌──────────┐
            │       │ RUNNING  │  ← Handler executing
            │       └──┬───┬───┘
            │          │   │
            │  success │   │ failure
            │          ▼   ▼
            │  ┌────────┐  ┌────────┐
            │  │COMPLETED│  │ FAILED │
            │  └────────┘  └───┬────┘
            │                  │
            │          ┌───────┴────────┐
            │          │                │
            │    retries left    no retries left
            │          │                │
            │          ▼                ▼
            │    ┌──────────┐   ┌──────────────────┐
            └────│ RETRYING │   │ FAILED_PERMANENTLY│
                 └──────────┘   │   (Dead Letter)   │
                       │        └──────────────────┘
                       │ backoff expires
                       └──▶ QUEUED (re-enqueue)

    Cancellation (user-initiated):
        RUNNING → CANCELLING → CANCELLED
        QUEUED → CANCELLED
        CREATED → CANCELLED
```

### Container Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     docker compose up -d                         │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐ │
│  │   frontend    │  │   backend    │  │   Infrastructure      │ │
│  │   nginx:alpine│  │   JRE 21     │  │                       │ │
│  │              │  │   Spring Boot│  │  ┌─────────────────┐  │ │
│  │  :80 ────────┼──│─▶ :8080      │  │  │  PostgreSQL 16  │  │ │
│  │              │  │              │  │  │  :5432           │  │ │
│  │  /api ───────┼──│─▶ /api/v1    │  │  └─────────────────┘  │ │
│  │  /ws  ───────┼──│─▶ /ws        │  │                       │ │
│  │  /*  ────────┤  │              │  │  ┌─────────────────┐  │ │
│  │  (SPA)       │  │  Flyway auto │  │  │    Redis 7      │  │ │
│  └──────────────┘  │  migrations  │  │  │    :6379         │  │ │
│                    └──────────────┘  │  └─────────────────┘  │ │
│                                      └───────────────────────┘ │
│                                                                 │
│  Health checks:                                                 │
│    postgres: pg_isready -U anvil -d anvil                       │
│    redis:    redis-cli ping                                     │
│    backend:  wget http://localhost:8080/actuator/health          │
│    frontend: (implicit — nginx starts immediately)               │
│                                                                 │
│  Dependency ordering:                                           │
│    postgres + redis → backend → frontend                        │
│    (service_healthy condition on postgres/redis)                 │
└─────────────────────────────────────────────────────────────────┘
```

### Data Flow — Job Submission to Completion

```
  Client                Backend                PostgreSQL           Redis              Worker
    │                      │                      │                  │                   │
    │  POST /api/v1/jobs   │                      │                  │                   │
    │─────────────────────▶│                      │                  │                   │
    │                      │  BEGIN TRANSACTION   │                  │                   │
    │                      │  INSERT INTO jobs    │                  │                   │
    │                      │  INSERT INTO outbox  │                  │                   │
    │                      │  COMMIT              │                  │                   │
    │                      │─────────────────────▶│                  │                   │
    │  201 { id, QUEUED }  │                      │                  │                   │
    │◀─────────────────────│                      │                  │                   │
    │                      │                      │                  │                   │
    │                      │  OutboxRelay (1s)    │                  │                   │
    │                      │  SELECT from outbox  │                  │                   │
    │                      │─────────────────────▶│                  │                   │
    │                      │  ZADD queue:prio     │                  │                   │
    │                      │───────────────────────────────────────▶│                   │
    │                      │  UPDATE outbox RELAYED                  │                   │
    │                      │─────────────────────▶│                  │                   │
    │                      │                      │                  │                   │
    │                      │                      │     poll()       │                   │
    │                      │                      │◀─────────────────│──────────────────▶│
    │                      │                      │  ZRANGE + CLAIM  │                   │
    │                      │                      │─────────────────▶│                   │
    │                      │                      │                  │  claim(jobId)     │
    │                      │                      │                  │◀──────────────────│
    │                      │                      │                  │                   │
    │                      │                      │  UPDATE jobs     │                   │
    │                      │                      │  SET status=RUNNING                  │
    │                      │                      │◀─────────────────────────────────────│
    │                      │                      │                  │                   │
    │                      │                      │                  │  handler.execute()│
    │                      │                      │                  │                   │
    │  ◀── WS: PROGRESS ───│◀── reportProgress ──│──────────────────│───────────────────│
    │                      │                      │                  │                   │
    │                      │                      │  UPDATE jobs     │                   │
    │                      │                      │  SET status=COMPLETED                │
    │                      │                      │◀─────────────────────────────────────│
    │                      │                      │                  │                   │
    │  ◀── WS: STATUS ─────│◀── pushStatusUpdate ─│─────────────────│───────────────────│
    │                      │                      │                  │  ack(jobId)       │
    │                      │                      │                  │◀──────────────────│
```

### Retry & Recovery Flow

```
  Worker crashes mid-job
         │
         ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Recovery Mechanisms                          │
  │                                                                  │
  │  1. Visibility timeout (Redis claimed set expires after 30s)     │
  │     └─▶ reclaimOrphanedJobs() detects RUNNING job not in Redis  │
  │         └─▶ FAILED → RETRYING → QUEUED → worker re-executes     │
  │                                                                  │
  │  2. Worker heartbeat stale (>30s)                                │
  │     └─▶ checkStaleWorkers() marks worker UNHEALTHY              │
  │         └─▶ Reclaims currentJobId from stale worker              │
  │                                                                  │
  │  3. Backend process killed + restarted                           │
  │     └─▶ reEnqueueLostJobs() (@PostConstruct)                    │
  │         └─▶ Re-enqueues all QUEUED jobs to Redis on startup     │
  │                                                                  │
  │  4. Job execution timeout                                        │
  │     └─▶ checkTimedOutJobs() detects timeoutAt < now             │
  │         └─▶ FAILS job, triggers retry or DLQ                    │
  │                                                                  │
  │  5. Outbox durability                                            │
  │     └─▶ Jobs survive DB + queue dual-write via transactional    │
  │         outbox — if process dies between DB write and queue      │
  │         enqueue, outbox relay picks it up on restart             │
  └──────────────────────────────────────────────────────────────────┘
```

### Observability Stack

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                     Structured Logging                           │
  │                                                                 │
  │  logback-spring.xml:                                            │
  │    default profile → text with MDC (correlationId, jobId,       │
  │                                    workerId)                    │
  │    json profile    → LogstashEncoder (JSON for log aggregators) │
  │    production      → LogstashEncoder                            │
  │                                                                 │
  │  CorrelationIdFilter:                                           │
  │    X-Correlation-Id header → MDC correlationId                  │
  │    Generates UUID if not provided by client                     │
  │                                                                 │
  │  MDC threading across layers:                                   │
  │    API (CorrelationIdFilter)                                    │
  │    → OutboxRelay (MDC jobId)                                    │
  │    → RetryScheduler (MDC jobId)                                 │
  │    → ScheduledJobScheduler (MDC jobId)                          │
  │    → WorkerRunner (MDC jobId + workerId)                        │
  ├─────────────────────────────────────────────────────────────────┤
  │                     Prometheus Metrics                           │
  │                                                                 │
  │  Counters:    anvil.jobs.{submitted,completed,failed,dlq}       │
  │  Timers:      anvil.jobs.{submission,execution}.time            │
  │               (publishPercentiles: 0.5, 0.95, 0.99)            │
  │  Gauges:      anvil.queue.depth.{high,medium,low}               │
  │               anvil.workers.{total,healthy,paused,unhealthy}    │
  │               anvil.jobs.{queued,running,dlq.size}              │
  │                                                                 │
  │  Endpoint: /actuator/prometheus                                  │
  ├─────────────────────────────────────────────────────────────────┤
  │                     Health Probes                                │
  │                                                                 │
  │  Liveness:  /actuator/health/liveness  (K8s liveness probe)     │
  │  Readiness: /actuator/health/readiness (K8s readiness probe)    │
  │  Full:      /actuator/health (includes Postgres + Redis checks) │
  └─────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 21 |
| Framework | Spring Boot | 3.5.16 |
| Build | Maven | 3.9+ |
| Database | PostgreSQL | 16 |
| Migrations | Flyway | (managed by Spring Boot) |
| Cache/Queue | Redis | 7 |
| Auth | Spring Security + JJWT | 0.12.6 |
| WebSocket | Spring WebSocket (STOMP + SockJS) | (managed by Spring Boot) |
| API Docs | springdoc-openapi | 2.8.6 |
| JSONB columns | hypersistence-utils | 3.8.0 |
| Cron parsing | cron-utils | 9.2.1 |
| Metrics | Micrometer + Prometheus | (managed by Spring Boot) |
| Structured Logging | logstash-logback-encoder | 8.0 |
| Testing | JUnit 5 + Mockito + Testcontainers | 1.20.6 + Awaitility 4.3.0 |
| Frontend | React + TypeScript + Vite | 19 / 8.1.1 |
| Styling | Tailwind CSS | 4.3.2 |
| Frontend Testing | Vitest + React Testing Library | 4.1.10 |
| Containerization | Docker + Docker Compose | — |

---

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+ and npm
- Docker and Docker Compose

### Option A: Run everything with Docker

```bash
docker compose up -d
```

Starts all four services (PostgreSQL, Redis, backend, frontend) with health checks and dependency ordering. The backend waits for Postgres/Redis to be healthy before starting, and Flyway runs migrations automatically on first boot.

- Frontend: `http://localhost`
- Backend API: `http://localhost:8080`
- Health check: `http://localhost:8080/actuator/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`

Verify all services are healthy:

```bash
docker compose ps
```

To rebuild after code changes:

```bash
docker compose build --no-cache
docker compose up -d
```

To view logs:

```bash
docker compose logs -f backend
docker compose logs -f frontend
```

### Option B: Local development (infra via Docker, code runs natively)

#### 1. Start infrastructure

```bash
docker compose up -d postgres redis
```

Starts PostgreSQL 16 and Redis 7 with health checks. Verify:

```bash
docker compose ps
```

#### 2. Run the backend

```bash
cd backend
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`. Flyway runs migrations automatically on first boot.

Health check: `http://localhost:8080/actuator/health`

#### 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on `http://localhost:5173`. It provides login/register, job management, and real-time progress tracking via the backend API.

### Environment Variables

All have sensible defaults for local development. Override as needed:

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/anvil` | PostgreSQL connection URL |
| `DB_USERNAME` | `anvil` | Database username |
| `DB_PASSWORD` | `anvil` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `SERVER_PORT` | `8080` | Backend server port |
| `BACKEND_PORT` | `8080` | Host port for backend in Docker |
| `FRONTEND_PORT` | `80` | Host port for frontend in Docker |
| `POSTGRES_PORT` | `5432` | Host port for PostgreSQL in Docker |
| `JWT_SECRET` | (base64 dev key) | JWT signing secret — **change in production** |
| `JWT_ACCESS_EXPIRY_MS` | `900000` | Access token lifetime (15 min) |
| `JWT_REFRESH_EXPIRY_MS` | `604800000` | Refresh token lifetime (7 days) |
| `WORKER_ID` | `worker-1` | Unique worker identifier |
| `WORKER_POLL_INTERVAL_MS` | `2000` | How often workers poll the queue |
| `WORKER_VISIBILITY_TIMEOUT_S` | `30` | Seconds before a claimed job is re-queued |
| `WORKER_HEARTBEAT_INTERVAL_MS` | `10000` | Worker heartbeat frequency |
| `WORKER_RETRY_BACKOFF_SECONDS` | `30,60,120,240` | Per-attempt backoff durations |
| `WATCHDOG_INTERVAL_MS` | `15000` | Health check sweep interval |
| `WATCHDOG_HEARTBEAT_TIMEOUT_MS` | `30000` | Missed heartbeat threshold |
| `SCHEDULER_RETRY_INTERVAL_MS` | `5000` | Retry scanner interval |
| `SCHEDULER_SCHEDULED_SCAN_INTERVAL_MS` | `5000` | Scheduled jobs scanner interval |
| `QUEUE_RELAY_INTERVAL_MS` | `1000` | Outbox-to-queue relay interval |
| `WEBSOCKET_ALLOWED_ORIGINS` | `*` | CORS origins for WebSocket endpoint |

---

## API Overview

All endpoints are under `/api/v1`. Authenticated endpoints require a `Bearer` token in the `Authorization` header.

| Group | Base Path | Description |
|---|---|---|
| Auth | `/api/v1/auth` | Register, login, refresh, logout |
| Users | `/api/v1/users` | Current user profile (`/me`) |
| Jobs | `/api/v1/jobs` | Create, list, get, cancel jobs |
| Notifications | `/api/v1/notifications` | List, unread count, mark read |
| Admin: Stats | `/api/v1/admin/stats` | Dashboard overview |
| Admin: Workers | `/api/v1/admin/workers` | List, pause, resume, restart |
| Admin: DLQ | `/api/v1/admin/dlq` | List, get, requeue, discard |
| Admin: Audit Log | `/api/v1/admin/audit-log` | Filtered, paginated query |
| Admin: Ping | `/api/v1/admin/ping` | Admin auth verification |

OpenAPI docs available at `http://localhost:8080/swagger-ui/index.html` when the backend is running.

### WebSocket

Connect via STOMP over SockJS at `http://localhost:8080/ws`.

**Subscribe to:**
- `/topic/user/{userId}/notifications` — per-user notification stream
- `/topic/job/{jobId}` — per-job progress and status updates

JWT authentication happens on the STOMP CONNECT frame via the `Authorization` header. Cross-user topic subscriptions are rejected.

---

## Testing

### Backend

```bash
cd backend
mvn test
```

**Current count: 180 tests, 0 failures**

Coverage includes:

- **Unit tests** — password hashing, JWT generation/validation, state machine transitions, retry backoff, priority queue ordering, rate limiting
- **Contract tests** — every REST endpoint tested for happy path, validation failure, unauthorized, forbidden, not-found
- **Integration tests** — Testcontainers (Postgres + Redis) for queue operations, worker claim/pause/resume, outbox relay, WebSocket auth and subscriptions, admin stats with seeded data, job list filter combinations (JPA Specification-based), orphan job reclamation, worker watchdog stale detection
- **Email notification tests** — async annotation verification, email-disabled behavior

Tests run automatically against real PostgreSQL and Redis instances spun up by Testcontainers. No mocking of database interactions in repository-layer tests.

### Load & Chaos Tests

```bash
# Load test (requires running backend)
.\scripts\load-test.ps1

# Chaos test (kills/restarts backend 20 times)
.\scripts\chaos-test.ps1 -Iterations 20 -JobsPerIteration 3
```

- **Load test:** API submission throughput=3857/min, p95=17ms, p99=34ms (measures HTTP accept rate, not end-to-end execution throughput)
- **Chaos test:** 20 single-process kill/restart iterations, 60 jobs, zero loss. Tests single-instance crash recovery via outbox pattern + orphan reclamation. Does NOT test multi-instance fault tolerance (where a surviving worker recovers a dead worker's orphaned job) — that path is architecturally supported but not empirically validated.

### Frontend

```bash
cd frontend
npm test
```

**Current count: 31 tests, 0 failures**

Coverage includes:

- **Job list** — rendering, filtering by status/type/page, empty state, loading skeleton, error state with retry, status/priority badges, pagination
- **Job submission form** — validation for missing job type, invalid JSON payload, empty cron expression, empty scheduled date; successful submission; API error display; real-time payload validation
- **Admin console** — route guard (redirects non-admin, redirects unauthenticated, renders admin content, shows spinner while loading); overview dashboard (renders stat tiles, job type breakdown, error state, loading skeleton); worker management (renders table, pause/resume buttons, error state, empty state)

---

## Project Structure

```
├── backend/
│   ├── Dockerfile                           # Multi-stage: Maven+JDK21 → JRE21-alpine
│   ├── .dockerignore
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/anvil/
│       │   ├── BackendApplication.java      # @EnableScheduling, @EnableAsync
│       │   ├── api/                         # REST controllers + DTOs
│       │   │   ├── AuthController.java      # /auth (register, login, refresh, logout)
│       │   │   ├── UserController.java      # /users/me
│       │   │   ├── JobController.java       # /jobs (CRUD, cancel)
│       │   │   ├── NotificationController.java  # /notifications
│       │   │   ├── AdminController.java     # /admin/ping
│       │   │   ├── AdminStatsController.java    # /admin/stats/overview
│       │   │   ├── AdminWorkerController.java   # /admin/workers
│       │   │   ├── DlqController.java       # /admin/dlq
│       │   │   ├── AuditLogController.java  # /admin/audit-log
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   └── dto/                     # Request/response records
│       │   ├── auth/                        # Spring Security + JWT provider + filters
│       │   ├── job/
│       │   │   ├── domain/                  # Job, JobAttempt, JobStatus, JobStateMachine
│       │   │   ├── handler/                 # JobHandler interface + 7 concrete handlers
│       │   │   ├── service/                 # JobService (create/cancel/retry business rules)
│       │   │   └── repository/              # Spring Data JPA repositories
│       │   ├── queue/                       # AnvilQueue interface + RedisQueue + OutboxRelay
│       │   ├── worker/                      # WorkerRunner, WorkerWatchdog, WorkerRepository
│       │   ├── scheduler/                   # RetryScheduler, ScheduledJobScheduler, CronSchedulerHelper
│       │   ├── notification/                # WebSocket push (SimpMessagingTemplate), email, persistence
│       │   ├── admin/                       # AdminStatsService, DeadLetterService
│       │   ├── audit/                       # AuditLogService (append-only)
│       │   └── config/                      # SecurityConfig, WebSocketConfig, OpenApiConfig,
│       │                                    # CorrelationIdFilter, MetricsService, GracefulShutdownHandler
│       └── resources/
│           ├── application.yaml             # Externalized config (env vars with defaults)
│           ├── logback-spring.xml           # Structured logging (text/json/production)
│           └── db/migration/                # Flyway SQL migrations
│
├── frontend/
│   ├── Dockerfile                           # Multi-stage: Node22 → nginx-alpine
│   ├── .dockerignore
│   ├── nginx.conf                           # SPA routing + /api,/ws proxy to backend
│   ├── package.json
│   ├── vite.config.ts                       # Dev server proxy + Vitest config
│   ├── tsconfig.json / tsconfig.app.json
│   └── src/
│       ├── App.tsx                          # Router (protected + public + admin routes)
│       ├── main.tsx                         # Entry point
│       ├── index.css                        # Tailwind + base styles
│       ├── tokens.ts                        # Design tokens (zinc-gray palette, status badges)
│       ├── types.ts                         # TypeScript types matching backend API contracts
│       ├── api/client.ts                    # Fetch-based API client with JWT auto-refresh
│       ├── contexts/AuthContext.tsx          # Auth state + login/register/logout
│       ├── hooks/useWebSocket.ts            # STOMP WebSocket hook for job progress
│       ├── components/
│       │   ├── Layout.tsx                   # App shell (nav, user menu, admin link)
│       │   ├── AdminRoute.tsx               # Admin role-based route guard
│       │   ├── NotificationCenter.tsx       # Bell icon, unread count, mark as read
│       │   └── ui/                          # Badges, ProgressBar, Feedback (skeleton/empty/error)
│       ├── pages/
│       │   ├── Login.tsx                    # Sign in form
│       │   ├── Register.tsx                 # Create account form
│       │   ├── JobList.tsx                  # Paginated list with status/type filters
│       │   ├── CreateJob.tsx                # Job submission with type selector + validation
│       │   └── JobDetail.tsx                # Job detail with live progress + cancel
│       ├── pages/admin/
│       │   ├── Overview.tsx                 # System overview dashboard (stat tiles + job type breakdown)
│       │   ├── Workers.tsx                  # Worker management (pause/resume/restart)
│       │   ├── Dlq.tsx                      # Dead letter queue (expandable, requeue/discard)
│       │   └── AuditLog.tsx                 # Audit log (actor ID + action filters)
│       └── pages/__tests__/                 # Vitest + RTL tests (31 tests)
│
├── docker-compose.yml                       # 4 services: postgres, redis, backend, frontend
├── scripts/
│   ├── load-test.ps1                        # PowerShell load test
│   └── chaos-test.ps1                       # PowerShell chaos test
└── docs/
    ├── PRD.md                               # Full product spec
    ├── IMPLEMENTATION_PLAN.md               # Phased build plan
    ├── AGENTS.md                            # Agent constraints & conventions
    └── phase-10-summary.md                  # Phase 10 completion summary
```

---

## Known Limitations

- **Handler idempotency:** Job handlers (`EMAIL_CAMPAIGN`, `CSV_IMPORT`, `AI_CONTENT_GENERATION`) are not idempotent. After a worker crash, `reclaimOrphanedJobs()` retries the handler, which would double-send emails, double-insert rows, or double-charge API credits in a real implementation. Production handlers must implement their own deduplication (idempotency keys, conditional writes). See `JobHandler` interface Javadoc for the contract.
- **Chaos test scope:** The chaos test validates single-instance crash recovery (entire backend killed + restarted). It does not exercise multi-instance fault tolerance where a surviving worker recovers a dead worker's orphaned job. The architecture supports this via `reclaimOrphanedJobs()` + Redis claimed-set checks, but it is not empirically validated.
- **Submission vs execution throughput:** The load test measures API submission throughput (HTTP accept rate), not end-to-end execution throughput (submission → queue → worker → completion). Actual execution throughput is limited by worker poll interval and handler duration.
- **Progress-to-completion gap:** The frontend shows 100% progress ~3 seconds before the status transitions to COMPLETED. This is a timing nuance — the handler reports progress=100% during its final iteration, but the state machine transition happens after the handler returns. The result section is correctly gated on `status=COMPLETED`, not `progressPct=100`, so no data is shown prematurely.

---

## Contributing

This project is built in phases following [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md). The build conventions and constraints for anyone (human or AI agent) picking up work are documented in [`docs/AGENTS.md`](docs/AGENTS.md) — read it before making changes.

Key conventions:

- **Architecture:** strict layering — controllers contain no business logic, services contain all business rules, repositories contain no business logic
- **State transitions** are centralized in `JobStateMachine` — never mutate `job.status` directly
- **Job-type-specific logic** lives only in `JobHandler` implementations — no `if (jobType == ...)` outside handler classes
- **Queue writes** use the transactional outbox pattern — never dual-write to DB + queue directly
- **Config over hardcoding** — all tuning knobs externalized via environment variables with sensible defaults
- **Tests** are required for every phase — unit tests for business logic, integration tests with Testcontainers for DB/queue, contract tests for endpoints
- **Commits** follow conventional commit format: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`
