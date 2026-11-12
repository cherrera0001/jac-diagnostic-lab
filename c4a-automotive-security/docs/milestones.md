# Estado de hitos (evidence-driven)

| Hito | Criterio de cierre | Estado | Evidencia |
|---|---|---|---|
| 0 Investigación | Inventario + limitaciones + fuentes | CERRADO | `research/`, `docs/` |
| 1 Conectividad real | BT + ELM + 0100 | CERRADO | sesión `2136-8803` |
| 2 Diagnóstico | DTC reales + correlación separada | CERRADO | P0579/P2299 + `VehicleFaultCorrelation` |
| 3 Auditoría Bluetooth | Auditor + hallazgos verificados | PARCIAL | Falta prueba 2.º teléfono (T-001) |
| 4 Modelado amenazas | T-001…T-010 + TARA doc | CERRADO (baseline) | `ThreatModelEngine` + `docs/tara/` |
| 5 Lab aislado | Emuladores + aislamiento comprobable | ABIERTO | esqueleto `lab/` |
| 6 Hardening | Controles APK + plan capas | PARCIAL | Policy/UI; operativa pendiente |
| 7 Entrega | APK + tests + informe reproducible | EN CURSO | repo + tests |

## Limitaciones explícitas

- Año / VIN / firmware / telemática de esta unidad: UNKNOWN.  
- Gateway: documental de línea, no medido aquí.  
- No se ejecutaron pruebas adversariales contra el vehículo.  
- No se declara cumplimiento ISO/SAE 21434.
