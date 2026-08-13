# FaultPilot JVM Agent

`faultpilot-agent-jvm` is an independently deployable specialist service. The central FaultPilot server discovers it
through an Agent Card, delegates investigations over the A2A task API, and accepts only Evidence IDs in the terminal
artifact. Evidence content remains owned by the central PostgreSQL database.

## Runtime flow

1. `GET /.well-known/agent-card.json` publishes identity, A2A URL, and capability version.
2. `POST /a2a/tasks` accepts one idempotent JVM investigation delegation.
3. The internal Agent loop selects one startup-loaded Skill and one allowlisted Tool per step.
4. YAML Tool definitions build bounded read-only HTTP requests to Prometheus or Arthas.
5. Tool observations are written to the central `/api/internal/evidence` endpoint.
6. `GET /a2a/tasks/{remoteTaskId}` returns progress and a terminal Evidence reference artifact.

Task state, selected Skill, step budget, deterministic Tool call IDs, and Evidence IDs are persisted. On restart, the
Agent resumes active tasks without selecting the Skill again or resetting its budget. A reserved Tool call can be
replayed safely because the central service treats `(runId, toolCallId)` as idempotent.

## Required environment

| Variable | Purpose |
| --- | --- |
| `MODEL_BASE_URL` | Alibaba Bailian OpenAI-compatible base URL |
| `MODEL_NAME` | Model name, default `qwen3.7-max` |
| `QWEN_API_KEY` | Model API key; never store it in Git |
| `JVM_AGENT_A2A_TOKEN` | Bearer token used by the central orchestrator |
| `FAULTPILOT_AGENT_API_TOKEN` | Bearer token used to write/query central Evidence |
| `FAULTPILOT_INTERNAL_URL` | Central FaultPilot base URL |
| `PROMETHEUS_URL` | Prometheus base URL |
| `JVM_AGENT_CAPABILITY_VERSION` | Version published by the Agent Card |

For source-level diagnosis, also configure `ORDER_SERVICE_ARTHAS_URL`, Arthas username/password, and
`ORDER_SERVICE_CODE_PACKAGE_PREFIX`. The same fields exist for `inventory-service`.

The default task database is a local H2 file under `./data`. A dedicated PostgreSQL database can be supplied through
`JVM_AGENT_DB_URL`, `JVM_AGENT_DB_USERNAME`, and `JVM_AGENT_DB_PASSWORD`.

## Start locally

Start the JVM Agent before starting the central server in A2A mode:

```powershell
$env:JVM_AGENT_A2A_TOKEN = "<shared-a2a-token>"
$env:FAULTPILOT_AGENT_API_TOKEN = "<shared-internal-api-token>"
$env:FAULTPILOT_INTERNAL_URL = "http://localhost:8080"
$env:JVM_AGENT_PUBLIC_URL = "http://localhost:8091/a2a"
$env:PROMETHEUS_URL = "http://localhost:9090"
mvn -pl faultpilot-agent-jvm spring-boot:run
```

Then start the central server with:

```powershell
$env:FAULTPILOT_SPECIALIST_TRANSPORT = "A2A"
$env:JVM_AGENT_CARD_URL = "http://localhost:8091/.well-known/agent-card.json"
$env:JVM_AGENT_A2A_TOKEN = "<shared-a2a-token>"
$env:FAULTPILOT_AGENT_API_TOKEN = "<shared-internal-api-token>"
mvn -pl faultpilot-server spring-boot:run
```

The Agent Card is public for startup discovery. All `/a2a/**` endpoints require the A2A bearer token, and all central
Evidence endpoints require the internal API bearer token.

## Start with Compose

The optional Compose profile builds and runs the JVM Agent in addition to infrastructure:

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml --profile distributed-agents up -d
```

With the central server running on the Windows host, set `FAULTPILOT_INTERNAL_URL` to
`http://host.docker.internal:8080` for the container. Configure Arthas URLs from the container's network perspective.

## Add a JVM diagnostic capability

- Add a Tool YAML under `src/main/resources/diagnostic/tools` when the response fits an existing safe parser.
- Add a Skill directory containing `skill.yaml` and `SKILL.md` under `diagnostic/skills`.
- Reference only allowlisted read-only Tools and define a deterministic local Evidence completion rule.
- Increment `JVM_AGENT_CAPABILITY_VERSION`, test, and restart the Agent and central server.

Prometheus Tools are declarative threshold queries. Arthas Tools are intentionally restricted to the two fixed
read-only commands validated at startup; arbitrary commands cannot be introduced through YAML.
