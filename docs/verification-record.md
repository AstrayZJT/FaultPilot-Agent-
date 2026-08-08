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

Latest live source-location verification after the self-reflection fixes:

- FaultPilot was restarted in `PRODUCTION_READ_ONLY` with an authenticated, loopback-only Arthas 4.3.2 connector attached to the order JVM.
- Scenario run: `5f7fee82-33e1-475f-8c63-80eae3185a71`; immediate order-service diagnostics confirmed `threadPoolExhausted=true`, `blockedActive=4`, and `blockedQueue=20` before the Incident was created.
- Incident: `9e2a429b-b944-4e47-bf30-2314cfd8ba39`.
- Evidence included `THREAD_POOL_ACTIVE_AT_MAX` and `BLOCKING_TASK_FOUND`. The latter retained `FaultScenarioManager.lambda$startBlockedTasks$9(FaultScenarioManager.java:276)` and `java.util.concurrent.locks.LockSupport.park`, proving that the source-line connector is active and not merely configured.
- The JVM Agent completed with `JVM_THREAD_POOL_EXHAUSTED` and cited the saturation plus blocking evidence. The remaining final diagnosis was `INCONCLUSIVE` because the remote Qwen Critic call returned HTTP 403 on both attempts; this was a model-provider authorization failure, not missing Arthas evidence.
- The live run therefore passes Arthas collection and JVM finding verification, but requires an authorized Qwen key to complete the final Critic/EvidenceGate `CONFIRMED` path.

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
- `mvn clean verify` passes across the complete five-module reactor build: 46 server tests, 2 order-lab tests, and 1 evaluation test.

## Redis Production Read-Only End-to-End Tests

Status: PASSED

Redis command-path latency:

- Scenario run: `a85dd079-ea60-45c8-98e9-94cc1e7af7c1`; Prometheus observed approximately `0.479s` command latency.
- Incident: `fa0e8949-3a98-44a1-8dbc-7d63ccd20981`; status `DIAGNOSED`.
- The Supervisor selected only `CACHE_AGENT`; its four tool steps completed and cited `REDIS_COMMAND_LATENCY_HIGH`.
- The Diagnosis Agent proposed `REDIS_SERVER_LATENCY`, the Critic returned `PASS`, and EvidenceGate returned `SUPPORTED`.
- Missing corroboration is explicit: `REDIS_TRACE_LATENCY_CORRELATED` or `REDIS_SLOW_COMMAND_FOUND`.

Redis client-pool exhaustion:

- Scenario run: `bff476fc-1e7c-434b-9f81-f04c9c37d97a`; Prometheus observed `active=4`, `max=4`.
- Incident: `c5b77b95-718d-4f9f-a9e3-1ca2338c7874`; status `DIAGNOSED`.
- Direct evidence was `REDIS_CLIENT_POOL_PENDING_HIGH`, while normal command latency isolated the client pool from Redis command processing.
- The Diagnosis Agent proposed `REDIS_CLIENT_POOL_EXHAUSTED`, the Critic returned `PASS`, and EvidenceGate returned `SUPPORTED`.
- The Specialist's final model summary timed out; the conservative fallback retained all Evidence and allowed the remaining model-backed diagnosis pipeline to complete.

## Database Production Read-Only End-to-End Tests

Status: PASSED

Database connection-pool exhaustion:

- Scenario run: `0ff52ebe-8d89-427c-bb03-003425a12d3d`; Prometheus observed Hikari `active=10`, `max=10`.
- Incident: `4c241441-9da4-4732-a923-0381f6649147`; status `DIAGNOSED`.
- The Diagnosis Agent proposed `DB_POOL_EXHAUSTED`, the Critic returned `PASS`, and EvidenceGate returned `SUPPORTED`.
- `CONNECTION_HOLDING_QUERY_FOUND` remains explicitly missing because the lab-held connections had no long-running non-idle transaction to attribute.

Slow SQL:

- Scenario run: `587667a4-aab2-4638-a577-5f275be78755` executed the delay inside PostgreSQL, not as a Java-side sleep.
- PostgreSQL recorded a statement maximum of approximately `2003ms`; FaultPilot returned only its fingerprint, call count, and bounded durations.
- Incident: `b9f42346-2017-4b43-ab33-4b9c28defb3c`; status `DIAGNOSED`.
- `DATABASE_AGENT` produced `SLOW_SQL_FOUND`; the Diagnosis Agent proposed `DB_SLOW_QUERY`, the Critic returned `PASS`, and EvidenceGate returned `SUPPORTED`.
- Trace correlation or an abnormal execution plan remains required for `CONFIRMED`.

## Downstream Production Read-Only End-to-End Test

Status: PASSED

- Scenario run: `3d1d33cc-00ba-46e7-9561-7dbb29b631e9`; inventory-service latency was approximately `1.552s`.
- Incident: `284c2c65-e90e-42dd-ba59-01a9cb9ce489`; status `DIAGNOSED`.
- The Supervisor selected only `DEPENDENCY_AGENT`, which completed three steps and cited `DOWNSTREAM_LATENCY_HIGH`.
- The Diagnosis Agent proposed `DEPENDENCY_TIMEOUT`, the Critic returned `PASS`, and EvidenceGate returned `SUPPORTED`.
- `SLOW_CHILD_SPAN_FOUND` remains missing because no Jaeger backend was configured; no trace fact was invented.

## Defects Fixed During Multi-Scenario Verification

- The Specialist deadline is configurable and bounded instead of being fixed at 30 seconds.
- LangChain4j's hidden retry loop is disabled; FaultPilot owns and persists at most two model attempts, preventing one call from silently consuming roughly 270 seconds.
- A failed Specialist final-summary call now preserves collected Evidence in a conservative fallback Finding instead of failing the entire Incident.
- Redis, database, downstream, JVM, and thread-pool direct evidence aliases normalize to catalog cause codes without accepting unknown Evidence IDs.
- Diagnosis and Critic prompts distinguish direct signals from optional corroboration and treat uncited Agent prose as unaudited context.
- Critic-requested follow-up agents are now enforced by the Supervisor plan validator, so a model cannot replace a targeted JVM follow-up with an unrelated Agent.
- A JVM finding with direct `THREAD_POOL_ACTIVE_AT_MAX` plus `BLOCKING_TASK_FOUND` evidence is normalized to `JVM_THREAD_POOL_EXHAUSTED` rather than being downgraded to `UNKNOWN`; the EvidenceGate still remains the final confidence authority.
- The slow SQL laboratory now executes the delay inside PostgreSQL so `pg_stat_statements` can observe it.
- Slow-statement detection checks both mean and maximum execution time, preventing intermittent outliers from being hidden by historical averages.

All scenarios above ran with FaultPilot in `PRODUCTION_READ_ONLY`; their inject/recover endpoints belong only to the target lab services. FaultPilot itself performed no remediation. Live Jaeger corroboration and production remediation handlers remain deployment-specific integrations and are intentionally not simulated as production evidence.

## GLM-5 Provider Migration

Status: PASSED

- The remote model is `glm-5`, reached through a deployment-specific Alibaba Bailian OpenAI-compatible endpoint supplied by `MODEL_BASE_URL`.
- Existing deployments keep reading the credential from `QWEN_API_KEY`; neither the credential nor the tenant-specific endpoint is stored in tracked configuration, logs, tests, or Git.
- Provider-specific Java configuration and error text were renamed to generic remote-model terminology so every Supervisor, Specialist, Diagnosis, and Critic role uses the configured GLM client.
- A direct compatibility probe returned HTTP 200 with response model `glm-5`; `mvn -pl faultpilot-server -am test` passes all 51 server tests.
- Scenario run `98c67fd1-d48b-4b7e-962a-a0946603b2b4` reached Prometheus executor values `active=4` and `size=4` before incident creation, avoiding stale-scrape races.
- Incident `ea9e1100-4634-4ead-8e41-d52ec9f19dbe` used the vague symptom `接口有时会卡住，我不确定是什么原因`. GLM selected only `JVM_AGENT`; the Specialist, Diagnosis, and Critic roles all completed successfully.
- Evidence included `THREAD_POOL_ACTIVE_AT_MAX` and `BLOCKING_TASK_FOUND`. Arthas retained four WAITING threads, `FaultScenarioManager.lambda$startBlockedTasks$9(FaultScenarioManager.java:276)`, and `java.util.concurrent.locks.LockSupport.park`.
- The Critic returned `PASS`; EvidenceGate returned `CONFIRMED` with primary cause `JVM_THREAD_POOL_EXHAUSTED`. The controlled fault was recovered after verification.

## Fuzzy Symptom and Self-Reflection Verification

Status: PASSED (agent routing and bounded self-reflection; final result intentionally inconclusive)

- Scenario run: `d3f01403-925e-4f39-b8fb-d9efb6c1d9ec` with `THREAD_POOL_EXHAUSTED`.
- Incident: `4a4cc605-6657-4496-b4ea-a4b5ab20da51`.
- User symptom: `出现了问题` (intentionally vague and without a proposed cause).
- Baseline evidence included `THREAD_POOL_ACTIVE_AT_MAX`, while process CPU and Redis client-pool evidence were normal.
- The remote Qwen Supervisor selected only `JVM_AGENT` in round 1. The plan reason explicitly cited the structured thread-pool evidence rather than the symptom wording.
- The Diagnosis Agent proposed `JVM_THREAD_POOL_EXHAUSTED`; the independent Critic returned `FOLLOW_UP` because the JVM finding lacked a blocking-thread corroboration.
- Round 2 scheduled only `JVM_AGENT` and persisted a second specialist task. It did not fan out to all Agents.
- The final result was `INCONCLUSIVE`: Arthas was not configured in the server-side catalog, and a required second-round remote model call was unavailable. The system recorded the missing diagnostic capability and model failure, and did not claim a source method or confirm a root cause from model prose.

This verifies that a caller can report an unknown or incorrect symptom. FaultPilot first uses structured observations, then lets the model Critic request a targeted follow-up, with EvidenceGate enforcing the final confidence boundary.

## GLM-5 Timeout and Database Pool Regression

Status: PASSED

- Incidents `d25c8559-cfc4-4f0d-8e36-a8aa286d376e` and `63c7b840-5157-4ab1-94cb-4f3cc4e79a08` reproduced two Diagnosis Synthesizer failures at approximately 45,000 ms per attempt.
- The configurable model timeout default was raised from 45 to 90 seconds, and remote failures now retain their cause and record a bounded failure category without logging credentials or prompts.
- Scenario run `2005d312-a2f5-4829-88b1-4b34397287ef` injected `DB_POOL_EXHAUSTED`; incident `6b5ad1d0-d17a-4596-b893-367391b1b6a4` used an intentionally cause-agnostic timeout symptom.
- The first Diagnosis Synthesizer call completed successfully in 84,126 ms, directly validating the new timeout boundary. Both Critic calls returned `PASS` and the bounded second investigation round completed.
- The incident finished `DIAGNOSED`; EvidenceGate returned `SUPPORTED` with primary cause `DB_POOL_EXHAUSTED` and retained `CONNECTION_HOLDING_QUERY_FOUND` as missing corroboration.
- The controlled scenario was recovered, all order-service diagnostic flags returned to false, and all 52 server tests passed.
