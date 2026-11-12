# Arquitectura

La aplicación es un módulo Android. El protocolo OBD no importa clases de Android, así las pruebas JUnit corren en la JVM sin el teléfono.

```text
Pantallas Compose (Hoy / Monitor / Taller / Avisos / Copia)
    DiagnosticViewModel
        LabController          sesión, permisos, evidencia, snapshots
            BluetoothRepository
            ClassicElmTransport    RFCOMM SPP
            BleInspector           GATT, sin escritura hasta validar ATI
            ElmSession
                ReadOnlyGate / ObdCommandPolicy
                ElmParser
                DtcDecoder / PidDecoder
                JacAnalysis / FaultIdentification
                WorkshopGuide + monitor ordenado
                Export JSON CSV TXT PDF
```

## Capas relevantes en 1.1.0

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
