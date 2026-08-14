---
apiVersion: faultpilot/v1
kind: DiagnosticSkill
metadata:
  name: jvm-thread-pool-exhausted
  version: 1.0.0
spec:
  ownerAgent: JVM_AGENT
  description: Confirm executor saturation and locate application threads blocked in source code.
  triggerEvidenceTypes:
    - THREAD_POOL_ACTIVE_AT_MAX
    - THREAD_POOL_QUEUE_GROWING
  producesEvidenceTypes:
    - THREAD_POOL_ACTIVE_AT_MAX
    - THREAD_POOL_NORMAL
    - BLOCKING_TASK_FOUND
  allowedTools:
    - query_prometheus_thread_pool
    - query_arthas_waiting_threads
  completion:
    allOf:
      - THREAD_POOL_ACTIVE_AT_MAX
      - BLOCKING_TASK_FOUND
    anyOf: []
  limits:
    maxSteps: 3
    timeoutSeconds: 60
  riskLevel: READ_ONLY
---

# JVM thread-pool exhaustion

## Goal

Confirm worker-pool saturation and identify the blocking application operation with source location.

## Investigation order

1. Reuse current-run executor saturation Evidence when present; otherwise query the pool ratio.
2. When the pool is saturated, inspect bounded Arthas WAITING threads.
3. Extract thread identity, application method, source file, line number, and blocking operation.

## Stop conditions

- THREAD_POOL_ACTIVE_AT_MAX plus BLOCKING_TASK_FOUND: complete.
- THREAD_POOL_NORMAL: insufficient for a pool-exhaustion claim.
- Arthas unavailable or package prefixes missing: preserve saturation Evidence and return insufficient.
- Step or time budget exhausted: return insufficient.
