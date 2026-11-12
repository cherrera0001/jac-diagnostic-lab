# CAN / vcan simulator (Nivel C)

**PROHIBIDO** conectar este simulador al puerto OBD del JAC T8.

## Aislamiento

| Capa | Regla |
| --- | --- |
| Código | No importa `ClassicElmTransport` / `BleInspector` |
| Transporte | Solo `TransportKind.ResearchLab` (`ResearchLabTransport`) |
| Hardware | Sin RFCOMM/GATT hacia el teléfono emparejado al camión |
| Host | vcan u otro bus virtual en máquina de laboratorio |

## Estado

- Esqueleto + política (2026-11-12).
- Respuestas OBD scriptadas: `ResearchLabTransport` en `jac-diagnostic-lab`.
- Prueba de aislamiento: `TransportIsolationTest`.

## Uso previsto (futuro)

1. Levantar vcan en el host de lab.
2. Alimentar tramas ISO-TP simuladas.
3. Correr casos adversariales **solo** contra el simulador.

Criterio de aceptación LAB-CAN-001: la app de producción no puede enlazar un transporte ResearchLab al flujo autodiag/UI del vehículo.
