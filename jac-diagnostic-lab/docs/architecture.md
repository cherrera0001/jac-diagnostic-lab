# Arquitectura

La aplicación es un módulo Android. El protocolo OBD no importa clases de Android, así las pruebas JUnit corren en la JVM sin el teléfono.

```text
AppShell Compose (Estado / En vivo / Pruebas / Datos / Informe)
    └ Herramientas avanzadas: Hoy / Monitor / Taller / Avisos / Copia
    DiagnosticViewModel
        LabController          sesión, permisos, evidencia, snapshots
            BluetoothRepository
            ClassicElmTransport    RFCOMM SPP
            BleInspector           GATT, sin escritura hasta validar ATI
            ElmSession
                ReadOnlyGate / ObdCommandPolicy
                ElmParser
                DtcDecoder / PidDecoder / PidCatalog (J1979 multi-señal)
                ReadinessDecoder / Mode06Decoder / Mode09Decoder
                HealthAssessment        reglas de salud por sistema
                GuidedTests             pruebas guiadas, eventos en ruta, CSV
                Present                 texto corto para la UI
                JacAnalysis / FaultIdentification
                WorkshopGuide + monitor ordenado
                Export JSON CSV TXT PDF
```

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

La evidencia se escribe tras cada tramo en almacenamiento privado. No hay borrado de DTC ni de sesiones desde la UI.
