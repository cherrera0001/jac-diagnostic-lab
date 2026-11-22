# Releases y APK

Los **milestones** de GitHub organizan el trabajo. La **APK** se publica en **Releases** (GitHub no adjunta binarios a un milestone).

## v1.17.0 — Hub «Señales»: espectro RF nativo (Wi-Fi, 4G/5G, GNSS, FFT magnético) y Escudo defensivo

- **Tag:** `v1.17.0`
- **versionCode:** 34
- **Fecha:** 2026-11-22
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.17.0 (`jac-diagnostic-lab-1.17.0-debug.apk` + `.sha256`)
- **SHA-256:** `d192f60359a9f14c832bc0efeb31a868e6c2d784dabc184ab0c9cf8f43395f82`

Pestaña **Señales** con 3 sub-vistas (`BLE / Tags`, `Redes y RF`, `Seguridad`): espectro Wi-Fi 2,4/5/6 GHz con gráfico de canales y detección de Evil Twin; celdas 5G NR / 4G LTE / 3G / 2G con conversión 3GPP `NR-ARFCN` y `EARFCN` a MHz y capacidad Shannon–Hartley; constelaciones GNSS y frecuencias portadoras; FFT radix-2 en vivo del magnetómetro (detección de oscilaciones e imanes > 120 µT); y auditoría defensiva del vehículo exportable a JSON (OBD expuesto, alerta `0x41`, downgrade 2G, Evil Twin, GNSS, magnetómetro) + guía física/SDR. Verificado por CI (`SignalsTest`) e instalado en Xiaomi `2512BPNDAG`. **PENDING_VALIDATION en campo.**

## v1.16.0 — Tags: categorías BLE reales e identificación activa GATT/DULT

- **Tag:** `v1.16.0`
- **versionCode:** 33
- **Fecha:** 2026-11-22
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.16.0 (`jac-diagnostic-lab-1.16.0-debug.apk` + `.sha256`)
- **SHA-256:** `bf55b51290cf22d4d8ab9a7e478d19976a38808a6b3bb925123972cf894e3761`

Categorías visuales y filtros por tipo de dispositivo (`Rastreador`, `Audio`, `Reloj / Pulsera`, `Teléfono / PC`, `TV / Hogar`, `Periférico`, `Auto / OBD`, `Baliza`, `BLE`) extrayendo `GAP Appearance` (`AD 0x19`), `BluetoothClass`, `Xiaomi MiBeacon 0xFE95`, `Samsung SmartTag 0xFD59/0xFD5A`, `Microsoft Swift Pair`, `Harman/JBL`, `Huami/Amazfit`, `Logitech`, Apple Continuity multi-TLV y modo Google Find Hub FMDN (`0x40`/`0x41`). Consulta activa bajo demanda **«Identificar»** (`GattInspector`: GAP + DIS + Batería + IETF DULT `15190001-…`) y diálogo **«Datos BLE»**. Verificado por CI e instalado en Xiaomi `2512BPNDAG`. **PENDING_VALIDATION con tag físico.**

## v1.15.0 — Tags: nombres BLE reales, calibración espacial silenciosa y UI minimalista

- **Tag:** `v1.15.0`
- **versionCode:** 32
- **Fecha:** 2026-11-22
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.15.0 (`jac-diagnostic-lab-1.15.0-debug.apk` + `.sha256`)
- **SHA-256:** `1cf4d6e5db95595f6ff2f03923b9ca2698eecc635955b7967323db56fa4e71bb`

Nombres BLE reales (`scanRecord.deviceName`, `device.alias` en Android 11+, `device.name`) con fusión ADV + respuesta de escaneo; inferencia Bluetooth SIG / subtipo Apple / servicios cuando no hay nombre anunciado; apodos locales persistentes y filtros rápidos (`Todos`, `Rastreadores`, `Con nombre`); estimador espacial GPS + RSSI alimentado silenciosamente en segundo plano (calibrado con varianza residual `σ²`, `R²`, `RMS` y rechazo de caminatas en línea recta o ruido puro); vista Buscar minimalista con arco de incertidumbre, detección de señal perdida y soporte de `BackHandler`. Verificado por CI (tests, lint, APK) e instalado en Xiaomi `2512BPNDAG`. **PENDING_VALIDATION con tag físico.**

## v1.14.0 — Tags: flecha de dirección orientativa y diseño minimalista (sin release propio; incluido en v1.15.0)

- **versionCode:** 31 · **Fecha:** 2026-11-21
- Brújula (`TYPE_ROTATION_VECTOR`) + `DirectionEstimator` por sectores de 22,5° con contraste mínimo de 5 dB; pruebas `DirectionEstimatorTest`.

## v1.13.0 — Pestaña Tags: rastreadores BLE, modo Buscar y mapa exportable

- **Tag:** `v1.13.0`
- **versionCode:** 30
- **Fecha:** 2026-11-21
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.13.0

Escaneo BLE pasivo, clasificación por firma pública (Apple Find My, Google Find Hub, Samsung SmartTag, Tile, Chipolo, Xiaomi), bandas de proximidad y exportación GeoJSON/GPX + `geo:`. Quita `neverForLocation` de `BLUETOOTH_SCAN`. **PENDING_VALIDATION con tag físico.**

## v1.12.1 — Borrar y limpiar: partir desde cero

- **Tag:** `v1.12.1`
- **versionCode:** 29
- **Fecha:** 2026-11-21
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.12.1

Un solo botón «Borrar y limpiar» con copia privada automática previa, Mode 04 por compuerta (`ClearDtcGate`) y relectura 03/07/0A. **PENDING_VALIDATION en vehículo.**

## v1.12.0 — Borrado seguro de códigos (Mode 04) y forense de paros al frenar

- **Tag:** `v1.12.0`
- **versionCode:** 28
- **Fecha:** 2026-11-21
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.12.0

Compuerta `ClearDtcGate` para Mode 04 y módulo `EventForensics` en ruta con 5 hipótesis rivales. **PENDING_VALIDATION en vehículo.**

## v1.11.2 — Informe profesional con sello C4A, bóveda privada y exportación cifrada

- **Tag:** `v1.11.2`
- **versionCode:** 27
- **Fecha:** 2026-11-21
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.11.2

PDF unificado con sello vectorial `C4A · c4a.cl` + huella SHA-256, bóveda privada (`filesDir/vault/`) y exportación cifrada AES-256-GCM (`.jacenc`).

## v1.11.1 — Iteración A (P0)

- **Tag:** `v1.11.1`
- **versionCode:** 26
- **Fecha:** 2026-11-21
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.11.1

Semántica estricta de medición (`DtcReadState`, plausibilidad `UNVERIFIED`, ventana temporal, prueba de freno honesta) y CI con `gradlew` ejecutable.

## v1.11.0 — Validada en la unidad real: ECU DCM6.2 e informe profesional

- **Tag:** `v1.11.0`
- **versionCode:** 25
- **Fecha:** 2026-11-18
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.11.0
- **SHA-256:** `623c0faf089e6dec9ecc0f3e31a155709367e69ccd1ca9195f18363ce4d64d7a`

Lectura real: P0579 (alm.+pend.) y P2299, ECU Delphi DCM6.2, códigos borrados hace 311 km. PDF profesional, detalle tocable, prueba freno contra acelerador, reconexión BLE automática. 101 pruebas JVM. Instalada y usada en la unidad.

## v1.10.1 — Enlace BLE trabado (sin release propio; incluido en 1.11.0)

## v1.10.0 — Informe de taller y dimensiones

- **Tag:** `v1.10.0`
- **versionCode:** 23
- **Fecha:** 2026-11-18
- **APK:** https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.10.0 (`jac-diagnostic-lab-1.10.0-debug.apk` + `.sha256`)
- **SHA-256:** `5c60b0445a231e40da36e263fad14448688d22fd65fece45da326ac40b6f81ed`

Informe de taller generado después de leer todo (DTC, cuadro congelado Mode 02, todos los PID, monitores, Mode 06, Mode 09, registro), con detalle y verificación por código. Cabecera y pestañas ajustadas. 100 pruebas JVM. Instalado en teléfono; **no probado en el vehículo**.

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
