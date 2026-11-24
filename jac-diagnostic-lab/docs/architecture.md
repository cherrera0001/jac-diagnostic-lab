# Arquitectura

La aplicación es un módulo Android. El protocolo OBD no importa clases de Android, así las pruebas JUnit corren en la JVM sin el teléfono.

```text
AppShell Compose (Estado / En vivo / Pruebas / Datos / Señales / Informe)
    ├ Herramientas avanzadas: Hoy / Monitor / Taller / Avisos / Copia
    ├ car/ (Android Auto · androidx.car.app:app:1.7.0, categoría POI, sin foco de audio)
    │   ├ JacCarAppService / JacCarSession (GridTemplate 3×2 · PaneTemplate · ListTemplate)
    │   ├ CarTileRenderer (mosaicos 160×160 cacheados)
    │   └ CarDashboardModel (puro JVM, comparte LabController y DrivingGuidanceEngine)
    ├ SignalsHubTab (pestaña Señales: BLE/Tags · Redes y RF · Seguridad defensiva)
    │   ├ TrackerScanner / HeadingSensor / GattInspector (GAP + DIS + Battery + IETF DULT)
    │   ├ RadioScanner (Wi-Fi 2.4/5/6 GHz, Celular 4G/5G, GNSS L1/L5, Magnetómetro FFT)
    │   ├ tracker/ (puro JVM): TrackerCore, BleAdParser, DeviceNaming, PositionEstimator, DirectionEstimator
    │   └ signals/ (puro JVM): RadioMath (EARFCN/NR-ARFCN/Shannon/FFT), SignalsCore, VehicleSecurityAudit
    └ DiagnosticViewModel
        LabController          sesión, permisos, evidencia, snapshots, bóveda privada
            BluetoothRepository
            ClassicElmTransport    RFCOMM SPP
            BleInspector           GATT, sin escritura hasta validar ATI
            ElmSession
                ReadOnlyGate / ObdCommandPolicy / ClearDtcGate (Mode 04 controlado)
                ElmParser
                DtcDecoder / PidDecoder / PidCatalog (J1979 multi-señal)
                ReadinessDecoder / Mode06Decoder / Mode09Decoder
                HealthAssessment / EvidenceEngine
                GuidedTests / EventForensics (paros y caídas de RPM en ruta)
                Present                 texto corto para la UI
                JacAnalysis / FaultIdentification
                WorkshopGuide + WorkshopPdf (sello C4A) + SecureArchive (.jacenc AES-256-GCM)
                Export JSON CSV TXT PDF GeoJSON GPX
```

## Capas nuevas en 1.12.0–1.19.0

| Pieza | Rol |
| --- | --- |
| `analysis/FuelEstimator.kt` | **SPEC-034 R7:** estimación de combustible sin mirar el tablero (`PID 0x2F` o respaldo `0x5E` / MAF `0x10` desde evento manual «Estanque lleno»), con invariante estricto `ESTIMACIÓN/INFERENCIA` (nunca medición directa) e integración con `DiagnosticInference` (R1) y Android Auto. |
| `bt/BackgroundCaptureNotification.kt` & `DiagnosticConnectionService` | **SPEC-034 R8:** `ForegroundService` (`connectedDevice`) con notificación persistente actualizada dinámicamente (enlace, modo activo, rondas/muestras, reconexión y combustible estimado) y `PendingIntent` a la app para captura continua OBD-II con pantalla apagada o en segundo plano. |
| `dynamic/GuidedTests.kt` (`FAST_FAULT`) | **SPEC-034 R10:** perfil ligero «Captura rápida de falla» restringido al Set Crítico de 8 PIDs (`0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42`) para evitar saturar el ELM327, con detección de caída de presión de riel `0x23` y tensión `0x42`. |
| `report/WorkshopReport.kt` (`DifferentialDiagnosis`) | **SPEC-034 R11:** Diagnóstico diferencial en *Diagnóstico principal* separando hechos observables de hipótesis (corte de torque *brake override* vs. apagado total por riel `0x23`/tensión `0x42`) y recomendación automática del Set Crítico de 8 PIDs ante pérdida de enlace o Mode 02 incompleto. |
| `car/JacCarAppService.kt` & `CarTileRenderer.kt` & `CarDashboardModel.kt` | **SPEC-034 R9:** integración oficial de **Android Auto** (categoría `IOT`, `minCarAppApiLevel = 1`): tablero horizontal 3×2 pausado (1,2 s), vista de detalle (`PaneTemplate`, incl. combustible estimado R7) y vista de estado/DTC (`ListTemplate`) con botón «Marcar evento». Comparte `LabController` sin duplicar sockets BT ni tomar foco de audio. |
| `analysis/DrivingGuidanceEngine.kt` | Inferencia de marcha 6M diésel calibrada (JAC T8 2.0T) con filtro de embrague/neutro rodando y recomendación explícita de marcha destino (`SUBIR A 3ª`, `BAJAR A 4ª`). |
| `signals/RadioMath.kt` | Conversión de canales Wi-Fi, LTE EARFCN y 5G NR-ARFCN (3GPP TS 38.104) a MHz, capacidad de Shannon–Hartley y FFT radix-2 con ventana de Hann. |
| `signals/SignalsCore.kt` & `RadioScanner.kt` | Escáner pasivo de Wi-Fi, celdas 4G/5G/3G/2G, satélites GNSS y magnetómetro FFT + motor de auditoría defensiva (`VehicleSecurityAudit`: OBD expuesto, alerta `0x41`, downgrade 2G, Evil Twin Wi-Fi, anomalía magnética). |
| `tracker/BleAdParser.kt` | Parser puro JVM de `GAP Appearance` (`AD 0x19`), bloques múltiples TLV Apple Continuity (`0x004C`) y respuestas IETF DULT (`0x0804`/`0x0805`/`0x0806`). |
| `tracker/TrackerCore.kt` | Clasificador de firmas BLE públicas + categorización real (`DeviceCategory`: Rastreador, Audio, Reloj/Pulsera, Teléfono/PC, TV/Hogar, Periférico, Auto/OBD, Baliza, BLE), libro sincronizado (`SightingBook`), fusión ADV + SCAN_RSP y exportación GeoJSON/GPX + `geo:`. |
| `tracker/GattInspector.kt` | Consulta activa opcional (solo al pulsar «Identificar»): lee GAP (`0x2A00`/`0x2A01`), DIS (`0x180A`), Batería (`0x180F`) y el servicio estándar IETF DULT (`15190001-…`) para fabricante, modelo y categoría. |
| `tracker/DeviceNaming.kt` | Resuelve apodos locales, identidad GATT/DULT, nombres BLE reales e inferencia Bluetooth SIG / subtipo Apple / servicios para dispositivos sin nombre anunciado. |
| `tracker/PositionEstimator.kt` | Calibración espacial silenciosa (GPS + RSSI) en segundo plano con agrupación espacial, varianza residual (`σ²`), `R²`/`RMS` y rechazo de recorridos ambiguos (línea recta) o ruido puro. |
| `tracker/DirectionEstimator.kt` | Rumbo orientativo por giro con brújula (`HeadingSensor`, `TYPE_ROTATION_VECTOR`) exigiendo contraste mínimo de 5 dB. |
| `security/ClearDtcGate.kt` | Compuerta para «Borrar y limpiar» (Mode `04` de un solo uso con respaldo previo y relectura 03/07/0A). |
| `dynamic/EventForensics.kt` | Detección de paros / caídas de RPM con pedal pisado y evaluación de 5 hipótesis rivales. |
| `report/WorkshopPdf.kt` & `evidence/SecureArchive.kt` | Generación PDF unificada con sello vectorial `C4A · c4a.cl`, bóveda privada (`filesDir/vault/`) y paquete cifrado `.jacenc`. |

## Capas nuevas en 1.8.0

| Pieza | Rol |
| --- | --- |
| `PidCatalog` | Decodifica cada PID J1979 conocido en una o varias señales (`70.RA`, `7A.DP`, `8B.REGEN`…), respetando los bits de soporte y los centinelas sin dato. |
| `ElmSession.readPidsRaw` | Agrupa hasta 6 PID por petición en CAN y vuelve a PID a PID si la ECU no lo acepta. |
| `LabController.fullScanInternal` | Mapas → escaneo → monitores → Mode 06 (con control de flujo si hay truncado) → Mode 09 → salud. |
| `HealthAssessment` | Reglas genéricas de taller. Devuelve `HealthItem` con estado, detalle y evidencia. |
| `GuidedTests` | Catálogo de pruebas, muestreo, análisis y detección de eventos en ruta. En ruta las señales rápidas se leen cada ronda y las lentas 1 de cada 4. |
| `AppShell` / `Present` | Interfaz nueva. `Present` no tiene lógica de diagnóstico, solo formato. |

## Capas de 1.1.0

| Pieza | Rol |
| --- | --- |
| `WorkshopGuide` | Pasos de taller + canales del monitor (`MonitorChannel`) |
| `Gauges.kt` / `LiveGaugeCard` | Medidores arco / barra / termómetro / aviso ATRV |
| `startOrderedMonitor()` | Polling en el orden fijo de taller |
| `captureSnapshot(antes\|después)` | Baseline y comparación de PID + códigos |
| `StreetGuide` | Ocho pasos del dueño (pestaña Hoy) |
| `SecurityGuide` | Nueve validaciones (sin %) |

`LabController` vive en la `Application`. Girar la pantalla no corta el socket. Un servicio en primer plano de tipo `connectedDevice` intenta mantener el proceso mientras la sesión está abierta.

Hay una sola solicitud activa: el transporte usa un mutex. El registro de sensores espera a que cada PID termine antes del siguiente.

La secuencia automática (`autodiag`) es solo lectura. Prefiere `OBDII` clásico; si no identifica, inspecciona `OBDBLE`. Tras el escaneo **vuelve a fusionar** dispositivos emparejados (`OBDII`/`OBDBLE`) para no perder el dongle si el scan no lo nombró. No presenta datos simulados en esa ruta.

Transportes tipados (`TransportKind`):

- `ProductionVehicle` — RFCOMM/GATT reales; único aceptado por `LabController.bindProductionTransport`.
- `ResearchLab` — `ResearchLabTransport` / `FakeObdTransport` (tests y lab). Rechazado en producción (`TransportIsolation`, LAB-CAN-001).

La evidencia se escribe tras cada tramo en almacenamiento privado (`filesDir/vault/` con `MANIFEST.sha256.txt`). La única operación de escritura OBD es «Borrar y limpiar» (Mode `04` detrás de `ClearDtcGate`, con respaldo privado previo y relectura 03/07/0A).
