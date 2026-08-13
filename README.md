# FaultPilot

FaultPilot is a Java 21 multi-agent incident diagnosis and safe remediation system for microservices.

## Current Stage

The MVP implementation covers the design stages 0-7: reproducible fault labs, read-only diagnostic tools, real Qwen-backed specialist agents, a LangGraph4j PostgreSQL checkpoint graph, evidence-based diagnosis, confirmation-gated lab remediation, evaluation records, reviewed runbook search, SSE replay, and a static operations console.

Modules:

- `faultpilot-server`: LangGraph4j main-Agent loop, delegation, Evidence ownership, and final summaries
- `faultpilot-agent-jvm`: independently deployable A2A JVM specialist with an internal Skill/Tool loop
- `faultpilot-lab-order`: order-service fault laboratory
- `faultpilot-lab-inventory`: inventory-service fault laboratory
- `faultpilot-evaluation`: fixed evaluation runner

## Prerequisites

- JDK 21 or newer, with `JAVA_HOME` pointing to that JDK
- Maven 3.9 or newer recommended
- Docker Desktop with Compose
- Alibaba Bailian OpenAI-compatible API access for model-backed stages

The application never reads a local model. Configure the Alibaba Bailian OpenAI-compatible endpoint and keep using the existing `QWEN_API_KEY` environment variable for the credential:

```powershell
$env:MODEL_BASE_URL = "https://<workspace-id>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
$env:MODEL_NAME = "qwen3.7-max"
$env:QWEN_API_KEY = "<your-rotated-key>"
$env:MODEL_TIMEOUT_SECONDS = "90"
$env:FAULTPILOT_SECURITY_VIEWER_PASSWORD = "<viewer-password>"
$env:FAULTPILOT_SECURITY_OPERATOR_PASSWORD = "<operator-password>"
$env:ALERTMANAGER_WEBHOOK_TOKEN = "<webhook-token>"
$env:FAULTPILOT_AGENT_API_TOKEN = "<shared-internal-api-token>"
$env:JVM_AGENT_A2A_TOKEN = "<shared-a2a-token>"
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

To use the distributed JVM specialist, start `faultpilot-agent-jvm` first and set
`FAULTPILOT_SPECIALIST_TRANSPORT=A2A` for the central server. See
[`faultpilot-agent-jvm/README.md`](faultpilot-agent-jvm/README.md) for the complete start order and environment contract.

The operator console is at `http://localhost:8080/`. It uses form login, Session cookies, and CSRF. API automation can use Basic authentication after obtaining the CSRF token from `/api/security/csrf`. The server never creates an action from model text: remediation is selected by the deterministic Cause Catalog and waits at `WAITING_ACTION_CONFIRMATION`.

Useful endpoints:

- `http://localhost:8080/actuator/health`
- `http://localhost:8080/api/system`
- `http://localhost:8080/swagger-ui.html`
- `http://localhost:9090`

Run the fixed evaluation comparison with `RULE`, `SINGLE_AGENT`, or `MULTI_AGENT`:

```powershell
$headers = @{ Authorization = "Basic <base64(operator:password)>" }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/evaluations -Headers $headers -ContentType application/json -Body '{"mode":"RULE"}'
```

Each completed run reports root-cause accuracy, routing accuracy, required-evidence recall, unsafe-action rate, tool calls, agent steps, and latency.

## Connect a real business service

The included `faultpilot-lab-order` service is also a realistic Spring Boot target: it exposes Actuator/Micrometer metrics, Prometheus scrapes them, and FaultPilot can query those metrics in a production-style read-only mode. See `docs/production-integration.md` for the configuration and verification checklist.

Start that mode with:

```powershell
$env:FAULTPILOT_INTEGRATION_MODE = "PRODUCTION_READ_ONLY"
$env:FAULTPILOT_REMEDIATION_ENABLED = "false"
mvn -pl faultpilot-server spring-boot:run
```

In this mode FaultPilot registers Prometheus, Actuator, and optionally authenticated Arthas read-only tools. Configure the Arthas fields in `docs/production-integration.md` to add thread, method, and source-line evidence. Even an incident request with `allowRemediation=true` cannot create a Pending Action or call the lab `recover-active` endpoint.
