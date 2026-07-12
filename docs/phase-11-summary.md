# Phase 11 Summary — Hardening, Observability, Load Testing

**Date:** 2026-07-12
**Status:** Complete

## What Was Implemented

### Structured JSON Logging
- `CorrelationIdFilter` — assigns `X-Correlation-Id` to every request, stored in MDC
- Worker/queue/scheduler classes log with `jobId` in MDC for full lifecycle tracing
- `logback-spring.xml` — JSON output (logstash-logback-encoder) in prod,彩色 readable output in dev

### Prometheus Metrics (`MetricsService`)
- Per-priority queue depth: `anvil.queue.depth.high`, `anvil.queue.depth.medium`, `anvil.queue.depth.low`
- Aggregate queue depth: `anvil.queue.depth`
- Worker count: `anvil.workers.total` / `anvil.workers.healthy`
- Job throughput: `anvil.jobs.submitted`, `anvil.jobs.completed`, `anvil.jobs.failed`, `anvil.jobs.retried`
- Job latency histograms: `anvil.job.execution.duration` (p50/p95/p99 via Timer)
- DLQ size: `anvil.queue.dlq.size`

### Health/Readiness Endpoints
- Spring Boot Actuator enabled: `/actuator/health`, `/actuator/prometheus`, `/actuator/info`
- Kubernetes liveness: `/actuator/health/liveness`
- Kubernetes readiness: `/actuator/health/readiness`
- Redis health check via `RedisHealthIndicator`

### Graceful Shutdown
- `GracefulShutdownHandler` — `SmartLifecycle` bean, stops accepting new work on SIGTERM
- `WorkerRunner.shutdown()` — `AtomicBoolean` flag, current job completes or claim released
- Docker: `stop_grace_period: 30s` in docker-compose

### Chaos Test Fixes
- **Root cause of initial failures:** `Get-Process java | Stop-Process -Force` killed Maven wrapper children, leaving orphaned Java processes on port 8080. Worker appeared dead but port was occupied.
- **Fix:** `chaos-test.ps1` now kills all Java processes on port 8080 via `netstat` + PID lookup, polls for job completion (180s max) instead of fixed waits, uses 4s submit-to-kill delay for outbox relay.
- **Result:** 20 iterations, 60 jobs, 0 losses.

### Reliability Improvements
- `WorkerWatchdog.reEnqueueLostJobs()` — `@PostConstruct` re-enqueues all QUEUED jobs to Redis on startup (recovers jobs lost from Redis during a kill)
- `WorkerWatchdog.reclaimOrphanedJobs()` — `@Scheduled` every 15s, detects RUNNING jobs not in Redis claimed set, transitions to FAILED→RETRYING with zero-delay retry
- `RedisQueue.isClaimed(UUID jobId)` — checks if a job is in the Redis claimed set
- `JobRepository.findByStatus(JobStatus)` — query for orphan detection

### Audit & Gap Closure
- Per-priority queue depth metrics added (was only aggregate)
- MDC `jobId` context added to `RetryScheduler`, `ScheduledJobScheduler`, `OutboxRelay`
- Handler idempotency documented in `JobHandler` interface and each concrete handler (known limitation for `EMAIL_CAMPAIGN`, `CSV_IMPORT`, `AI_CONTENT_GENERATION`)

### Containerization
- `backend/Dockerfile` — multi-stage: `maven:3.9-eclipse-temurin-21` build → `eclipse-temurin:21-jre-alpine` runtime
- `frontend/Dockerfile` — multi-stage: `node:22-alpine` build → `nginx:alpine` serve
- `frontend/nginx.conf` — SPA routing, `/api/` proxy → `backend:8080`, WebSocket upgrade headers
- `docker-compose.yml` — 4 services: postgres, redis, backend, frontend with health checks and dependency ordering
- `.dockerignore` files for both backend and frontend
- **Smoke test PASSED:** full stack clean start, register → submit → COMPLETED with real-time progress

### README Rewrite
- System architecture diagram (ASCII)
- Job lifecycle state machine diagram
- Container architecture diagram
- Data flow sequence diagram
- Retry/recovery flow diagram
- Observability stack diagram
- Complete project structure
- Environment variables table
- Known limitations section

## Files Created
- `backend/src/main/java/com/anvil/config/CorrelationIdFilter.java`
- `backend/src/main/java/com/anvil/config/GracefulShutdownHandler.java`
- `backend/src/main/java/com/anvil/config/MetricsService.java`
- `backend/src/main/resources/logback-spring.xml`
- `backend/Dockerfile`
- `backend/.dockerignore`
- `frontend/Dockerfile`
- `frontend/.dockerignore`
- `frontend/nginx.conf`
- `scripts/chaos-test.ps1`
- `scripts/load-test.ps1`

## Files Modified
- `backend/src/main/java/com/anvil/worker/WorkerRunner.java` — MDC context, graceful shutdown flag
- `backend/src/main/java/com/anvil/worker/WorkerWatchdog.java` — reEnqueueLostJobs, reclaimOrphanedJobs, RedisQueue dependency
- `backend/src/main/java/com/anvil/queue/OutboxRelay.java` — MDC jobId context
- `backend/src/main/java/com/anvil/queue/RedisQueue.java` — isClaimed() method
- `backend/src/main/java/com/anvil/scheduler/RetryScheduler.java` — MDC jobId context
- `backend/src/main/java/com/anvil/scheduler/ScheduledJobScheduler.java` — MDC jobId context
- `backend/src/main/java/com/anvil/job/repository/JobRepository.java` — findByStatus() method
- `backend/src/main/java/com/anvil/job/handler/*.java` — idempotency Javadoc on all handlers
- `backend/src/main/resources/application.yaml` — actuator, metrics, graceful shutdown config
- `docker-compose.yml` — backend + frontend services
- `README.md` — fully rewritten

## Test Results

| Suite | Tests | Status |
|-------|-------|--------|
| Backend | 180 | All passing |
| Frontend | 31 | All passing |
| **Total** | **211** | **All passing** |

### Chaos Test
- 20 kill/restart iterations
- 60 total jobs submitted
- 0 jobs lost
- All jobs reached terminal state (COMPLETED or FAILED with retry)

### Load Test
- 100 concurrent connections, 60s duration
- 100% success rate
- p50: 12ms, p95: 17ms, p99: 34ms (submission latency)
- ~3857 submissions/min (API accept rate, not end-to-end execution)

## Disclosure Notes

- Chaos test validates single-instance crash recovery (entire backend killed + restarted), NOT multi-instance fault tolerance
- Load test measures API submission throughput, NOT end-to-end execution throughput (~30 jobs/min based on 2s worker poll interval)
- Handler idempotency is documented as known limitation — `EMAIL_CAMPAIGN`, `CSV_IMPORT`, `AI_CONTENT_GENERATION` may double-execute on crash-retry
- Progress-to-completion gap: ~3s window where progress=100% but status=RUNNING; frontend correctly gates on `status === 'COMPLETED'`, not `progressPct === 100`

## Project Complete

All 12 phases (0-11) of the Anvil distributed job processing platform are implemented and verified. The system includes:

- **Backend:** Java 21 + Spring Boot 3.5, PostgreSQL, Redis, JWT auth, WebSocket real-time push
- **Frontend:** React 19 + TypeScript + Vite + Tailwind CSS
- **Infrastructure:** Docker containers, structured logging, Prometheus metrics, Kubernetes-ready health endpoints
- **Reliability:** Outbox pattern, retry with exponential backoff, chaos-tested crash recovery, graceful shutdown
- **Observability:** Structured JSON logs with correlation IDs, Prometheus metrics, admin console
