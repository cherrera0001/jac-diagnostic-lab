# SPEC-035 — Captura resiliente e informe fiable (Fase 1)

Origen: informe `jac-informe-1.pdf` (sesión 2026-11-24, app v1.19.4) y revisión crítica.
Hallazgo raíz: en `LabController.startGuidedTest`, 5 rondas sin respuesta terminaban la prueba y la
rutina de reconexión nunca se usaba dentro de la captura (29 `DISCONNECTED` seguidos a las 18:34:42).

## Requisitos

| ID | Requisito | Verificación |
|----|-----------|--------------|
| R1 | La captura no termina por pérdida de enlace: tras 3 rondas perdidas reabre el transporte, reinicializa el ELM y continúa, con espera creciente (2→10 s), hasta 10 intentos fallidos o 5 min de corte continuo. | `LinkRecoveryPolicyTest` |
| R2 | Cada corte queda registrado con hora de inicio, duración, intentos, motivo y si se recuperó; se muestra en el informe. | `LinkRecoveryPolicyTest`, `WorkshopReportPhase1Test` |
| R3 | El informe no se contradice: si la presión de riel mínima de la captura o un paro de motor indican caída, el resumen y el diferencial lo reflejan. | `WorkshopReportPhase1Test` |
| R4 | Tabla cronológica de eventos: hora, tipo, velocidad, RPM, pedal D/E, presión de riel (último valor conocido ≤ 2 s antes). | `EventTimelineTest` |
| R5 | Línea de tiempo gráfica alrededor de cada evento (−6 s / +4 s): RPM, pedal D/E, riel normalizados. | `EventTimelineTest` (datos), render manual |
| R6 | Valores no válidos se rotulan «no válido» (p. ej. PID 61 = byte 0 → −125 %), no se muestran como medición. | `ValueValidityTest` |
| R7 | Campos vacíos por límite del perfil de 8 PIDs se rotulan «fuera del perfil», distintos de «sin dato / falla». | `ValueValidityTest` |
| R8 | Campo opcional «Relato del conductor» (marcha, embrague, temperatura, síntoma, notas), persistente, en el informe. | `WorkshopReportPhase1Test` |

## Fuera de alcance
Reescritura de `LabController` (Fase 2), historial/comparación de sesiones (Fase 3).

## Invariantes
- Solo lectura: la recuperación de enlace no envía comandos de escritura ni Mode 04.
- Nunca se inventan datos en el hueco de un corte: el hueco se marca como evento `ENLACE`.
