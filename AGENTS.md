# AGENTS.md — Anvil Project Context & Constraints

This file is the source of truth for any AI coding agent (Claude Code, Cursor, Copilot
Workspace, etc.) working in this repository. Read this in full before writing code.
If anything in a prompt conflicts with this file, this file wins.

---

## 1. Project Vision (one paragraph)

Anvil is an enterprise-grade distributed job processing platform: clients submit jobs
instead of doing heavy work inline, a worker pool executes them asynchronously with
retries, scheduling, priority, and progress tracking, and admins get full operational
visibility. Build it like it will run in production for a real company with thousands
of users — not like a tutorial project. Prefer correctness and clarity over cleverness.

Full functional/non-functional spec: see `PRD.md` in this repo. This file covers *how*
to build it; the PRD covers *what* to build.

---

## 2. Tech Stack (fixed — do not substitute without asking)

| Layer | Choice |
|---|---|
| Backend language/framework | Java 21, Spring Boot 3.x |
| Build tool | Maven |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Queue | Redis (via Redis Streams or a list-based priority queue) — chosen for simplicity in v1; the queue is abstracted behind an interface so it can be swapped for RabbitMQ/SQS later without touching business logic |
| Caching / heartbeats / distributed locks | Redis |
| Real-time push | Spring WebSocket (STOMP) |
| Auth | Spring Security + JJWT |
| Frontend | React 18 + TypeScript, Vite |
| Frontend styling | Tailwind CSS + a small design-token layer (see §6) |
| API contract | REST, OpenAPI 3 spec generated from code (springdoc-openapi) |
| Testing (backend) | JUnit 5, Mockito, Testcontainers (Postgres + Redis) for integration tests |
| Testing (frontend) | Vitest + React Testing Library |
| Containerization | Docker + docker-compose for local dev |

Do not introduce a new datastore, message broker, or frontend framework without
flagging it explicitly and explaining the tradeoff — this is a constraint, not a
suggestion.

---

## 3. Architecture Rules

1. **Layered architecture, strictly:** `controller → service → repository`. Controllers
   contain no business logic. Repositories contain no business logic. Business rules
   live in services.
2. **The queue/scheduler/worker layer must never know about specific job types.**
   All job-type-specific logic lives behind the `JobHandler` interface (PRD §9).
   If you find yourself writing `if (jobType.equals("REPORT_GENERATION"))` anywhere
   outside a handler class, stop — that's an architecture violation.
3. **No business logic in the database layer** beyond constraints/indexes. No stored
   procedures for domain logic.
4. **State transitions are centralized.** All job status changes go through one
   `JobStateMachine` / `JobLifecycleService` component that validates legal
   transitions (per the PRD §5 diagram) and writes the audit log entry in the same
   transaction. Do not update `job.status` directly from scattered call sites.
5. **Outbox pattern for queue writes.** When a job is created/re-queued, the DB write
   and the queue enqueue must not silently diverge — use a transactional outbox table
   + a relay process, not a direct dual-write, to avoid the classic "written to DB but
   never enqueued" bug.
6. **Idempotency matters.** Worker job execution must tolerate at-least-once delivery.
   Handlers should be written assuming they might run twice for the same attempt in
   rare crash-recovery scenarios; document any handler that isn't safely repeatable.
7. **Config over hardcoding.** Retry counts, backoff intervals, timeouts, and priority
   aging thresholds are configuration (application.yml + DB-level per-job-type
   overrides), never magic numbers buried in service code.

---

## 4. Package Structure (backend)

```
com.anvil
├── api                 # REST controllers, request/response DTOs, exception handlers
├── auth                # Spring Security config, JWT provider, filters
├── job
│   ├── domain          # Job, JobAttempt entities, JobStatus enum, JobStateMachine
│   ├── handler          # JobHandler interface + concrete handlers (ReportGenerationHandler, etc.)
│   ├── service          # JobService (create/cancel/retry business rules)
│   └── repository       # Spring Data JPA repositories
├── queue                # Queue abstraction interface + Redis implementation
├── worker                # Worker pool runner, heartbeat, claim/execute loop
├── scheduler             # Cron/delayed job scanner, leader-election-safe
├── notification          # WebSocket push, email sender, notification persistence
├── admin                 # Admin-only services: stats aggregation, DLQ management
├── audit                 # AuditLogService, append-only writes
└── config                 # Spring config classes (Redis, WebSocket, OpenAPI, etc.)
```

Mirror this shape — don't invent a parallel structure per feature.

---

## 5. API Conventions

- Base path: `/api/v1`.
- Standard error response shape for all 4xx/5xx:
  ```json
  { "error": { "code": "JOB_NOT_FOUND", "message": "...", "traceId": "..." } }
  ```
- Pagination: `?page=0&size=20`, response includes `{ content, page, size, totalElements, totalPages }`.
- All list endpoints must support filtering by the fields a user would realistically
  filter by (status, jobType, date range) — check the PRD's persona sections for what
  each role needs to see.
- Every admin-only endpoint must have a corresponding test asserting a non-admin gets
  403, not just that an admin gets 200.

---

## 6. Frontend Design Constraints

**Do not produce a generic AI-app look.** No default shadcn purple gradients, no
emoji-as-icons, no centered hero with a big rounded gradient button. This is an
enterprise operational tool — it should feel closer to Linear, Vercel's dashboard, or
Datadog than to a landing page.

Concrete constraints:
- **Color:** a restrained neutral base (slate/zinc grays) with a single deliberate
  accent color used sparingly for primary actions and status. Status colors
  (queued/running/completed/failed) should be a consistent, accessible semantic set
  reused everywhere (badges, charts, dashboard tiles) — decide this palette once in a
  shared token file, never pick colors ad hoc per component.
- **Typography:** one primary sans-serif (e.g. Inter or system font stack), a clear
  type scale (don't invent new font sizes per component), tabular numerals for any
  numeric/stat display.
- **Density:** admin views are data-dense tables and compact stat tiles; user-facing
  views can breathe more, but neither should feel like a marketing page.
- **Consistency:** one component library approach used everywhere (buttons, inputs,
  tables, modals, toasts) — don't mix ad hoc styled divs with a component kit.
- **States:** every data view must have a designed empty state, loading state
  (skeletons, not spinners for lists/tables), and error state — this is not optional
  polish, it's part of Definition of Done.
- **Real-time indicators:** running jobs show live progress bars driven by WebSocket
  updates, not just static percentages that only update on page refresh.

Read `/mnt/skills/public/frontend-design/SKILL.md`-equivalent guidance if the coding
agent has access to a frontend design skill — apply it before finalizing any screen.

---

## 7. Job Handler Contract (recap — see PRD §9 for full interface)

Every job type is one class implementing `JobHandler<TPayload, TResult>`. New job
types must be addable by: (1) adding a handler class, (2) registering its payload
schema, (3) nothing else. If adding a job type requires touching the queue, scheduler,
or worker loop, that's a bug in the abstraction — fix the abstraction, don't special-case.

---

## 8. Testing Requirements (non-negotiable per phase)

- **Unit tests** for all service-layer business logic (state machine transitions,
  retry backoff calculation, priority queue ordering).
- **Integration tests** (Testcontainers) for anything touching Postgres or Redis —
  no mocking the DB for the repository layer's own tests.
- **Contract tests** for every REST endpoint: happy path, validation failure,
  unauthorized, forbidden (wrong role), not-found.
- **Chaos/resilience test** (at least one, manually or scripted) simulating a worker
  crash mid-job and confirming the job is reclaimed and completes.
- Every phase in the implementation plan ends with a testing checklist. Do not mark a
  phase done until its tests pass AND the manual verification steps succeed.

---

## 9. Things to Never Do

- Never trust client-supplied role/user-id claims outside the validated JWT.
- Never put job-type-specific `if/else` or `switch` logic outside a `JobHandler`.
- Never perform a blocking, long-running operation on the API request thread — that
  defeats the entire point of the system.
- Never delete or mutate audit log rows from application code.
- Never hardcode secrets, connection strings, or JWT signing keys in source.
- Never mark a phase complete without running the tests specified for that phase.
- Never introduce a new top-level dependency (new DB, new broker, new frontend
  framework) without explicitly calling it out — see §2.

---

## 10. Definition of Done (applies to every phase/feature)

- [ ] Code follows the package structure in §4.
- [ ] Business logic has unit test coverage; DB/queue interactions have integration
      test coverage.
- [ ] New/changed endpoints documented in the OpenAPI spec (auto-generated is fine,
      but verify it looks right).
- [ ] Role-based access enforced and tested (not just implemented).
- [ ] Structured logs emitted for key events (job created, state transition, worker
      heartbeat miss) with a correlation/job ID.
- [ ] No secrets committed; config externalized.
- [ ] Manual test steps in the phase prompt have been executed and pass.

---

## 11. Git / Commit Conventions

- Conventional commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`.
- One phase (from the implementation plan) = one or more focused commits, not one
  giant commit for the whole phase.
- Do not commit generated files (`target/`, `node_modules/`, `.env`) — ensure
  `.gitignore` is correct from Phase 0 onward.
