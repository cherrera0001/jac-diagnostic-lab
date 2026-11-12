# Changelog

## v0.2.0 — 2026-11-11

Corte de la evaluación con evidencia de la JAC T8.

- Sesión real `2026-11-11T2136-8803`: OBDBLE, `ELM327 v2.1`, CAN `A6`, DTC almacenados P0579 y P2299.
- Allowlist de solo lectura. El servicio `04` no se envía.
- Dashboard Seguridad, TARA T-001 a T-010 y hallazgos C4A-AUTO-2026-001 a 006.
- Informe único: `c4a-automotive-security/reports/INFORME-2026-11-11.md`.
- README de entrada con el proceso, los diagramas y el uso.
- Pruebas: `ProtocolTest` 15, `SecurityPolicyTest` 6, `ObdCommandPolicyTest` 4. Cero fallos.
- APK debug validada en `jac-diagnostic-lab/dist/`. SHA-256 `A37FEDF39F488744FB71986D54F9794A3B5C4234B8780A4C46E295A98B21A4C2`.

## v0.1.0 — 2026-11-11

Primera APK de diagnóstico de solo lectura: enlace Classic y BLE, sesión ELM, lectura de DTC y exportación local.
