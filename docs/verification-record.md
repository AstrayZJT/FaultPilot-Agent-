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

## Pending Verification

- CPU hotspot diagnosis in production read-only mode.
- Slow SQL diagnosis and remediation confirmation in LAB mode.
- Database connection-pool exhaustion diagnosis and remediation confirmation in LAB mode.
- Downstream dependency timeout diagnosis and remediation confirmation in LAB mode.
