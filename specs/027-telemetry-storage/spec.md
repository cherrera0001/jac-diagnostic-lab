# SPEC-027 — Telemetry storage (AC-009 / AC-010)

**Estado:** en implementación (1.5.0)

## Requisitos

1. Append-only de mediciones por sesión (JSONL).  
2. Índice de sesiones listable.  
3. Recuperar historial sin mezclar con sesión viva.  
4. Sin cloud; sin VIN en export por defecto.

## Tests

- `TEST-STORE-001` append + count.  
- `TEST-STORE-002` listSessions ordenado por recencia.
