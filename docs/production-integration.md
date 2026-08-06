# Production Business Integration

FaultPilot supports a `PRODUCTION_READ_ONLY` integration mode for connecting to an existing Spring Boot business service without adding recovery endpoints to that service.

## Runtime flow

```text
Business service
  -> Spring Boot Actuator + Micrometer
  -> /actuator/prometheus
  -> Prometheus scrape target
  -> FaultPilot Prometheus and Actuator read-only tools
  -> Qwen Supervisor and specialist Agents
  -> Evidence-backed Diagnosis
```

The model never receives a free-form PromQL or URL execution tool. FaultPilot builds metric queries from the configured Service Catalog labels. Production mode does not register the lab diagnostic endpoints or lab recovery actions.

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
3. Inspect the incident traces and confirm tool sources start with `prometheus:` or `actuator:`.
4. Confirm evidence such as `PROCESS_CPU_HIGH`, `PROCESS_CPU_NORMAL`, or `DATA_UNAVAILABLE` is stored.
5. Confirm the incident ends at `DIAGNOSED` and `GET /api/pending-actions/by-incident/{id}` returns an empty list.
6. Stop Prometheus or point `PROMETHEUS_URL` at an unavailable address and confirm the tools return `DATA_UNAVAILABLE`; no model-only diagnosis should be accepted.

This mode is intentionally diagnostic-only. A future production remediation integration should use separately authenticated, allowlisted runbook handlers and remain behind the existing human confirmation workflow.
