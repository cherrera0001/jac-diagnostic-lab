# Releases y APK

Los **milestones** de GitHub organizan el trabajo. La **APK** se publica en **Releases** (GitHub no adjunta binarios a un milestone).

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
