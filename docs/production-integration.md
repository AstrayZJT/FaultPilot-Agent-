# Production Business Integration

FaultPilot supports a `PRODUCTION_READ_ONLY` integration mode for connecting to an existing Spring Boot business service without adding recovery endpoints to that service.

## Runtime flow

```text
Business service
  -> Spring Boot Actuator + Micrometer
  -> /actuator/prometheus
  -> Prometheus scrape target
  -> optional authenticated Arthas HTTP API
  -> optional PostgreSQL statistics role
  -> optional Jaeger Query backend
  -> FaultPilot Prometheus, Actuator, Arthas, PostgreSQL, and trace read-only tools
  -> Qwen Supervisor and specialist Agents
  -> Evidence-backed Diagnosis
```

The model never receives a free-form PromQL or URL execution tool. FaultPilot builds metric queries from the configured Service Catalog labels. Production mode does not register the lab diagnostic endpoints or lab recovery actions.

## Optional Arthas thread and source-line inspection

Arthas is an optional JVM-level diagnostic source. It is attached to the already running Java process; the business service does not need a code change or a new recovery endpoint. FaultPilot sends only the fixed read-only command `thread --state WAITING -n 50` to the configured Arthas HTTP API. The bounded `-n` form is used because Arthas `--all` returns thread statistics without stack frames. The server filters the response to configured application package prefixes and stores at most eight thread summaries. A successful match is recorded as `BLOCKING_TASK_FOUND` with the thread name, application method, source file and line number, and the observed blocking operation.

Configure the endpoint and package prefix before starting FaultPilot. Keep the password in the process environment and do not put it in this file or in Git:

```
$env:ORDER_SERVICE_ARTHAS_URL = "http://127.0.0.1:8563"
$env:ORDER_SERVICE_ARTHAS_USERNAME = "faultpilot"
$env:ORDER_SERVICE_ARTHAS_PASSWORD = "<arthas-password>"
$env:ORDER_SERVICE_CODE_PACKAGE_PREFIX = "com.astrayzjt.faultpilot.lab.order"
```

Start Arthas against the target process with HTTP authentication and disable commands that can mutate or instrument the target. The target PID below is the process listening on port `8081`:

```
$pid = (Get-NetTCPConnection -LocalPort 8081 -State Listen).OwningProcess | Select-Object -First 1
java -jar "$env:TEMP\arthas-boot.jar" `
  --target-ip 127.0.0.1 `
  --http-port 8563 `
  --telnet-port 3658 `
  --username $env:ORDER_SERVICE_ARTHAS_USERNAME `
  --password $env:ORDER_SERVICE_ARTHAS_PASSWORD `
  --disabled-commands "stop,shutdown,reset,ognl,vmtool,sc,sm,watch,trace,tt,monitor,jad,mc,redefine,retransform" `
  $pid
```

After the Arthas HTTP port is listening, start FaultPilot in `PRODUCTION_READ_ONLY` mode and run the `THREAD_POOL_EXHAUSTED` scenario. The console should show `THREAD_POOL_ACTIVE_AT_MAX` plus an Arthas evidence item whose summary contains a value like `FaultScenarioManager.java:207`. A missing Arthas configuration produces no source evidence; an unreachable or unauthenticated endpoint produces `DATA_UNAVAILABLE`. Both cases prevent a model-only source claim.

Stop the temporary Arthas instance after verification and never expose its HTTP or telnet ports beyond the local diagnostic network. Arthas remains read-only in FaultPilot; remediation still requires the existing human-confirmation workflow and is disabled in production-read-only mode.

## PostgreSQL statistics diagnostics

FaultPilot can inspect PostgreSQL execution statistics and active connection groups through a separate diagnostic account. It does not connect through the business application's datasource and it never accepts SQL text, parameters, database names, or connection URLs from the model.

The two fixed probes are:

- `inspect_postgres_slow_statements`: reads `pg_stat_statements` and returns only a normalized statement fingerprint, call count, mean duration, and maximum duration. It never selects the SQL text.
- `inspect_postgres_connection_holders`: reads grouped `pg_stat_activity` state and wait-event metadata. It never selects a query, bind value, PID, database user, or application name.

Create a dedicated role with no write grants. The database administrator should adapt these statements to the organization's role policy and store the password in a secret manager rather than in a shell history or repository:

```sql
CREATE ROLE faultpilot_diagnostic LOGIN PASSWORD '<secret-from-secret-manager>';
GRANT CONNECT ON DATABASE orders TO faultpilot_diagnostic;
GRANT pg_read_all_stats TO faultpilot_diagnostic;
ALTER ROLE faultpilot_diagnostic SET default_transaction_read_only = on;
ALTER ROLE faultpilot_diagnostic SET statement_timeout = '3s';
```

`pg_stat_statements` must be enabled by the database administrator. PostgreSQL normally requires `shared_preload_libraries = 'pg_stat_statements'`, a restart, and `CREATE EXTENSION pg_stat_statements` in each monitored database. Keep the role free of table `SELECT`, DDL, replication, and superuser privileges; the fixed probes do not need them.

Add the PostgreSQL target to FaultPilot's administrator-owned catalog. The JDBC URL must be credential-free, query timeout is restricted to 1-10 seconds, and result count is restricted to 1-50 rows:

```yaml
faultpilot:
  catalog:
    services:
      order-service:
        database-ref: orders-postgres
  database:
    instances:
      orders-postgres:
        jdbc-url: jdbc:postgresql://postgres.internal:5432/orders
        username: faultpilot_diagnostic
        password: ${ORDERS_DIAGNOSTIC_DATABASE_PASSWORD}
        query-timeout-seconds: 3
        max-rows: 20
```

When the target is unavailable, missing `pg_stat_statements`, or lacks the statistics grant, FaultPilot records `DATA_UNAVAILABLE`. It does not convert missing database evidence into a model-only diagnosis. A slow statement fingerprint becomes `SLOW_SQL_FOUND`; a long non-idle connection group becomes `CONNECTION_HOLDING_QUERY_FOUND`. Either result still requires the EvidenceGate's independent latency or pool-pressure corroboration before a cause can be confirmed.

## Jaeger trace correlation

FaultPilot supports a Jaeger Query backend for independent timing corroboration. Business services should export OpenTelemetry-compatible traces to the organization's trace collector, and the Query endpoint should be protected by a service-scoped read-only identity or a reverse proxy. FaultPilot sends a fixed `GET /api/traces` request with only the configured service name, bounded trace count, and bounded lookback window.

The model cannot provide an endpoint, query string, trace ID, span ID, service name, or tag selector. FaultPilot discards operation names, request paths, attributes, tag values, trace IDs, span IDs, and payloads before persisting evidence. It retains only a bounded summary: category, configured related service, and duration.

Configure the Jaeger backend and logical-to-trace service name mapping in server-side configuration. Keep any token or basic-auth password in a secret manager:

```yaml
faultpilot:
  catalog:
    services:
      order-service:
        trace-ref: jaeger-primary
        trace-service-name: orders-api
        downstreams:
          - inventory-service
      inventory-service:
        trace-service-name: inventory-api
  trace:
    jaeger:
      jaeger-primary:
        base-url: https://jaeger-query.internal
        bearer-token: ${JAEGER_QUERY_BEARER_TOKEN}
        lookback-minutes: 15
        max-traces: 10
```

`base-url` is restricted to a credential-free HTTP(S) URL. Choose either bearer authentication or basic authentication, never both. The trace window is restricted to 1-60 minutes and the response is restricted to 1-20 traces and 512 KiB.

Three fixed trace probes are available:

- `inspect_trace_slow_database_spans`: only recognizes same-service spans tagged with `db.system=postgresql` and yields `API_AND_SQL_TIME_CORRELATED` when the configured duration threshold is exceeded.
- `inspect_trace_slow_dependency_spans`: only recognizes same-service client spans whose configured peer is a catalogued downstream and yields `SLOW_CHILD_SPAN_FOUND`.
- `inspect_trace_redis_spans`: only recognizes same-service spans tagged with `db.system=redis` and yields `REDIS_TRACE_LATENCY_CORRELATED`.

The first two classes of evidence are deliberately corroborating evidence: a database span alone cannot prove a specific SQL statement, and a slow child span alone cannot prove a downstream outage. EvidenceGate requires them alongside the corresponding PostgreSQL or Prometheus signal. Jaeger is currently the supported query adapter; other backends should be connected through a controlled Jaeger Query-compatible gateway rather than exposing a free-form trace API to the model.

## Business service requirements

The target Spring Boot service should include:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Expose only the observability endpoints needed by the deployment:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

Protect Actuator and Prometheus with the service's normal network policy and authentication in a real deployment. The local lab exposes them on localhost for demonstration.

## FaultPilot configuration

Configure one Service Catalog entry per business service:

```yaml
faultpilot:
  integration:
    mode: PRODUCTION_READ_ONLY
  observability:
    prometheus-url: http://prometheus:9090
  remediation:
    enabled: false
  catalog:
    services:
      order-service:
        actuator-base-url: http://order-service:8080
        prometheus-labels:
          job: order-service
        arthas-base-url: http://127.0.0.1:8563
        arthas-username: faultpilot
        arthas-password: ${ORDER_SERVICE_ARTHAS_PASSWORD}
        code-package-prefixes:
          - com.example.orders
        downstreams:
          - inventory-service
        allowed-actions: []
```

`prometheus-labels` must match labels assigned by Prometheus. The labels are server-side configuration and are never taken from model output.

For the included local business sample, run the existing order service and Prometheus, then start FaultPilot with:

```powershell
$env:FAULTPILOT_INTEGRATION_MODE = "PRODUCTION_READ_ONLY"
$env:FAULTPILOT_REMEDIATION_ENABLED = "false"
$env:ORDER_SERVICE_URL = "http://localhost:8081"
$env:PROMETHEUS_URL = "http://localhost:9090"
mvn -pl faultpilot-server spring-boot:run
```

Create an incident with `serviceName` set to `order-service` and a symptom such as `order API CPU is high`. `allowRemediation` may be omitted or set to `true`; production mode still remains read-only and records `ACTION_SKIPPED` instead of creating a Pending Action.

## Verification checklist

1. Query `http://localhost:9090/api/v1/query?query=process_cpu_usage{job="faultpilot-lab-order"}` and confirm a result.
2. Create an incident through the console or `POST /api/incidents`.
3. Inspect the incident traces and confirm tool sources start with `prometheus:`, `actuator:`, or `arthas:`.
4. With a PostgreSQL target configured, inspect tool sources beginning with `postgres:` and confirm that diagnostic summaries expose only statement fingerprints or curated connection-group metadata.
5. With a Jaeger backend configured, inspect tool sources beginning with `jaeger:` and confirm that summaries expose only category, configured related service, and duration.
6. Confirm evidence such as `PROCESS_CPU_HIGH`, `PROCESS_CPU_NORMAL`, `SLOW_SQL_FOUND`, `CONNECTION_HOLDING_QUERY_FOUND`, `SLOW_CHILD_SPAN_FOUND`, or `DATA_UNAVAILABLE` is stored.
7. Confirm the incident ends at `DIAGNOSED` only when the EvidenceGate has the required independent evidence, and that `GET /api/pending-actions/by-incident/{id}` returns an empty list.
8. Stop Prometheus or point `PROMETHEUS_URL` at an unavailable address and confirm the tools return `DATA_UNAVAILABLE`; no model-only diagnosis should be accepted.

This mode is intentionally diagnostic-only. A future production remediation integration should use separately authenticated, allowlisted runbook handlers and remain behind the existing human confirmation workflow.
