# Releases y APK

Los **milestones** de GitHub organizan el trabajo. La **APK** se publica en **Releases** (GitHub no adjunta binarios a un milestone).

## v1.9.0 — Identificación de ECU y perfil JAC T8

- **Tag:** `v1.9.0`
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.9.0 (`jac-diagnostic-lab-1.9.0-debug.apk` + `.sha256`)
- **versionCode:** 22
- **Fecha:** 2026-11-18
- **APK local:** `dist/jac-diagnostic-lab-1.9.0-debug.apk` + `.sha256`
- **SHA-256:** `f050d6985ab08d30baf5aa8678f2ecd57cd20ac89a2cc15c72a1a3f328863865`

Versión visible en el pie y en PDF/JSON/TXT (corrige el «1.0.0» fijo del PDF). Mode 09 por ECU con raw y timestamps, VIN directo, perfil por evidencia (DCM6.2AP / DCM6.2AP-6D / P/N 1026400FD020 / MT80 / EDC17C81 / MD1CS089), catálogo versionado con fuentes. Probado en replay (JVM). **No probado en hardware** en esta iteración.

## v1.8.0 — Salud, escaneo completo y pruebas

- **Tag:** `v1.8.0`
- **versionCode:** 21
- **Fecha:** 2026-11-18
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.8.0 (`jac-diagnostic-lab-1.8.0-debug.apk` + `.sha256`)

Rediseño en cinco pestañas (Estado, En vivo, Pruebas, Datos, Informe). Escaneo de todos los PID declarados con multi-PID, monitores diésel, Mode 06 y Mode 09, salud por sistema y pruebas guiadas con CSV. Validado con la T8 en marcha: 68 PID, 82 señales, 19 pruebas Mode 06. Límite conocido: el clon corta ISO-TP tras 4 tramas.

## v1.7.1 — Limpiar todo / Reset

- **Tag:** `v1.7.1`
- **versionCode:** 20
- **Fecha:** 2026-11-12

Botón siempre habilitado en todas las pestañas: corta BT, cancela monitor, libera `busy`/compuerta de autodiag y limpia RAM. No borra sessions/telemetry ya guardados. Evita forzar detención desde Android.

## v1.7.0 — Completar captura (auto-monitor + reconectar)

- **Tag:** `v1.7.0`
- **versionCode:** 19
- **Fecha:** 2026-11-12

Tras autodiag con ECU, arranca monitor solo. Botón **Reconectar y continuar captura** (clásico → BLE) sin borrar evidencia. Fallback RFCOMM si BLE no deja ECU. CI unit test. FFE1 aceptado; queries de apps rivales.

## v1.6.1 — Captura estable (GATT FFF + enlace caído)

- **Tag:** `v1.6.1`
- **versionCode:** 18
- **Fecha:** 2026-11-12

Corrige selección por defecto 2A00/2A05 (no OBD). Prefiere FFF2/FFF1. Monitor avisa ENLACE CAÍDO y conflictos con OBD Auto Doctor / clones Torque. La cacería de sensores escribe JSONL. Servicio en primer plano al monitorear.

## v1.6.0 — Monitor «Qué ocurre» (mecánico)

- **Tag:** `v1.6.0`
- **versionCode:** 17
- **Fecha:** 2026-11-12

`LiveSituationEngine`: franja de situación, hipótesis H1–H4 abiertas, medidores siempre visibles con VIVO/STALE/ERROR/NO SOPORTADO, banner de sensores que la app no lee (freno, APP fuera de mapa). Tests SIT-001…007. No inventa PID OEM ni recomienda piezas.

## v1.5.0 — Persistencia telemetría + LinkPhase (SPEC-024/027)

- **Tag:** `v1.5.0`
- **versionCode:** 16
- **Fecha:** 2026-11-12
- **Auditoría:** `docs/audits/latest-code-assessment.md`

JSONL append-only + pestaña Historial. `LinkPhase` (BT≠ECU). Reintentos GATT con backoff (133). Tests STORE/BT.

## v1.4.0 — VehicleTelemetryEngine (SPEC-023)

- **Tag:** `v1.4.0`
- **versionCode:** 15
- **Fecha:** 2026-11-12
- **APK:** `jac-diagnostic-lab-1.4.0-debug.apk`

Scheduler un-comando FAST/MEDIUM/SLOW, Hz/latencia/edad **por PID** (medidos), pause en DTC/mapa. Monitor muestra métricas por canal. Tests TEL-001…005.

## v1.3.0 — Matriz «Qué puede leer» (SPEC-022)

- **Tag:** `v1.3.0`
- **versionCode:** 14
- **Fecha:** 2026-11-12
- **APK:** `jac-diagnostic-lab-1.3.0-debug.apk`
- **Auditoría:** `docs/audits/current-codebase-assessment.md` @ `6b6470c` → este release

`ObdCapabilityDiscoveryEngine` + pestaña **Capacidad** con motivos de ausencia. Bitmaps Mode 01 encadenados 0x00…0xC0. Tests CAP-001…004.

## v1.2.0 — Aislamiento lab + cierre DIAG P0

- **Tag:** `v1.2.0`
- **versionCode:** 13
- **Fecha:** 2026-11-12
- **APK:** `jac-diagnostic-lab-1.2.0-debug.apk`
- **URL:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.2.0

`TransportKind` + `ResearchLabTransport` + `TransportIsolation` (LAB-CAN-001). Docs SEC-BT / TARA unidad. Issues DIAG-002…006 cerrados. SAFE-001 sigue abierto hasta taller.

## v1.1.0 — Monitor de taller + APK pública

- **Tag:** `v1.1.0`
- **versionCode:** 12
- **Fecha:** 2026-11-11
- **APK:** `jac-diagnostic-lab-1.1.0-debug.apk` en el release y en `dist/`
- **URL:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.1.0

Incluye medidores ordenados, capturas antes/después, ciclo de 7 pasos para el mecánico y PDF con esa guía.

**Requisito de hardware:** ELM327 Mini (o compatible) emparejado como `OBDII` y/o `OBDBLE`. Sin dongle no hay lectura nueva; sí se puede revisar una sesión ya guardada y generar PDF.

## v1.0.1

- Autodiag reinyecta OBDBLE/OBDII emparejado tras el escaneo.
- No inventa éxito si GATT falla (p. ej. código 133).

## v1.0.0

- Ficha de falla, freeze P0579 corregido, captura ~15 s, banner SAFE-001.

## Anteriores

Ver tags `v0.4.x` / `v0.6.0` en GitHub Releases. Preferir siempre la última `v1.x`.
