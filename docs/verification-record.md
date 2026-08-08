# FaultPilot Verification Record

Last updated: 2026-08-08

## Thread Pool Exhaustion End-to-End Test

Status: PASSED

Runtime path:

- FaultPilot server: `http://localhost:8080` in `PRODUCTION_READ_ONLY` mode.
- Order service: `http://localhost:8081`.
- Prometheus: `http://localhost:9090`.
- Model-backed supervisor and specialist agent: Qwen OpenAI-compatible configuration.

Fault injection:

- Scenario: `THREAD_POOL_EXHAUSTED`.
- Scenario run: `0f4b621c-8dad-45e9-9df2-cba8860c7bae`.
- Observed order-service diagnostics: `blockedActive=4`, `blockedQueue=20`.

Observability result:

- Prometheus exposed `executor_active_threads{name="labBlockedExecutor"}=4`.
- Prometheus exposed `executor_pool_size_threads{name="labBlockedExecutor"}=4`.
- Prometheus exposed `executor_queued_tasks{name="labBlockedExecutor"}=20`.

FaultPilot result:

- Incident: `225b09f5-e3c4-4371-afea-7f6592a9b0fe`.
- Status: `DIAGNOSED`.
- Primary cause: `JVM_THREAD_POOL_EXHAUSTED`.
- Supporting evidence: `THREAD_POOL_ACTIVE_AT_MAX`.
- The supervisor selected `JVM_AGENT`; the specialist completed successfully.
- `ACTION_SKIPPED` was emitted because production read-only mode intentionally disables remediation.

## Defect Found and Fixed During Verification

The order lab created its blocked worker pool directly, so its metrics were not registered with Micrometer. Production read-only diagnosis queried Prometheus `executor_*` metrics and therefore observed only unrelated Spring executors.

The order lab now:

- Registers `labBlockedExecutor` with Micrometer `ExecutorServiceMetrics`.
- Uses a queue capacity that tolerates a recovery-to-reinjection handoff.
- Marks a scenario run `FAILED` and releases partial resources if activation throws.
- Recovers persisted order-service active scenarios on process startup because in-memory faults cannot survive a restart.

## Arthas Thread and Source-Line Tool Verification

Status: PASSED (automated tests and live authenticated connector verification).

The read-only `query_arthas_waiting_threads` tool now:

- Reads its endpoint, credentials, and application package prefixes only from the server-side Service Catalog.
- Ignores model-supplied command arguments and sends only `thread --state WAITING -n 50`.
- Bounds the HTTP response to 256 KiB and returns at most eight curated thread summaries.
- Filters out JVM/framework-only stacks before producing `BLOCKING_TASK_FOUND`.
- Preserves the application method and source location in the Evidence summary, for example `FaultScenarioManager.java:207`.

Automated coverage is in `ArthasClientTest` and `ProductionDiagnosticToolsConfigurationTest`: fixed-command enforcement, Basic Auth construction, configuration fail-closed behavior, stack-frame filtering, and Evidence mapping all pass.

Live authenticated connector check:

- Arthas 4.3.2 was attached to the Order JVM on loopback with HTTP Basic Auth and mutation/instrumentation commands disabled.
- Scenario run: `90510682-02ee-4dd0-9859-8267fdce4fd9`; observed `blockedActive=4`, `blockedQueue=20`.
- Incident: `7f97bab5-f4e4-4beb-ac51-b5497e01ac98`.
- Status: `DIAGNOSED`; primary cause: `JVM_THREAD_POOL_EXHAUSTED`.
- `query_arthas_waiting_threads` completed with `SUCCEEDED` and produced `BLOCKING_TASK_FOUND` for four application threads; the first location was `FaultScenarioManager.java:207`.
- Production read-only mode created no remediation action.

Live production-read-only safety check:

- Incident: `07123af1-423e-4338-9ca4-3fcc0146701c`.
- The incident completed `DIAGNOSED` with `JVM_THREAD_POOL_EXHAUSTED` while Arthas was intentionally not configured.
- The persisted tool trace contains `query_arthas_waiting_threads` with status `SUCCEEDED` and summary `Arthas thread inspection is not configured for this service`; no false source evidence was created.

## Production Read-Only Adapter Coverage

Status: PASSED (automated tests; live backend credentials and endpoints are deployment-specific)

The server now has bounded, server-configured read-only adapters for the remaining production evidence sources:

- PostgreSQL: fixed `pg_stat_statements` and `pg_stat_activity` queries return only statement fingerprints, durations, and curated connection groups. The dedicated role is constrained by `pg_read_all_stats`, `default_transaction_read_only`, and a bounded statement timeout.
- Jaeger Query: fixed service-scoped trace requests return only same-service PostgreSQL, configured downstream, or Redis span durations. Trace IDs, operation names, attributes, tags, and payloads are discarded.
- Redis: fixed INFO sections include keyspace hit/miss counters; `inspect_redis_cache_hit_rate` produces `REDIS_CACHE_HIT_RATE_LOW` without reading keys or values.

Automated coverage includes URL and credential validation, fixed-query enforcement, response bounds, authentication construction, source filtering, raw-data omission, and Evidence mapping. The production integration guide contains the required database role, Redis ACL, and Jaeger Query configuration.

## CPU Hotspot Agentic End-to-End Test

Status: PASSED

Runtime and fault signal:

- Scenario: `CPU_HOTSPOT`; scenario run: `b86df16f-bea8-4d64-bf1d-a7ca5a999cf9`.
- Prometheus observed `process_cpu_usage=0.097677253836796957`, above the configured production-read-only threshold.
- Arthas was attached on loopback with the restricted command set and produced `CPU_HOT_METHOD_FOUND`.
- The first application source location was `FaultScenarioManager.lambda$startCpuHotspot$8(FaultScenarioManager.java:257)`.

Agentic result:

- Incident: `a10a077e-6366-4adc-964a-56acbf512159`.
- Status: `DIAGNOSED`; final gate status: `CONFIRMED`; primary cause: `JVM_CPU_HOTSPOT`.
- The remote Qwen Supervisor selected `JVM_AGENT`; the specialist collected Prometheus and Arthas evidence.
- The remote Qwen Diagnosis Agent persisted a `READY_FOR_REVIEW` proposal citing both evidence records.
- The independent remote Qwen Critic returned `PASS`.
- EvidenceGate confirmed the cause from `PROCESS_CPU_HIGH` plus `CPU_HOT_METHOD_FOUND`; production read-only mode performed no remediation.

Defects fixed during this verification:

- Specialist, Diagnosis, and Critic parsers now normalize known Qwen enum aliases while dropping malformed or cross-incident evidence IDs.
- Missing optional arrays and common field aliases no longer invalidate an otherwise safe structured response.
- Diagnosis proposal, critique, and gate repositories now cast serialized documents to PostgreSQL `jsonb` explicitly.
- `mvn clean verify` passes with 42 server tests and the complete five-module reactor build.

## Pending Verification

- Slow SQL diagnosis and remediation confirmation in LAB mode.
- Database connection-pool exhaustion diagnosis and remediation confirmation in LAB mode.
- Downstream dependency timeout diagnosis and remediation confirmation in LAB mode.
