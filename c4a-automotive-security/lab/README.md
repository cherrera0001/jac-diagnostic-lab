# Laboratorio aislado (Niveles B/C)

**PROHIBIDO** conectar estos simuladores al vehículo real.

| Carpeta | Rol | Estado |
| --- | --- | --- |
| `elm327-emulator/` | Respuestas AT/OBD scriptables | Fixtures + `ResearchLabTransport` |
| `can-simulator/` | Bus / vcan simulado | Política + aislamiento documentado |
| `ecu-simulator/` | ECU ISO-TP mínima | Pendiente |
| `gateway-policy/` | Filtrado OBD→CAN (diseño) | Pendiente |
| `security-test-cases/` | Casos adversariales | Pendiente |

## Separación de transportes (LAB-CAN-001)

En `jac-diagnostic-lab`:

- `TransportKind.ProductionVehicle` — RFCOMM/GATT reales (`ClassicElmTransport`, `BleInspector`).
- `TransportKind.ResearchLab` — `ResearchLabTransport` / `FakeObdTransport` en tests.
- `LabController.bindProductionTransport` llama a `TransportIsolation.assertProductionOnly`.

Criterio de aislamiento: el código de lab no escribe a sockets RFCOMM/GATT del teléfono conectado al vehículo.
