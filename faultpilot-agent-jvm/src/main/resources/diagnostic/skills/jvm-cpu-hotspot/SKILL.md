# JVM CPU hotspot

## Goal

Confirm process-level CPU pressure and locate the application method consuming CPU.

## Investigation order

1. Reuse current-run PROCESS_CPU_HIGH Evidence when present; otherwise query process CPU.
2. When CPU is high, inspect bounded Arthas hot threads.
3. Stop only when both process CPU pressure and an application source location are available.

## Stop conditions

- PROCESS_CPU_HIGH plus CPU_HOT_METHOD_FOUND: complete.
- PROCESS_CPU_NORMAL: insufficient for a CPU-hotspot claim.
- Arthas unavailable or package prefixes missing: preserve metric Evidence and return insufficient.
- Step or time budget exhausted: return insufficient.
