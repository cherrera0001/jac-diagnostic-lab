# JAC Diagnostic Lab

Aplicación Android de diagnóstico **solo lectura** para una **JAC T8** (unidad declarada 2023, diésel) con adaptador **ELM327 Mini Bluetooth** (`OBDII` clásico / `OBDBLE` BLE).

**Versión actual: 1.19.0** (`versionCode` 36), 22-11-2026, instalada en Xiaomi `2512BPNDAG` (base **validada en la unidad real** con ECU Delphi DCM6.2; ver `research/validation/2026-11-18-unidad-real.md`). Integra **SPEC-034 R7–R11**: estimación de combustible sin mirar el tablero (`PID 0x2F` o respaldo `0x5E`/MAF `0x10` desde «Estanque lleno», siempre etiquetada `ESTIMACIÓN/INFERENCIA`), captura continua en segundo plano (`ForegroundService` con notificación persistente), **Android Auto nativo** en solo lectura (tablero 3×2 + detalle + combustible estimado), **Perfil de Captura Rápida de Falla** (Set Crítico de 8 PIDs: `0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42`) y **Diagnóstico Diferencial** en informe PDF (`WorkshopReport`/`WorkshopPdf`) con recomendación automática del Set Crítico. APK: [release v1.19.0](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.19.0).

Versión 1.10.0 (`versionCode` 23): Informe para el taller generado después de leer todo, con detalle por código, cuadro congelado completo y registro de comunicación (SPEC-033). APK: [release v1.10.0](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.10.0) · `dist/jac-diagnostic-lab-1.10.0-debug.apk`.

Versión 1.9.0 (`versionCode` 22): Identificación de ECU por Mode 09 (raw por ECU, VIN directo), perfil JAC T8 por evidencia observable con fuentes y niveles de confianza (SPEC-031). La versión en uso aparece en el pie de todas las pantallas y en cada PDF/JSON/TXT. APK: [release v1.9.0](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.9.0) · copia en `dist/jac-diagnostic-lab-1.9.0-debug.apk` + `.sha256`.

Versión anterior publicada: 1.8.0 (`versionCode` 21). Rediseño completo, escaneo de todos los PID que declara la ECU, salud por sistema y pruebas dinámicas.

Solo escribe en la ECU con «Borrar y limpiar» (Mode 04, vehículo detenido, relectura) y parte de cero. No reprograma la ECU y no ejecuta actuadores. Una conexión exitosa **no** autoriza a circular.

Licencia: [Apache-2.0](../LICENSE). Uso, aviso de seguridad y datos que no se suben: [NOTICE](../NOTICE) y [CONTRIBUTING.md](../CONTRIBUTING.md).

## Descargar la APK

| Qué | Dónde |
| --- | --- |
| Release + APK | https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.19.0 |
| Archivo | `jac-diagnostic-lab-1.19.0-debug.apk` (con su `.sha256` y copia en `dist/`) |
| SHA-256 (v1.19.0) | `fad3f334714eb368cb9c05f07346d74db56ccab169bd31e8ed4deba7fd3d2d5b` |
| Issue abierto | `#1 (SAFE-001)` · Project #22 (`JAC T8 / C4A Automotive Security`) |

### Antes de instalar

- Sin un **ELM327** (o compatible) en el puerto OBD-II no hay lectura.
- Cierre Torque, Car Scanner, OBD Auto Doctor, etc. El adaptador atiende a un solo cliente.
- Motor encendido o contacto en **ON**, camioneta **estacionada**.
- Es una build **debug** firmada para laboratorio, no una publicación de Play Store.

### Instalar

1. Descargue la APK del release y compruebe la huella: `sha256sum -c jac-diagnostic-lab-1.19.0-debug.apk.sha256`.
2. En Android permita instalar desde esa fuente (archivos o navegador).
3. O por USB:

```text
adb install -r jac-diagnostic-lab-1.19.0-debug.apk
```

## Pantallas y modos (v1.19.0)

**Nuevo en 1.19.0 (SPEC-034 R7–R11):**
- **Estimación de combustible sin mirar el tablero (`R7`, `FuelEstimator.kt`):** lee `PID 0x2F` si la ECU lo soporta; en caso contrario integra `PID 0x5E` (L/h) o respaldo `PID 0x10` (MAF g/s) desde el botón manual **«Estanque lleno»**. Etiqueta siempre los litros como **`ESTIMACIÓN/INFERENCIA`** (nunca medición directa) con invariante en código y prueba TDD `r23`.
- **Captura continua en segundo plano (`R8`, `DiagnosticConnectionService` + `BackgroundCaptureNotification.kt`):** `ForegroundService` (`connectedDevice`) con notificación persistente actualizada dinámicamente (estado de enlace, modo activo, rondas/muestras, reconexión y combustible estimado) y acceso directo a la app, manteniendo viva la captura OBD-II y el registro de evidencia con pantalla apagada o usando otra app en primer plano.
- **Soporte Android Auto validado con TDD (`R9`, `androidx.car.app:app:1.7.0`):** `JacCarAppService` en solo lectura con estado de enlace, DTCs activos, lecturas clave y combustible estimado R7 etiquetado como `ESTIMACIÓN/INFERENCIA`, categoría `IOT` compatible con modo desarrollador / Fuentes desconocidas.
- **Perfil de Captura Rápida de Falla (`R10`, `FAST_FAULT`):** monitoreo ligero que consulta exclusivamente el **Set Crítico de 8 PIDs** (`0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42`) para evitar saturar adaptadores ELM327 y prevenir pérdidas de enlace durante pruebas dinámicas (`#1 SAFE-001`).
- **Diagnóstico Diferencial en Informe PDF (`R11`, `WorkshopReport.kt` / `WorkshopPdf.kt`):** en *Diagnóstico principal* separa estrictamente **Hechos observables** de **Hipótesis** (corte de torque/potencia *brake override* `P0579`/`P2299` vs. apagado total de motor por caída de presión de riel `0x23` o tensión `0x42`) y recomienda automáticamente el Set Crítico de 8 PIDs cuando hay pérdida de enlace o datos no capturados en Mode 02.

**Anterior reciente (1.11.2–1.17.0):** hub **Señales** (`BLE / Tags` con categorías reales, GATT/DULT y flecha silenciosa; `Redes y RF` con canales Wi-Fi, 4G/5G MHz/SINR, GNSS y FFT magnético; `Seguridad` con Escudo defensivo), informe PDF C4A + bóveda privada + `.jacenc`, «Borrar y limpiar» (`ClearDtcGate`) y forense de paros (`EventForensics`).

| Pestaña / Modo | Qué hace cada función |
| --- | --- |
| **Estado** | Veredicto en una línea, códigos DTC tocables con detalle y mosaicos por sistema (Bien / Atención / Revisar). |
| **En vivo** | Medidores con mini-gráfico y **Modo Tablero manos libres** con recomendación de cambio calibrada 6M diésel (`2ª → 3ª`, `4ª → 3ª`). |
| **Android Auto** | Tablero horizontal 3×2 en la pantalla de la camioneta (refresco pausado 1,2 s), vista de detalle motor/DPF y vista de estado/DTC + «Marcar evento», en paralelo con Spotify y Waze. |
| **Pruebas** | Ralentí (60 s), calentamiento/termostato (10 min), aceleración libre (40 s), carga eléctrica (60 s) y registro en ruta o banco con forense de paros y CSV. |
| **Datos** | Todas las señales leídas con buscador, Mode 06, datos Mode 09 de la ECU, horas de motor y km desde borrado. |
| **Señales** | Hub de 3 vistas: **BLE / Tags** (categorías, GATT/DULT, apodos, flecha), **Redes y RF** (Wi-Fi MHz/canales/Evil Twin, 4G/5G MHz/SINR, GNSS, FFT magnético) y **Seguridad** (Escudo defensivo del vehículo + reporte JSON). |
| **Informe** | PDF con sello C4A / JSON / TXT / CSV / `.jacenc` cifrado, «Borrar y limpiar» y herramientas avanzadas. |

## Qué lee

| Servicio | Contenido |
| --- | --- |
| `01` | Todos los PID que declaran los mapas `0100`–`0180` (68 en la unidad probada, 82–84 señales). Hasta 6 PID por petición en CAN. |
| `0101` / `0141` | Monitores de emisiones, interpretados como diésel o gasolina según el bit de encendido por compresión. |
| `03` / `07` / `0A` / `02` | Códigos guardados, pendientes, permanentes y freeze frame. |
| `06` | Pruebas a bordo con valor, mínimo, máximo y aprobado/fallado. |
| `09` | Calibración (CALID), CVN, nombre de la ECU, rendimiento en uso y VIN cuando la ECU lo declara (anonimizable al exportar). |

La salud se evalúa con rangos genéricos de taller para un diésel common-rail. **No son especificaciones JAC** y no hay porcentaje global.

## Pruebas dinámicas

- **Estacionarias** (ralentí, calentamiento, aceleración libre, carga eléctrica): freno de mano, punto muerto, sin nadie delante.
- **Ruta / banco:** la app solo registra. Detecta corte de potencia con pedal pisado (SAFE-001), falta de turbo, caída de tensión, motor detenido y regeneración del DPF. El botón **MARCAR EVENTO** es para el acompañante.
- Con SAFE-001 abierto, la prueba en marcha va **solo en banco de rodillos o pista cerrada, con acompañante**. Nunca en la vía.

## Compilar

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK de salida en esta máquina: `%LOCALAPPDATA%\jac-diagnostic-lab-build\app\outputs\apk\debug\app-debug.apk`. Al publicar se copia a `dist\jac-diagnostic-lab-<versión>-debug.apk` junto a su `.sha256`.

| Pieza | Versión |
| --- | --- |
| JDK | 21 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| AGP / Kotlin / Gradle | 9.5.1 / 2.4.21 / 9.6.0 |
| Teléfono de trabajo | Xiaomi 2512BPNDAG, Android 17 |

## Sesión (solo lectura)

Orden típico: `ATZ` → `ATE0` → `ATL0` → `ATS0` → `ATH1` → `ATI` → `ATSP0` → `0100` (hasta 3 intentos mientras la ECU despierta) → `ATDP`/`ATDPN` → `ATRV` → DTC → freeze → mapas `0100`–`0180` → escaneo → `0101`/`0141` → `06` → `09`.

- `ATZ` reinicia el adaptador, **no** borra la ECU.
- `ATRV` es una estimación del adaptador. En la unidad probada marcó 2,3 V de más; la app usa el PID `0x42` de la ECU.
- PID `0x11` es la mariposa absoluta, no el pedal. En diésel abierta en ralentí es normal (DIAG-002).

## Evidencia y privacidad

- Sesiones, telemetría, trazas CSV y bóveda privada (`filesDir/vault/` con `MANIFEST.sha256.txt`) en almacenamiento privado de la app.
- Exportación con opción de anonimizar VIN y dirección Bluetooth, y empaquetado cifrado AES-256-GCM (`.jacenc`).
- La pestaña **Tags** solicita ubicación en ejecución (y `BLUETOOTH_SCAN` sin `neverForLocation`) únicamente para registrar avistamientos locales y triangular por GPS + RSSI en el teléfono; no usa APIs en la nube.
- Sin permiso de internet; `allowBackup=false`.
- Las lecturas reales de una unidad (VIN, PDF, sesiones) **no** se suben al repositorio. Ver [CONTRIBUTING.md](../CONTRIBUTING.md).

## Documentación

| Documento | Contenido |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | Capas, transporte, escaneo, pruebas |
| [docs/safety.md](docs/safety.md) | Gate de solo lectura, SAFE-001, pruebas en marcha |
| [docs/elm327-protocol.md](docs/elm327-protocol.md) | AT/OBD permitidos, ISO-TP |
| [docs/bluetooth-troubleshooting.md](docs/bluetooth-troubleshooting.md) | Fallos de enlace |
| [docs/jac-t8-diagnostic-plan.md](docs/jac-t8-diagnostic-plan.md) | Plan de diagnóstico |
| [docs/RELEASES.md](docs/RELEASES.md) | Historial de versiones y APK |
| [../CHANGELOG.md](../CHANGELOG.md) | Cambios por versión |
| [../README.md](../README.md) | Proceso de punta a punta |

## Limitaciones

- El clon ELM327 v2.1 corta las respuestas ISO-TP tras 4 tramas consecutivas, incluso con control de flujo estándar. Algunas pruebas Mode 06 y contadores `090B` llegan incompletos y se marcan como tales. Un adaptador de mejor calidad (vLinker, OBDLink) lo resuelve.
- En vivo, por BLE, se logran unas 2 actualizaciones por segundo de las señales rápidas.
- Sin catálogo J2012-DA completo: los títulos de códigos son genéricos SAE, no textos JAC.
- No hay PID genérico de interruptor de freno.
- ABS, BCM y transmisión propietarios: fuera de alcance.
- No recomienda cambiar piezas.
