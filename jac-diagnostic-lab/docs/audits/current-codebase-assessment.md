# Auditoría del código existente — Sprint 0 (Master Prompt v4.0)

**Fecha:** 2026-11-12  
**Rama:** `cursor/jac-diagnostic-lab`  
**Commit auditado:** `6b6470c1562890e96915f9fda20d683c3c5bc7bd`  
**Remoto:** `origin/cursor/jac-diagnostic-lab` = mismo SHA (working tree limpio)  
**Tag release latest:** `v1.2.0` → `06c6606` (README posterior en `6b6470c` no regeneró APK)  
**Gradle en HEAD:** `versionName=1.2.0`, `versionCode=13`  
**APK en teléfono a525a763:** tras redeploy 1.3.0 → `versionName=1.3.0`, `versionCode=14` (antes 1.1.0/12)

## Alcance de inspección

Inspeccionado en esta auditoría:

| Área | Rutas | ¿Inspeccionado? |
| --- | --- | --- |
| Gradle / versiones | `app/build.gradle.kts`, README, RELEASES | Sí |
| Manifest / permisos | `AndroidManifest.xml` | Sí |
| BT Classic / BLE | `bt/Bluetooth.kt` | Sí (parcial: connect/GATT/ATI) |
| Servicio FGS | `DiagnosticConnectionService` en Manifest + Bluetooth.kt | Sí |
| ELM / parser | `elm/ElmSession.kt`, `Protocol.kt`, `TransportKind.kt` | Sí |
| OBD decode / bitmap | `obd/Decoders.kt`, `Semantics.kt` | Sí |
| Sesión / poll | `LabController.kt` | Sí |
| UI Monitor/Taller | `ui/Ui.kt`, `Gauges.kt`, `WorkshopGuide.kt` | Sí |
| Seguridad | `security/*`, `ReadOnlyGate` | Sí |
| Tests | `*Test.kt` (5 clases) | Sí |
| Specs / constitución | `specs/*`, `.specify/memory/constitution.md` | Sí |
| CI | `.github/` | **Ausente** |
| Room / DB telemetría | — | **No existe** |
| REA / JADX | — | **No integrado aún** |
| Todos los 97 archivos git del mono-repo | `git ls-files` | **No línea a línea** — ver límite abajo |

**Límite:** no se afirma auditoría byte-a-byte de cada archivo del mono-repo OLDB2. Sí se afirma revisión de los módulos listados arriba y del estado git/release/teléfono.

## Discrepancia README 1.2.0 vs Gradle 1.0.1

| Fuente | versionName | versionCode |
| --- | --- | --- |
| Consulta pública previa (obsoleta) | 1.0.1 | 11 |
| **HEAD Gradle** | **1.2.0** | **13** |
| README jac-diagnostic-lab | 1.2.0 | 13 |
| Tag/release GitHub `v1.2.0` | 1.2.0 | (APK del commit `06c6606`) |
| Teléfono Xiaomi instalado | **1.1.0** | **12** |

**Conclusión:** la discrepancia 1.2.0↔1.0.1 **no persiste en HEAD**. Persiste un **desfase de despliegue**: el teléfono no tiene la APK 1.2.0.

## Hallazgos

### AUD-001 — Teléfono sin APK del HEAD/release latest
- **Archivo:** n/a (dispositivo)  
- **Requisito:** AC-012 / distribución  
- **Síntoma:** dumpsys → 1.1.0 mientras HEAD/release = 1.2.0  
- **Causa:** no se reinstaló tras v1.2.0  
- **Evidencia:** `adb dumpsys package` 2026-11-12  
- **Gravedad:** Media (operativa)  
- **Repro:** instalar APK antigua; comparar con `build.gradle.kts`  
- **Corrección:** instalar `dist/jac-diagnostic-lab-1.2.0-debug.apk` o siguiente release

### AUD-002 — No hay matriz de capacidades con motivo de ausencia
- **Archivo:** `LabController.kt` ~568–580; UI Monitor  
- **Requisito:** AC-003 / SPEC-022  
- **Síntoma:** solo `Set<Int> supported`; UI dice «fuera de mapa» o «—» sin distinguir adapter/ECU/bitmap/NO DATA/OEM  
- **Causa:** `readSupportedInternal` guarda bitmap, no un inventario tipado  
- **Evidencia:** código actual; no existe `ObdCapabilityDiscoveryEngine`  
- **Gravedad:** Alta (producto v4)  
- **Repro:** sin sesión, Monitor muestra canales sin explicar por qué no hay valor  
- **Corrección:** motor de descubrimiento + pantalla «Qué puede leer»

### AUD-003 — Polling único pero sin scheduler tipado ni Hz por sensor
- **Archivo:** `LabController.startPoll`  
- **Requisito:** AC-004 / SPEC-023  
- **Síntoma:** un intervalo global; Hz efectivo de sesión, no por PID  
- **Causa:** bucle secuencial simple  
- **Evidencia:** `intervalMs` único; `SessionTimeMetrics` agregado  
- **Gravedad:** Alta  
- **Corrección:** `VehicleTelemetryEngine` + métricas por canal (Sprint 3)

### AUD-004 — Sin persistencia Room/SQLite de telemetría
- **Archivo:** sesiones en `files/` texto/JSON  
- **Requisito:** SPEC-027 / AC-007  
- **Síntoma:** no hay tablas Measurement/SupportedPid versionadas  
- **Causa:** no implementado  
- **Gravedad:** Alta  
- **Corrección:** repositorio local (Sprint 3–4)

### AUD-005 — Sin CI (.github ausente)
- **Archivo:** —  
- **Requisito:** §14 CI/CD  
- **Síntoma:** tests solo locales  
- **Gravedad:** Media  
- **Corrección:** workflow unit test + assemble (SPEC-030)

### AUD-006 — Tag v1.2.0 ≠ HEAD tipado
- **Archivo:** git tags  
- **Síntoma:** `v1.2.0` = `06c6606`; HEAD = `6b6470c` (solo README)  
- **Gravedad:** Baja  
- **Corrección:** next release desde HEAD con APK regenerada

### AUD-007 — Interruptor de freno aún no modelado como REQUIRES_DIFFERENT_HARDWARE en UI de capacidades
- **Archivo:** `WorkshopGuide` / dossier (texto); no en matriz PID  
- **Requisito:** §4.3 / OEM  
- **Gravedad:** Media  
- **Corrección:** entrada explícita en matriz de capacidades

### AUD-008 — GATT 133 sin estrategia de reintento estructurada
- **Archivo:** `Bluetooth.kt` BleInspector; autodiag  
- **Requisito:** Sprint 1 conectividad  
- **Síntoma:** fallo y fin; recovery textual  
- **Gravedad:** Media  
- **Corrección:** backoff + preferencia Classic si emparejado (Sprint 1)

### AUD-009 — Bloques bitmap Mode 01: 0x00/0x20/0x40 sí; 0x60+ no
- **Archivo:** `readSupportedInternal`  
- **Requisito:** §4.1  
- **Síntoma:** PIDs > 0x60 no descubiertos aunque la ECU declare 0x40→0x60  
- **Evidencia:** solo encadena si `0x20`/`0x40` ∈ supported  
- **Gravedad:** Media  
- **Corrección:** extender cadena 0x60/0x80/0xA0/0xC0 cuando el bit next-page esté set

### AUD-010 — REA / benchmark apps no iniciados
- **Requisito:** §7 AC-010  
- **Gravedad:** Baja en Sprint 0; planificada Sprint 6  
- **Corrección:** clonar `morluto/rea` fuera del APK; auditar nuestra APK

## Fortalezas comprobadas

- Solo lectura: Mode 04 denegado (`ObdCommandPolicy` + tests).  
- `TransportKind` Production vs Research + `TransportIsolation`.  
- Semántica 0x11 mariposa; ATRV tipado; freeze P0579; DTC exactos (regresión).  
- Monitor con medidores + capturas antes/después + ciclo Taller.  
- Constitución Spec Kit presente; SPEC-001…003 existen.

## Plan de recuperación inmediato (ordenado)

1. AUD-002 → ObdCapabilityDiscoveryEngine + UI (este sprint).  
2. AUD-001 → reinstalar APK en teléfono.  
3. AUD-009 → extender bitmaps.  
4. AUD-003/004 → telemetría + Room (siguiente).  
5. AUD-005 → CI mínima.  
6. AUD-008 → resiliencia BT.  
7. AUD-010 → REA fuera de APK.

## Evidencia de versión

```text
HEAD 6b6470c · Gradle 1.2.0 (13) · phone 1.1.0 (12) · release tag v1.2.0 @ 06c6606
```
