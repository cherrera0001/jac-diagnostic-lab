# SPEC-024 — Bluetooth reliability / LinkPhase

**Estado:** en implementación (1.5.0)  
**AC:** AC-001…004

## Requisitos

1. Estados explícitos: `DISCONNECTED` → `TRANSPORT_CONNECTED` → `ELM_READY` → `ECU_RESPONDING` → `DIAGNOSTIC_READY`.  
2. GATT status 133: hasta 3 intentos con backoff; mensaje de recovery apunta a Classic si está emparejado.  
3. No enviar OBD antes de transporte válido.  
4. No presentar BT conectado como ECU respondiendo.

## Tests

- `TEST-BT-001` LinkPhase transitions tipadas.  
- `TEST-BT-002` política de reintento 133 (contador / backoff ms).
