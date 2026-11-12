# Informe comparativo — JAC Diagnostic Lab 0.4.0 → Sprint P0

**Fecha:** 2026-11-12  
**Baseline:** tag `v0.4.0` / commit `c08a425`  
**Evidencia ancla:** sesión `2026-11-11T2233-288`  
**PDF SHA-256:** `0a7872d6704d35b712fd60cd6a1de492524a57a3aa3c648829e5f8e3a6676828` (verificado)

## Qué había en 0.4.0

- Lectura real OBD vía OBDBLE, DTC P0579/P2299, CSV de sensores.
- PID 0x11 rotulado incorrectamente como «Posición del acelerador».
- ATRV 16.9 V sin estado formal de verificación independiente.
- Ventana de 6 muestras (755 ms) sin métricas de muestreo en UI/estado.
- Estado operacional no tipado (RPM>0 podía describirse como «solo contacto»).

## Qué cambió en P0 (SPEC-003)

| Ítem | Antes | Después |
|---|---|---|
| PID 0x11 | Acelerador | Posición absoluta de mariposa / Absolute Throttle Position |
| ATRV | Texto libre | `REQUIRES_INDEPENDENT_MEASUREMENT` |
| Tiempo | Solo timestamps CSV | `SessionTimeMetrics` (755 ms / Hz efectivo) |
| Operación | Implícito | `OperationalState.ENGINE_IDLING_OR_RUNNING` si RPM>50 |
| Evidencia | En teléfono | Versionada en `evidence/original/2026-11-11T2233-288/` |
| SDD | Specs 001–002 | + constitución + SPEC-003 |

## Pruebas

`DiagnosticRegressionTest` + suite previa: **BUILD SUCCESSFUL** (`testDebugUnitTest`).

## No demostrado en este sprint

- Segundo teléfono / auth Bluetooth (solo backlog).
- Lab CAN/UDS adversarial.
- Multímetro sobre batería (DIAG-003 sigue pendiente de medición humana).
- Definiciones DTC específicas JAC (siguen UNKNOWN).

## Próximo

Bluetooth security research (SPEC siguiente) y lab CAN aislado — sin Mode 04 ni tramas arbitrarias al vehículo.
