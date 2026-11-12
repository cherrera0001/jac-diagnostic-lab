# SPEC-003 — Evidencia diagnóstica y corrección semántica (sesión 2233-288)

**Baseline:** JAC Diagnostic Lab `v0.4.0`  
**Evidencia original:** `c4a-automotive-security/evidence/original/2026-11-11T2233-288/`  
**PDF SHA-256 verificado:** `0a7872d6704d35b712fd60cd6a1de492524a57a3aa3c648829e5f8e3a6676828`

### REQ-DIAG-001 — PID 0x11 es Absolute Throttle Position
**Estado:** en implementación  
**Issue:** DIAG-001  
**Comportamiento:** El nombre mostrado y `standardName` identifican posición absoluta de mariposa. No se etiqueta como pedal de acelerador.  
**Criterio:** `TEST-PID-011` — E5 → ≈89.8039 %; semántica mariposa/Throttle Position.  
**Prueba:** `DiagnosticRegressionTest.testPid011_mathAndSemanticAbsoluteThrottleNotPedal`

### REQ-DIAG-002 — Plausibilidad contextual sin auto-falla
**Estado:** en implementación  
**Issue:** DIAG-002  
**Comportamiento:** Con ~787 rpm, 0 km/h y 0x11 alto, se emite nota de plausibilidad. No se marca falla confirmada.  
**Prueba:** `testDiag002_highThrottleWithIdleIsPlausibilityNoteNotAutoFault`

### REQ-DIAG-003 — ATRV requiere medición independiente
**Estado:** en implementación  
**Issue:** DIAG-003  
**Comportamiento:** 16.9 V → `REQUIRES_INDEPENDENT_MEASUREMENT`; `isConfirmedBatteryMeasurement=false`.  
**Prueba:** `testVolt001_adapterEstimateRequiresIndependentMeasurement`

### REQ-DIAG-004 — Conservar DTC P0579 y P2299 exactos
**Estado:** verificado por regresión  
**Issue:** DIAG-004  
**Comportamiento:** Bytes/códigos exactos; definiciones JAC = UNKNOWN sin fuente.  
**Prueba:** `testDtc001_preservesP0579AndP2299Exactly`

### REQ-DIAG-005 — Métricas de ventana temporal
**Estado:** en implementación  
**Issue:** DIAG-005  
**Comportamiento:** Seis timestamps del CSV → ventana 755 ms y Hz efectivo.  
**Prueba:** `testTime001_sixSampleWindow755ms`

### REQ-DIAG-006 — ATI no prueba autenticidad
**Estado:** cubierto por hallazgos C4A-AUTO-2026-002  
**Issue:** DIAG-006  

### REQ-DIAG-007 — Estado operacional con RPM ≠ 0
**Estado:** en implementación  
**Issue:** DIAG-007  
**Comportamiento:** RPM 786.75 ⇒ no describir como solo contacto ON.  
**Prueba:** `testDiag007_rpmNonZeroIsNotContactOnly`

## Fuera de alcance de esta spec
Bluetooth adversarial, CAN lab, UDS privilegiado, Mode 04.
