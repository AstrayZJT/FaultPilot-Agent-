# FaultPilot Verification Record

Last updated: 2026-08-06

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

Status: AUTOMATED TESTS PASSED; live connector verification requires an authenticated Arthas instance on the target JVM.

The read-only `query_arthas_waiting_threads` tool now:

- Reads its endpoint, credentials, and application package prefixes only from the server-side Service Catalog.
- Ignores model-supplied command arguments and sends only `thread --state WAITING --all`.
- Bounds the HTTP response to 256 KiB and returns at most eight curated thread summaries.
- Filters out JVM/framework-only stacks before producing `BLOCKING_TASK_FOUND`.
- Preserves the application method and source location in the Evidence summary, for example `FaultScenarioManager.java:207`.

Automated coverage is in `ArthasClientTest` and `ProductionDiagnosticToolsConfigurationTest`: fixed-command enforcement, Basic Auth construction, configuration fail-closed behavior, stack-frame filtering, and Evidence mapping all pass.

Live production-read-only safety check:

- Incident: `07123af1-423e-4338-9ca4-3fcc0146701c`.
- The incident completed `DIAGNOSED` with `JVM_THREAD_POOL_EXHAUSTED` while Arthas was intentionally not configured.
- The persisted tool trace contains `query_arthas_waiting_threads` with status `SUCCEEDED` and summary `Arthas thread inspection is not configured for this service`; no false source evidence was created.

## Pending Verification

- CPU hotspot diagnosis in production read-only mode.
- Slow SQL diagnosis and remediation confirmation in LAB mode.
- Database connection-pool exhaustion diagnosis and remediation confirmation in LAB mode.
- Downstream dependency timeout diagnosis and remediation confirmation in LAB mode.
