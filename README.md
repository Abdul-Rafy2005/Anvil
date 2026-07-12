# Anvil

**Submit a job. Don't block the request. Get results when they're ready.**

Anvil is a distributed job processing platform. Clients submit work (CSV imports, report generation, image processing, etc.) via a REST API and receive a job ID immediately. A worker pool picks up jobs asynchronously, executes them with automatic retries and backoff, and pushes real-time progress updates over WebSocket. Admins get operational visibility through stats, worker management, audit logs, and a dead letter queue.

This is the backend implementation covering Phases 0-8 of the build. See [`docs/PRD.md`](docs/PRD.md) for the full product spec and [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for the phased build approach.

## Current Status

**Implemented (Phases 0-8):**

- Authentication and role-based access control (register, login, JWT refresh/logout, rate limiting)
- Job CRUD with idempotency, cancellation, status tracking, and priority tiers (HIGH/MEDIUM/LOW)
- Redis-backed priority queue with transactional outbox pattern
- Worker pool with heartbeat monitoring, automatic reclamation of stalled jobs, and pause/resume
- 7 job handlers (CSV Import, Email Campaign, File Compression, Image Processing, Report Generation, AI Content Generation, Always-Fail)
- Automatic retry with exponential backoff and dead letter queue (requeue/discard)
- Scheduled and cron-based job scheduling (cron-utils validated)
- WebSocket notifications (STOMP over SockJS) with per-user authorization and job progress streaming
- Admin stats overview, worker management, audit log querying, and DLQ management

**Not yet implemented:**

- Frontend (placeholder shell only) — no job dashboard, admin views, or auth screens
- Load/chaos testing (Phase 11)
- Frontend job management, charts, real-time progress UI (Phases 9-10)
- Production hardening — do not treat this as production-ready

See [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for the full phase list and what comes next.

## Architecture

```
┌──────────────┐      ┌──────────────┐      ┌──────────────────┐
│   REST API   │─────▶│  PostgreSQL   │◀─────│  Scheduler       │
│  (Spring MVC)│      │  (Flyway)     │      │  (cron/delayed)  │
└──────┬───────┘      └──────────────┘      └────────┬─────────┘
       │                                             │
       │   ┌──────────────┐      ┌──────────────┐   │
       ├──▶│  Outbox       │─────▶│  Redis Queue  │◀──┘
       │   │  (transactional)│     │  (priority)   │
       │   └──────────────┘      └──────┬───────┘
       │                                │
       │                     ┌──────────▼──────────┐
       │                     │    Worker Pool       │
       │                     │  (claim → execute    │
       │                     │   → ack/nack)        │
       │                     └──────────┬──────────┘
       │                                │
       └──────▶ WebSocket ◀─────────────┘
                (STOMP/SockJS)
```

- **API layer** receives job submissions and writes to Postgres + outbox table in one transaction
- **Outbox relay** moves entries from the outbox to Redis sorted sets (priority ordering)
- **Workers** poll Redis, claim jobs, execute handlers, update status, and push progress via WebSocket
- **Scheduler** scans for due scheduled/retrying jobs and moves them to the queue
- **Watchdog** detects missed heartbeats and marks unhealthy workers

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
| Testing | JUnit 5 + Mockito + Testcontainers | 1.20.6 + Awaitility 4.3.0 |
| Frontend | React + TypeScript + Vite | 19 / 8.1.1 |
| Styling | Tailwind CSS | 4.3.2 |
| Containerization | Docker + Docker Compose | — |

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+ and npm
- Docker and Docker Compose

### 1. Start infrastructure

```bash
docker compose up -d
```

Starts PostgreSQL 16 and Redis 7 with health checks. Verify:

```bash
docker compose ps
```

### 2. Run the backend

```bash
cd backend
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`. Flyway runs migrations automatically on first boot.

Health check: `http://localhost:8080/actuator/health`

### 3. Run the frontend (optional — placeholder only)

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on `http://localhost:5173`. Currently a placeholder landing page.

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

## Testing

```bash
cd backend
mvn test
```

**Current count: 168 tests, 0 failures**

Coverage includes:

- **Unit tests** — password hashing, JWT generation/validation, state machine transitions, retry backoff, priority queue ordering, rate limiting
- **Contract tests** — every REST endpoint tested for happy path, validation failure, unauthorized, forbidden, not-found
- **Integration tests** — Testcontainers (Postgres + Redis) for queue operations, worker claim/pause/resume, outbox relay, WebSocket auth and subscriptions, admin stats with seeded data
- **Email notification tests** — async annotation verification, email-disabled behavior

Tests run automatically against real PostgreSQL and Redis instances spun up by Testcontainers. No mocking of database interactions in repository-layer tests.

## Project Structure

```
backend/src/main/java/com/anvil/
├── BackendApplication.java          # @EnableScheduling, @EnableAsync
├── api/                             # REST controllers + DTOs
│   ├── AuthController.java          # /auth (register, login, refresh, logout)
│   ├── UserController.java          # /users/me
│   ├── JobController.java           # /jobs (CRUD, cancel)
│   ├── NotificationController.java  # /notifications
│   ├── AdminController.java         # /admin/ping
│   ├── AdminStatsController.java    # /admin/stats/overview
│   ├── AdminWorkerController.java   # /admin/workers (list, pause, resume, restart)
│   ├── DlqController.java           # /admin/dlq
│   ├── AuditLogController.java      # /admin/audit-log
│   ├── GlobalExceptionHandler.java
│   └── dto/                         # Request/response records
├── auth/                            # Spring Security + JWT
│   ├── SecurityConfig.java          # Filter chain, BCrypt cost 12
│   ├── JwtProvider.java             # Token generation/validation
│   ├── JwtAuthenticationFilter.java
│   ├── AuthService.java             # Register/login/refresh/logout logic
│   ├── LoginRateLimiter.java
│   ├── User.java                    # Entity (email, password_hash, role, is_active)
│   ├── RefreshToken.java            # Server-side refresh token (revocable)
│   └── Role.java                    # USER, ADMIN
├── job/
│   ├── domain/
│   │   ├── Job.java                 # Entity (status, priority, payload, progress)
│   │   ├── JobAttempt.java          # Per-attempt record (worker, duration, error)
│   │   ├── JobStatus.java           # CREATED → QUEUED → RUNNING → COMPLETED/FAILED/...
│   │   ├── JobPriority.java         # HIGH, MEDIUM, LOW
│   │   ├── JobStateMachine.java     # Centralized transition validation + audit
│   │   └── DeadLetterEntry.java     # Failed job archival
│   ├── handler/
│   │   ├── JobHandler.java          # Interface: execute(payload, ctx)
│   │   ├── JobHandlerRegistry.java  # Auto-discovers all JobHandler beans
│   │   ├── CsvImportHandler.java
│   │   ├── EmailCampaignHandler.java
│   │   ├── FileCompressionHandler.java
│   │   ├── ImageProcessingHandler.java
│   │   ├── ReportGenerationHandler.java
│   │   ├── AiContentGenerationHandler.java
│   │   └── AlwaysFailHandler.java   # For testing retry/DLQ behavior
│   ├── repository/
│   │   ├── JobRepository.java
│   │   ├── JobAttemptRepository.java
│   │   ├── DeadLetterEntryRepository.java
│   │   └── IdempotencyKeyRepository.java
│   └── service/
│       └── JobService.java          # Create/cancel/list business logic
├── queue/
│   ├── AnvilQueue.java              # Interface (enqueue, claim, ack, nack, size)
│   ├── RedisQueue.java              # Redis sorted-set implementation
│   ├── OutboxEntry.java             # Transactional outbox entity
│   ├── OutboxEntryRepository.java
│   ├── OutboxRelay.java             # Outbox → Redis relay (scheduled)
│   └── OutboxStatus.java
├── worker/
│   ├── WorkerRunner.java            # Poll → claim → execute → ack/nack loop
│   ├── Worker.java                  # Entity (hostname, status, current_job_id)
│   ├── WorkerStatus.java            # HEALTHY, PAUSED, UNHEALTHY
│   ├── WorkerRepository.java
│   └── WorkerWatchdog.java          # Detects missed heartbeats
├── scheduler/
│   ├── ScheduledJobScanner.java     # Enqueues due scheduled jobs
│   ├── RetryScanner.java            # Enqueues due retrying jobs
│   └── CronSchedulerHelper.java     # cron-utils validation
├── notification/
│   ├── NotificationService.java     # Create, push (WebSocket), persist
│   ├── EmailNotificationService.java
│   ├── Notification.java            # Entity
│   ├── NotificationRepository.java
│   ├── NotificationController.java
│   └── NotificationType.java        # JOB_COMPLETED, JOB_FAILED, JOB_FAILED_PERMANENTLY, JOB_CANCELLED
├── admin/
│   ├── AdminStatsService.java       # Aggregated dashboard overview
│   ├── AdminWorkerService.java      # Pause/resume/restart workers
│   └── DeadLetterService.java       # DLQ requeue/discard
├── audit/
│   ├── AuditLogEntry.java           # Append-only entity
│   ├── AuditLogEntryRepository.java
│   ├── AuditLogService.java         # Structured audit writes
│   └── AuditLogQueryService.java    # Paginated filtered queries
└── config/
    ├── WebSocketConfig.java         # STOMP broker, SockJS endpoint
    └── WebSocketAuthChannelInterceptor.java  # JWT auth + per-user topic authorization
```

## Contributing

This project is built in phases following [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md). The build conventions and constraints for anyone (human or AI agent) picking up work are documented in [`docs/AGENTS.md`](docs/AGENTS.md) — read it before making changes.

Key conventions:

- **Architecture:** strict layering — controllers contain no business logic, services contain all business rules, repositories contain no business logic
- **State transitions** are centralized in `JobStateMachine` — never mutate `job.status` directly
- **Job-type-specific logic** lives only in `JobHandler` implementations — no `if (jobType == ...)` outside handler classes
- **Queue writes** use the transactional outbox pattern — never dual-write to DB + queue directly
- **Tests** are required for every phase — unit tests for business logic, integration tests with Testcontainers for DB/queue, contract tests for endpoints
- **Commits** follow conventional commit format: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`
