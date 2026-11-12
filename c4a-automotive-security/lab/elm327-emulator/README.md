# ELM327 emulator (Nivel B/C)

Este directorio es el **laboratorio aislado**. No debe hablar con la camioneta.

Implementación mínima actual: `FakeObdTransport` y fixtures en las pruebas unitarias de `jac-diagnostic-lab` (`ProtocolTest`, `ObdCommandPolicyTest`).

Próximos pasos (solo simulador):

- Respuestas malformadas y rate-limit.
- Negación de autorización simulada.
- Gateway policy simulada.

**Prohibido** apuntar estas pruebas al puerto OBD real.
