# JAC Diagnostic Lab

Aplicación Android de diagnóstico **solo lectura** para una **JAC T8** (unidad declarada 2023) con adaptador **ELM327 Mini Bluetooth** (`OBDII` clásico / `OBDBLE` BLE).

**Versión actual: 1.5.0** (`versionCode` 16) — 12-11-2026.

Auditoría Sprint 0: [docs/audits/current-codebase-assessment.md](docs/audits/current-codebase-assessment.md).

No borra códigos (no Mode 04), no reprograma la ECU y no ejecuta actuadores. Una conexión exitosa **no** autoriza a circular.

## Descargar la APK (dueño o mecánico)

La APK instalable está en el **release de GitHub** del hito **v1.1.0** (los milestones de GitHub no alojan binarios; el archivo vive en Releases):

| Qué | Dónde |
| --- | --- |
| Release + APK | https://github.com/cherrera0001/jac-diagnostic-lab/releases/tag/v1.2.0 |
| Archivo | `jac-diagnostic-lab-1.2.0-debug.apk` |
| Copia en el repo | `dist/jac-diagnostic-lab-1.2.0-debug.apk` |

### Limitación real (léala antes de instalar)

- Sin un **ELM327** (o compatible) en el puerto OBD-II **no hay lectura nueva**.
- El cuello de botella habitual es el enlace **Bluetooth**: BLE GATT (código **133** si falla la radio) o RFCOMM clásico ocupado por otra app.
- Cierre Torque, Car Scanner, OBD Auto Doctor, etc. antes de usar esta app.
- Contacto en **ON**, camioneta **estacionada**. No pruebe el corte de potencia en la calle.
- Esta build es **debug** firmada para laboratorio; no es una publicación Play Store.

### Instalar en el teléfono

1. Descargue `jac-diagnostic-lab-1.1.0-debug.apk` desde el release.
2. En Android permita instalar desde esa fuente (archivo / navegador).
3. O por USB:

```text
adb install -r jac-diagnostic-lab-1.1.0-debug.apk
```

## Qué hay de nuevo en 1.1.0

| Pieza | Para qué |
| --- | --- |
| Pestaña **Monitor** | Medidores vivos en orden fijo: RPM → velocidad → refrigerante → carga → mariposa `0x11` → MAP → APP `0x49/4A/4B` si el mapa las declara → ATRV (solo dongle). |
| Capturas **ANTES / DESPUÉS** | Baseline y comparación con Δ para controlar un ajuste de taller. |
| Pestaña **Taller** | Ciclo de 7 pasos: leer sin borrar → freeze → baseline → medir freno/APP → ajuste → monitor+PDF → decisión de circulación. |
| PDF del informe | Incluye el ciclo de taller y el orden del monitor. |
| Autodiag | Reinyecta `OBDBLE`/`OBDII` emparejados tras el escaneo (desde 1.0.1). |

## Pantallas

| Pestaña | Para qué sirve | Qué no hace |
| --- | --- | --- |
| **Hoy** | Ocho pasos del dueño, lectura en palabras, PDF + huella | No recomienda piezas |
| **Monitor** | Medidores ordenados + capturas antes/después + enlace | No inventa PID de freno |
| **Taller** | Guía del mecánico y ficha de falla | No borra DTC ni prueba el corte en marcha |
| **Avisos** | Códigos, análisis y validaciones de seguridad | No da un % de “seguridad del vehículo” |
| **Copia** | PDF / JSON / TXT / CSV (solo si hay contenido) | No sube nada a internet |

## Flujo recomendado

### Dueño

1. Camioneta quieta, contacto ON, ELM enchufado, otras apps OBD cerradas.
2. **Hoy** → permitir Bluetooth → **Leer la camioneta**.
3. Leer el resultado. Guardar PDF (paso 7 o pestaña **Copia**).
4. Si hay relato de **corte de potencia al frenar**: no circular; llevar el PDF al servicio JAC (**SAFE-001**).

### Mecánico

1. **Taller** paso a paso (o escáner JAC en paralelo).
2. **Monitor** → **Captura ANTES** → PDF.
3. Medir interruptor de freno y pistas APP con multímetro (no usar 750 mV de la N56).
4. Tras el ajuste: misma postura → **Captura DESPUÉS** → comparar Δ y DTC → nuevo PDF.
5. Si el síntoma de corte sigue: no entregar para circular.

## Entorno comprobado (11-11-2026)

| Pieza | Versión |
| --- | --- |
| JDK | 21.0.13 |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |
| AGP | 9.5.1 |
| Kotlin | 2.4.21 |
| Gradle | 9.6.0 |
| Compose BOM | 2026.09.00 |
| Teléfono de trabajo | Xiaomi 2512BPNDAG, Android 17 |

## Compilar

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK de salida (ruta de build local de esta máquina):

```text
%LOCALAPPDATA%\jac-diagnostic-lab-build\app\outputs\apk\debug\app-debug.apk
```

Copiar a `dist\jac-diagnostic-lab-1.1.0-debug.apk` al publicar.

## Conectar OBDII / OBDBLE

- Empareje desde Ajustes de Bluetooth. El PIN lo pide Android; esta app no lo guarda.
- `OBDII`: RFCOMM SPP, reintento y canal 1 de respaldo.
- `OBDBLE`: GATT; solo escribe tras elegir WRITE + NOTIFY y validar `ATI`.
- Si el escaneo no nombra el dongle, el autodiag **reinyecta** los emparejados conocidos.
- GATT **133** = fallo de radio/enlace, no de la UI. Reinsertar ELM, ON, cerrar otras apps, reintentar.

Detalle: [docs/bluetooth-troubleshooting.md](docs/bluetooth-troubleshooting.md).

## Sesión (solo lectura)

Orden típico: `ATZ` → `ATE0` → `ATL0` → `ATS0` → `ATH1` → `ATI` → `ATSP0` → `ATDP`/`ATDPN` → `ATRV` → `0100` → DTC `03`/`07`/`0A` → freeze `02` → PID y monitor.

- `ATZ` reinicia el adaptador, **no** borra la ECU.
- `ATI` imitable; no prueba originalidad.
- `ATRV` ≠ batería (hace falta multímetro).
- PID `0x11` = **mariposa absoluta**, no el pedal.
- VIN solo con autorización explícita y soporte `0900`/`0902`.

## Evidencia y privacidad

- Sesiones en almacenamiento privado de la app.
- Exportación JSON/CSV/TXT/PDF con opción de anonimizar.
- Sin permiso de internet; `allowBackup=false`.
- Las lecturas reales (VIN, PDF de unidad) **no** se versionan en git.

## Documentación

| Documento | Contenido |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | Capas, transporte, monitor |
| [docs/safety.md](docs/safety.md) | Gate de solo lectura, SAFE-001 |
| [docs/elm327-protocol.md](docs/elm327-protocol.md) | AT/OBD permitidos |
| [docs/bluetooth-troubleshooting.md](docs/bluetooth-troubleshooting.md) | Fallos de enlace |
| [docs/jac-t8-diagnostic-plan.md](docs/jac-t8-diagnostic-plan.md) | Plan de diagnóstico |
| [docs/RELEASES.md](docs/RELEASES.md) | Historial de versiones y APK |
| [../README.md](../README.md) | Proceso de punta a punta |
| [../c4a-automotive-security](../c4a-automotive-security) | TARA / hallazgos (no habla al camión) |

## Limitaciones

- Sin catálogo J2012-DA completo: no se inventa texto de un DTC desconocido.
- EPC ≠ MIL de `0101`.
- No hay PID genérico de interruptor de freno.
- ABS/BCM/transmisión propietarios: fuera de alcance.
- No recomienda cambiar piezas.
- El lab adversarial (CAN virtual) **nunca** apunta al T8 real.
