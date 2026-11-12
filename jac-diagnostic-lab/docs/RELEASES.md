# Releases y APK

Los **milestones** de GitHub organizan el trabajo. La **APK** se publica en **Releases** (GitHub no adjunta binarios a un milestone).

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
