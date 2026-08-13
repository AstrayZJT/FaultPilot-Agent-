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
