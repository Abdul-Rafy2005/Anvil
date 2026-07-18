<div align="center">
  <h1>🔨 Anvil</h1>
  <p><strong>A Distributed Job Processing & Workflow Orchestration Platform</strong></p>
  
  <p>
    <a href="#features"><img src="https://img.shields.io/badge/Status-Production%20Ready-success?style=flat-square" alt="Status" /></a>
    <a href="#tech-stack"><img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=java" alt="Java 21" /></a>
    <a href="#tech-stack"><img src="https://img.shields.io/badge/Spring_Boot-3.5-brightgreen?style=flat-square&logo=spring-boot" alt="Spring Boot" /></a>
    <a href="#tech-stack"><img src="https://img.shields.io/badge/React-19-blue?style=flat-square&logo=react" alt="React" /></a>
    <a href="#testing"><img src="https://img.shields.io/badge/Tests-180%2B%20Passing-success?style=flat-square" alt="Tests" /></a>
    <a href="#observability--resiliency"><img src="https://img.shields.io/badge/Coverage-Integration%20%7C%20Unit-blueviolet?style=flat-square" alt="Coverage" /></a>
  </p>

  <p>
    <a href="#engineering-highlights">Engineering Highlights</a> •
    <a href="#architecture">Architecture</a> •
    <a href="#getting-started">Getting Started</a>
  </p>
</div>

---

## 📖 Overview

**Submit a job. Don't block the request. Get results when they're ready.**

Anvil is a horizontally scalable, distributed job processing and orchestration platform built for high-throughput, fault-tolerant asynchronous work. Designed to offload heavy computations from client-facing applications, Anvil ensures jobs are executed reliably, retried intelligently, and monitored comprehensively.

Whether it's report generation, AI content creation, bulk email campaigns, or CSV imports, Anvil acts as the resilient backbone that handles the complexity of distributed execution, state management, and real-time client updates.

## ✨ Key Features

- **Asynchronous Processing:** Submit jobs via a REST API and receive a tracking ID immediately.
- **Priority Queues:** Redis-backed queues supporting HIGH, MEDIUM, and LOW priority execution.
- **Real-Time Updates:** WebSocket (STOMP/SockJS) integration pushes live progress and status changes directly to the client.
- **Robust Error Handling:** Configurable automatic retries with exponential backoff and a robust Dead Letter Queue (DLQ) for permanent failures.
- **Comprehensive Admin Console:** Dashboard for real-time statistics, worker node management, and DLQ inspection.
- **Role-Based Access Control:** Secure JWT authentication separating User workspaces from Admin tooling.

---

## 🛠️ Engineering Highlights

Anvil is built to demonstrate production-ready engineering patterns to solve distributed system challenges:

1. **Transactional Outbox Pattern:** Ensures dual-write consistency. Database writes and queue enqueues occur atomically—preventing ghost jobs or data loss if a process crashes mid-transaction.
2. **Resilience & Chaos Tested:** Proven against rigorous chaos tests (20 kill/restart cycles). Orphan jobs are successfully reclaimed without data loss.
3. **Observability-First Design:** Implements structured JSON logging with correlation IDs for request tracing, combined with Prometheus metrics for throughput, latency, and worker health monitoring.
4. **State Machine Job Lifecycle:** Strict state transitions (`CREATED` → `QUEUED` → `RUNNING` → `COMPLETED` / `FAILED`) guarantee deterministic job handling.
5. **Clean Architecture:** Strict separation of concerns (Controllers, Services, Repositories). Handlers strictly implement a `JobHandler` interface, ensuring adding new job types never touches core orchestration logic.

---

## 💻 Tech Stack

| Category | Technologies |
|:---|:---|
| **Backend Core** | Java 21, Spring Boot 3.5, Spring Security, Maven |
| **Data Layer** | PostgreSQL 16, Flyway (Migrations) |
| **Caching & Queues** | Redis 7 |
| **Real-time Comms** | Spring WebSocket (STOMP + SockJS) |
| **Frontend** | React 19, TypeScript, Vite, Tailwind CSS |
| **Observability** | Micrometer, Prometheus, Logstash Encoder |
| **Testing** | JUnit 5, Mockito, Testcontainers, Vitest, React Testing Library |
| **Infrastructure** | Docker, Docker Compose |

---

## 🏗️ Architecture

The system splits cleanly into a **write path** (submit → queue → execute → persist) and a **read path** (dashboard queries + live push), avoiding read-write contention.

### High-Level Topology

```text
┌───────────────────────┐
│      Client App       │
└──────────┬────────────┘
           │ POST /api/v1/jobs
           ▼
┌───────────────────────────────────────────┐
│         Spring Boot REST API              │
└──────────┬────────────────────────────────┘
           │ BEGIN TX: INSERT job + INSERT outbox row
           ▼
┌───────────────────────────────────────────┐
│               PostgreSQL 16               │
└──────────┬────────────────────────────────┘
           │ OutboxRelay → ZADD
           ▼
┌───────────────────────────────────────────┐
│      Redis 7 — Priority Queue             │
└──────────┬────────────────────────────────┘
           │ poll() → claim
           ▼
┌───────────────────────────────────────────┐
│               Worker Pool                 │
│   claim → JobHandler.execute() → ack/nack │
└───┬───────────────────┬───────────────────┘
    │ persist result    │ push WS progress
    ▼                   ▼
┌────────────┐   ┌───────────────────┐  
│ PostgreSQL │   │ WebSocket Service │  
└────────────┘   └─────────┬─────────┘  
                           │
                 ┌─────────┴─────────┐
                 │ React Dashboard   │
                 └───────────────────┘
```

### Job State Machine

```text
CREATED ──(enqueue)──▶ QUEUED ──(claim)──▶ RUNNING ──(success)──▶ COMPLETED
                          ▲                   │
                          │                   ├─────(failure)───▶ FAILED ──(retries left)──▶ RETRYING
                          │                                         │
                          └────────(backoff expires)────────────────┘
                                                                    │
                                                            (no retries left)
                                                                    ▼
                                                            FAILED_PERMANENTLY (DLQ)
```

---

## 📊 Observability & Resiliency

Anvil isn't just built to run; it's built to fail safely and recover gracefully.

- **Crash Recovery:** Outbox durability ensures jobs survive DB/Queue dual-write failures.
- **Orphan Job Reclamation:** Visibility timeouts and worker heartbeats allow the system to detect crashed workers and automatically re-queue stalled jobs.
- **Load Testing Results:** Sustains 3857 jobs/min API submission throughput with p95=17ms and p99=34ms latency.
- **Health Probes:** Kubernetes-ready `/actuator/health/liveness` and `readiness` endpoints.

---

## 🏎️ Getting Started

### Prerequisites
- Java 21+ & Maven 3.9+
- Node.js 18+
- Docker & Docker Compose

### Quick Start (Docker)

Spin up the entire stack—PostgreSQL, Redis, API, and Frontend—with one command:

```bash
docker compose up -d
```
- **Frontend Dashboard:** `http://localhost`
- **Backend API:** `http://localhost:8080/api/v1`
- **Swagger Docs:** `http://localhost:8080/swagger-ui/index.html`
- **Prometheus Metrics:** `http://localhost:8080/actuator/prometheus`

### Local Development Environment

1. **Start Infrastructure (DB & Cache):**
   ```bash
   docker compose up -d postgres redis
   ```
2. **Start Backend API:**
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
3. **Start Frontend Dashboard:**
   ```bash
   cd frontend
   npm install && npm run dev
   ```

---

## 🧪 Testing

Anvil employs a rigorous testing strategy ensuring extreme reliability. 

- **Backend (180+ Tests, 0 Failures):** 
  - *Unit Tests:* Core domain logic, state transitions, retry backoff algorithms.
  - *Integration Tests:* Driven by **Testcontainers** (spinning up real Postgres and Redis nodes), verifying queue operations, worker orchestration, outbox relays, and WebSocket security. No database mocking.
  - *Contract Tests:* Comprehensive endpoint validation.
- **Frontend (31+ Tests, 0 Failures):**
  - Uses Vitest and React Testing Library to cover UI components, form validation, and admin route guards.

Run tests locally:
```bash
# Backend
cd backend && mvn test

# Frontend
cd frontend && npm test
```

---

## 📂 Project Structure

```text
├── backend/                  # Java 21, Spring Boot, REST API, WebSocket
│   ├── src/main/java/.../
│   │   ├── api/              # REST Controllers, DTOs
│   │   ├── job/              # Job Domain, State Machine, Handlers (CSV, Email, AI, etc.)
│   │   ├── queue/            # Redis Queue implementations, Transactional Outbox Relay
│   │   ├── worker/           # Worker Orchestration, Watchdogs
│   │   └── notification/     # WebSocket STOMP messaging
│   └── src/test/             # 180+ Integration & Unit Tests (Testcontainers)
│
├── frontend/                 # React 19, Vite, Tailwind SPA
│   ├── src/
│   │   ├── components/       # Reusable UI elements
│   │   ├── pages/            # Admin Console, User Workspace, Job Detail
│   │   ├── hooks/            # Custom hooks (e.g., useWebSocket)
│   │   └── api/              # API Client with interceptors
│
├── scripts/                  # Load & Chaos testing PowerShell scripts
├── docs/                     # Product specs and technical architecture
└── docker-compose.yml        # Infrastructure orchestration
```

---

## 📝 License

Distributed under the MIT License. See `LICENSE` for more information.

<p align="center">
  Built with ❤️ focusing on clean architecture and robust distributed systems design.
</p>