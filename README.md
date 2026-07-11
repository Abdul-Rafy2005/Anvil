# Anvil

A distributed job processing and workflow orchestration platform.

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+ and npm
- Docker and Docker Compose

## Quick Start

### 1. Start infrastructure

```bash
docker-compose up -d
```

This starts PostgreSQL 16 and Redis with health checks. Verify with:

```bash
docker-compose ps
```

### 2. Run the backend

```bash
cd backend
./mvnw spring-boot:run
```

The API starts on http://localhost:8080. Health check: http://localhost:8080/actuator/health

### 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on http://localhost:5173.

## Project Structure

```
anvil/
├── backend/          # Spring Boot 3 (Java 21, Maven)
│   └── src/main/java/com/anvil/
│       ├── api/          # REST controllers, DTOs, exception handlers
│       ├── auth/         # Spring Security, JWT, filters
│       ├── job/          # Job domain, handlers, services, repositories
│       ├── queue/        # Queue abstraction + Redis implementation
│       ├── worker/       # Worker pool, heartbeat, claim/execute loop
│       ├── scheduler/    # Cron/delayed job scanner
│       ├── notification/ # WebSocket push, email, notification persistence
│       ├── admin/        # Admin-only services
│       ├── audit/        # Audit log service
│       └── config/       # Spring configuration classes
├── frontend/         # React 18 + TypeScript + Vite
├── docker-compose.yml
└── docs/
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/anvil` | PostgreSQL connection URL |
| `DB_USERNAME` | `anvil` | Database username |
| `DB_PASSWORD` | `anvil` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `SERVER_PORT` | `8080` | Backend server port |
