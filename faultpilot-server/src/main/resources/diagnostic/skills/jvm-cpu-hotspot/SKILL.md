---
apiVersion: faultpilot/v1
kind: DiagnosticSkill
metadata:
  name: jvm-cpu-hotspot
  version: 1.0.0
spec:
  ownerAgent: JVM_AGENT
  description: Confirm process CPU pressure and locate the responsible application method.
  triggerEvidenceTypes:
    - PROCESS_CPU_HIGH
  producesEvidenceTypes:
    - CPU_HOT_METHOD_FOUND
  allowedTools:
    - query_prometheus_process_cpu
    - query_arthas_hot_threads
  completion:
    allOf:
      - PROCESS_CPU_HIGH
      - CPU_HOT_METHOD_FOUND
    anyOf: []
  limits:
    maxSteps: 3
    timeoutSeconds: 60
  riskLevel: READ_ONLY
---

# JVM CPU hotspot

## Goal

Confirm that the target service has process-level CPU pressure and locate the application method consuming CPU.

## Investigation order

1. Query the bounded process CPU metric for the target service.
2. If CPU is high, query the bounded Arthas hot-thread gateway.
3. Stop when both process CPU evidence and an application source location are available.

## Stop conditions

- `PROCESS_CPU_HIGH` and `CPU_HOT_METHOD_FOUND`: complete.
- Normal CPU: stop without claiming a hotspot.
- Arthas unavailable or no configured package prefix: preserve the CPU evidence and return insufficient evidence.
- Maximum steps or deadline reached: return insufficient evidence.
