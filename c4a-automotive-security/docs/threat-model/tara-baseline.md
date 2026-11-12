# TARA baseline — JAC T8 / ELM327 (2026-11-11)

Referencia metodológica: ISO/SAE 21434. **No se declara certificación.**

## Activos

Integridad de comunicaciones, disponibilidad ECU, autenticación, capacidad de diagnóstico, integridad de evidencias, seguridad de ocupantes.

## Escenarios con evidencia directa

| ID | Título | Evidencia | Riesgo residual |
|---|---|---|---|
| T-001 | Acceso BT desconocido | GATT FFF1/FFF2 + ATI en sesión propia | UNKNOWN hasta 2.º teléfono |
| T-002 | Identidad/credenciales débiles | ATI `ELM327 v2.1` imitable | Presente con dongle genérico |
| T-003 | App no confiable | Allowlist solo en esta APK | Alto si dongle siempre montado |
| T-006 | Diagnósticos inconsistentes | Kinds timeout/NO DATA explícitos | Bajo si se conservan crudos |
| T-008 | Interferencia funcional | Prohibido ensayar en vehículo | UNKNOWN (arquitectura) |

## Separación falla técnica vs ciber

Los códigos leídos en una unidad son evidencia de diagnóstico y quedan en el informe local. No se atribuyen a un ataque sin prueba independiente, y no se copian a este repositorio.
