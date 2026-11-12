# TARA — unidad declarada JAC T8 2023

**Estado:** cerrado como TARA de unidad con límites de confianza explícitos (sin CVSS de vehículo inventado)  
**Issue:** [#10 SEC-TARA-001](https://github.com/cherrera0001/jac-diagnostic-lab/issues/10)  
**Fecha:** 2026-11-12  
**Unidad:** JAC T8, año **declarado** 2023 (no leído de la ECU)  
**Complemento BT:** [SEC-BT-001](../bluetooth/SEC-BT-001-caracterizacion.md)

## Activos

| Activo | Estado |
|---|---|
| ECU motor (respuesta 7E8) | VERIFIED en sesiones OBD |
| Puerto OBD-II + ELM327 | VERIFIED_IMITABLE (ATI) |
| Señal freno / APP | UNKNOWN en esta app (sin PID de freno) |
| Gateway / SecOC | UNKNOWN para esta unidad |
| Firmware ECU | UNKNOWN |

## Escenarios relevantes al síntoma

| ID | Escenario | Evidencia | Conclusión |
|---|---|---|---|
| S1 | Desacuerdo freno/acelerador (técnico) | P0579, P2299, relato de corte al frenar | HIPÓTESIS abierta — taller |
| S2 | Ataque por Bluetooth al dongle | BLE WRITE tras emparejar | Observación de superficie; no explica DTC |
| S3 | Inyección CAN / firmware | Ningún byte | NOT_TESTED / no afirmado |

## Controles

- Allowlist OBD en la APK (no en el dongle).
- Mode 04 bloqueado.
- Aviso SAFE-001: no circular con corte al frenar.
- Lab adversarial: nunca contra el T8.

## Riesgo residual

El síntoma de pérdida de potencia al frenar es **riesgo de seguridad física**. Esta TARA no lo cierra. Cierre: escáner JAC + medición de pedales, issue SAFE-001.
