# Backlog P0 — Fiabilidad diagnóstica

| ID | Título | Spec | Estado |
|---|---|---|---|
| DIAG-001 | PID 0x11 Absolute Throttle ≠ pedal | REQ-DIAG-001 | FIX en código + test |
| DIAG-002 | Plausibilidad 89.8% @ idle | REQ-DIAG-002 | Nota contextual |
| DIAG-003 | ATRV 16.9 V | REQ-DIAG-003 | REQUIRES_INDEPENDENT_MEASUREMENT |
| DIAG-004 | DTC P0579/P2299 trazables | REQ-DIAG-004 | Regresión |
| DIAG-005 | Serie 755 ms | REQ-DIAG-005 | SessionTimeMetrics |
| DIAG-006 | Procedencia ELM327 | REQ-DIAG-006 | Hallazgo C4A-002 |
| DIAG-007 | Estado operacional RPM>0 | REQ-DIAG-007 | OperationalState |

Criterio de terminado: tests verdes + evidencia original versionada + sin Mode 04.
