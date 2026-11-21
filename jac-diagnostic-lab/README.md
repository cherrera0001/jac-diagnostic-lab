# JAC Diagnostic Lab

Aplicación Android de diagnóstico **solo lectura** para una **JAC T8** (unidad declarada 2023, diésel) con adaptador **ELM327 Mini Bluetooth** (`OBDII` clásico / `OBDBLE` BLE).

**Versión actual: 1.11.1** (`versionCode` 26), 21-11-2026, **validada en la unidad real** (ECU Delphi DCM6.2 confirmada; ver `research/validation/2026-11-18-unidad-real.md`). Informe de taller PDF profesional, detalle tocable en Estado, prueba «Freno contra acelerador», reconexión BLE automática. APK: [release v1.11.1](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.11.1).

Versión 1.10.0 (`versionCode` 23): Informe para el taller generado después de leer todo, con detalle por código, cuadro congelado completo y registro de comunicación (SPEC-033). APK: [release v1.10.0](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.10.0) · `dist/jac-diagnostic-lab-1.10.0-debug.apk`.

Versión 1.9.0 (`versionCode` 22): Identificación de ECU por Mode 09 (raw por ECU, VIN directo), perfil JAC T8 por evidencia observable con fuentes y niveles de confianza (SPEC-031). La versión en uso aparece en el pie de todas las pantallas y en cada PDF/JSON/TXT. APK: [release v1.9.0](https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.9.0) · copia en `dist/jac-diagnostic-lab-1.9.0-debug.apk` + `.sha256`.

Versión anterior publicada: 1.8.0 (`versionCode` 21). Rediseño completo, escaneo de todos los PID que declara la ECU, salud por sistema y pruebas dinámicas.

No borra códigos (no Mode 04), no reprograma la ECU y no ejecuta actuadores. Una conexión exitosa **no** autoriza a circular.

Licencia: [Apache-2.0](../LICENSE). Uso, aviso de seguridad y datos que no se suben: [NOTICE](../NOTICE) y [CONTRIBUTING.md](../CONTRIBUTING.md).

## Descargar la APK

| Qué | Dónde |
| --- | --- |
| Release + APK | https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.11.1 |
| Archivo | `jac-diagnostic-lab-1.11.1-debug.apk` (con su `.sha256`) |
| Copia en el repo | `dist/jac-diagnostic-lab-1.11.1-debug.apk` |
| Milestone | «v1.11.0 — Validada en la unidad real» |

### Antes de instalar

- Sin un **ELM327** (o compatible) en el puerto OBD-II no hay lectura.
- Cierre Torque, Car Scanner, OBD Auto Doctor, etc. El adaptador atiende a un solo cliente.
- Motor encendido o contacto en **ON**, camioneta **estacionada**.
- Es una build **debug** firmada para laboratorio, no una publicación de Play Store.

### Instalar

1. Descargue la APK del release y compruebe la huella: `sha256sum -c jac-diagnostic-lab-1.11.1-debug.apk.sha256`.
2. En Android permita instalar desde esa fuente (archivos o navegador).
3. O por USB:

```text
adb install -r jac-diagnostic-lab-1.11.1-debug.apk
```

## Pantallas (1.8.0, ampliadas en 1.9.0)

**Nuevo en 1.10.0:** en Informe, tarjeta «Informe para el taller» («Leer todo y generar informe»): PDF técnico + .sha256 + JSON completo, generado al terminar el escaneo. Cabecera y pestañas ajustadas al teléfono.

**Nuevo en 1.9.0:** pie con la versión en uso en todas las pantallas; en Herramientas avanzadas → Capacidad, tarjeta «Perfil del vehículo» con «Identificar ECU (Mode 09)», respuestas por ECU, campo para la etiqueta física de la ECU, estado del perfil (IDENTIFIED / SUPPLIER_ONLY / AMBIGUOUS / PARTIAL), chequeo del año del VIN y cobertura del catálogo. «Consultar VIN» ya no pide autorización.

| Pestaña | Para qué sirve |
| --- | --- |
| **Estado** | Veredicto en una línea, códigos con título corto y mosaicos por sistema (Bien / Atención / Revisar). Un toque abre la evidencia. |
| **En vivo** | Medidores grandes con mini-gráfico: RPM, velocidad, refrigerante, tensión, carga, pedal, turbo, riel, torque, aire. |
| **Pruebas** | Ralentí (60 s), calentamiento/termostato (10 min), aceleración libre (40 s), carga eléctrica (60 s) y registro en ruta o banco. Resultado con puntos de color y CSV. |
| **Datos** | Todas las señales leídas, agrupadas y con buscador. Mode 06, datos de la ECU, horas de motor, km desde el borrado. |
| **Informe** | PDF / JSON / TXT / CSV. Las pantallas anteriores (Hoy, Monitor, Taller, Avisos) quedan en «Herramientas avanzadas». |

## Qué lee

| Servicio | Contenido |
| --- | --- |
| `01` | Todos los PID que declaran los mapas `0100`–`0180` (68 en la unidad probada, 82–84 señales). Hasta 6 PID por petición en CAN. |
| `0101` / `0141` | Monitores de emisiones, interpretados como diésel o gasolina según el bit de encendido por compresión. |
| `03` / `07` / `0A` / `02` | Códigos guardados, pendientes, permanentes y freeze frame. |
| `06` | Pruebas a bordo con valor, mínimo, máximo y aprobado/fallado. |
| `09` | Calibración (CALID), CVN, nombre de la ECU y rendimiento en uso. VIN solo con autorización. |

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

- Sesiones, telemetría y trazas CSV en almacenamiento privado de la app.
- Exportación con opción de anonimizar VIN y dirección Bluetooth.
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
