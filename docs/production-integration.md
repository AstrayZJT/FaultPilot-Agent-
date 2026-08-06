# Production Business Integration

FaultPilot supports a `PRODUCTION_READ_ONLY` integration mode for connecting to an existing Spring Boot business service without adding recovery endpoints to that service.

## Runtime flow

```text
Business service
  -> Spring Boot Actuator + Micrometer
  -> /actuator/prometheus
  -> Prometheus scrape target
  -> optional authenticated Arthas HTTP API
  -> FaultPilot Prometheus, Actuator, and Arthas read-only tools
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
4. Confirm evidence such as `PROCESS_CPU_HIGH`, `PROCESS_CPU_NORMAL`, or `DATA_UNAVAILABLE` is stored.
5. Confirm the incident ends at `DIAGNOSED` and `GET /api/pending-actions/by-incident/{id}` returns an empty list.
6. Stop Prometheus or point `PROMETHEUS_URL` at an unavailable address and confirm the tools return `DATA_UNAVAILABLE`; no model-only diagnosis should be accepted.

This mode is intentionally diagnostic-only. A future production remediation integration should use separately authenticated, allowlisted runbook handlers and remain behind the existing human confirmation workflow.
