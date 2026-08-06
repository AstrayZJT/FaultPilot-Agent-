# FaultPilot

FaultPilot is a Java 21 multi-agent incident diagnosis and safe remediation system for microservices. The implementation follows the project design in `docs/FaultPilot-多Agent微服务故障诊断与安全处置系统设计书.md`.

## Current Stage

Stage 0 provides the Maven multi-module skeleton, Spring Boot entrypoints, PostgreSQL/Flyway foundation, Prometheus configuration, TraceId propagation, and a common JSON error response.

Modules:

- `faultpilot-server`: incident orchestration service
- `faultpilot-lab-order`: order-service fault laboratory
- `faultpilot-lab-inventory`: inventory-service fault laboratory
- `faultpilot-evaluation`: fixed evaluation runner

## Prerequisites

- JDK 21 or newer, with `JAVA_HOME` pointing to that JDK
- Maven 3.9 or newer recommended
- Docker Desktop with Compose
- Qwen OpenAI-compatible API access for model-backed stages

The application never reads a local model. Configure the real Qwen credentials through environment variables:

```powershell
$env:QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:QWEN_MODEL = "qwen3.7-plus"
$env:QWEN_API_KEY = "<your-rotated-key>"
```

The key is intentionally absent from the repository. Do not put it in `application.yml`, `.env.example`, logs, or commits.

## Start Infrastructure

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

The database initialization creates separate `faultpilot` and `faultpilot_lab` databases, separate service accounts, and enables `pg_stat_statements` in the lab database.

The example Compose file uses host port `55432` so it does not collide with an existing local PostgreSQL instance on `5432`.

## Build and Test

```powershell
mvn clean verify
```

Run the server locally after infrastructure is available:

```powershell
mvn -pl faultpilot-server spring-boot:run
```

Useful endpoints:

- `http://localhost:8080/actuator/health`
- `http://localhost:8080/api/system`
- `http://localhost:8080/swagger-ui.html`
- `http://localhost:9090`
