# Auditoría latest — Master Prompt v4.0 (12-11-2026)

**HEAD:** `fc996d6e26ae2e8cf62c04207d88969e194c289a`  
**Rama:** `cursor/jac-diagnostic-lab` (= remoto)  
**Gradle / teléfono medido:** `1.4.0` / `versionCode` 15  
**Referencia pública “v1.2.0” del prompt:** **obsoleta** respecto a HEAD (releases posteriores: 1.3.0, 1.4.0).  
**Working tree:** limpio al inicio de esta auditoría.

## Qué ya está implementado (no reinventar)

| Capacidad | Evidencia |
| --- | --- |
| Solo lectura / Mode 04 bloqueado | `ObdCommandPolicy`, tests |
| Transport Production vs Research | `TransportKind`, `TransportIsolation` |
| Bitmap PIDs + motivos de ausencia | `ObdCapabilityDiscoveryEngine`, UI Capacidad (1.3.0) |
| Telemetría continua 1-comando + Hz/PID | `VehicleTelemetryEngine` + `startPoll` (1.4.0) |
| Monitor medidores + antes/después | UI Monitor |
| DTC / freeze / ATRV tipado / 0x11 mariposa | regresiones DIAG |

## Hipótesis “por qué faltan parámetros” — veredicto con evidencia

| Id | Hipótesis | Veredicto en HEAD |
| --- | --- | --- |
| H1 | PID no soportado por ECU | **Parcialmente cubierto** — matriz Capacidad marca `PID_NOT_IN_BITMAP` |
| H2 | App no consulta PID correcto | **Parcial** — catálogo de interés acotado; no hay OEM inventado (correcto) |
| H3 | Error de parser | **No hallado** en PIDs de sesión 2233-288 (regresión verde) |
| H4 | Adaptador no soporta operación | **Observado** vía ATI imitable / GATT 133; no = ECU |
| H5 | BT interrumpe consultas | **Abierto** — GATT 133 sin reintento estructurado (AUD-L-001) |
| H6 | Scheduler demasiado rápido | **Mitigado** — bandas FAST/MED/SLOW; Hz medido |
| H7 | Perfil propietario requerido | **Desconocido** — sin afirmaciones OEM sin evidencia |
| H8 | Ausencia → cero | **Mitigado** — NO DATA / null; UI «—» |
| H9 | Solo foto, no bucle | **Cerrado en 1.4.0** — `startPoll` continuo |

## Hallazgos nuevos / abiertos

### AUD-L-001 — GATT 133 sin reintento ni estados tipados
- **Archivo:** `bt/Bluetooth.kt` `BleInspector.connectAndDiscover` ~289–298  
- **Problema:** un fallo 133 aborta; UI no distingue TransportConnected vs ElmReady vs EcuResponding  
- **Impacto:** AC-002/003 frágiles en BLE  
- **Corrección:** reintentos con backoff + `LinkPhase` en estado  
- **Prueba:** unitaria de política de reintento; E2E hardware pendiente

### AUD-L-002 — Persistencia de sesión incompleta para telemetría continua
- **Archivo:** `LabController.persist` ~830–841  
- **Problema:** JSON/TXT de sesión completa; flush cada ~12 muestras; **no** hay append JSONL por medición ni listado de historial en UI  
- **Impacto:** AC-009/010 débiles (se pierde granularidad / UX historial)  
- **Corrección:** `TelemetryStore` append-only + pantalla Historial  
- **Prueba:** append + listSessions en JVM

### AUD-L-003 — Tags locales incompletos vs GitHub Releases
- **Síntoma:** `git tag --list` local no listaba v1.3/v1.4 hasta fetch; remoto sí tiene releases  
- **Impacto:** confusión README/release (menor)  
- **Corrección:** `git fetch --tags` en CI/docs

### AUD-L-004 — Sin CI `.github/`
- **Impacto:** AC-015 parcial (solo local)  
- **Corrección:** workflow unit test (P3/P0 ligero)

### AUD-L-005 — Sin Room; sin VehicleFingerprint Mode 09 completo
- **Impacto:** P1 identificación ECU  
- **Estado:** pendiente; VIN solo con auth

### AUD-L-006 — DriveRecorder / viaje
- **Estado:** correctamente **no** habilitado (SAFE-001)

## Prioridad P0 de esta iteración

1. AUD-L-002 TelemetryStore + Historial  
2. AUD-L-001 LinkPhase + reintento GATT 133  
3. Tests + APK 1.5.0  

No se afirma prueba ECU real en esta iteración salvo que el hardware responda.
