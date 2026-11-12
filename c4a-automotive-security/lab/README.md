# Laboratorio aislado (Niveles B/C)

**PROHIBIDO** conectar estos simuladores al vehículo real.

Estructura prevista:

| Carpeta | Rol |
|---|---|
| `elm327-emulator/` | Respuestas AT/OBD scriptables |
| `ecu-simulator/` | ECU ISO-TP mínima |
| `can-simulator/` | Bus simulado |
| `gateway-policy/` | Políticas de filtrado OBD→CAN (diseño) |
| `security-test-cases/` | Casos adversariales reproducibles |

Estado 2026-11-11: esqueleto + política de aislamiento documentada.
Implementación de emuladores: Hito 5 (pendiente).

Criterio de aislamiento: el código de lab no importa el transporte Bluetooth de `jac-diagnostic-lab` ni escribe a sockets RFCOMM/GATT del teléfono conectado al vehículo.
