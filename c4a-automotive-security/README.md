# C4A Automotive Security Lab

Auditoría integral, diagnóstico y hardening de **JAC T8**, basada en evidencia.

- **Metodología de referencia:** ISO/SAE 21434 (TARA), STRIDE, SDD/TDD, Evidence-Driven Security.
- **No se declara cumplimiento formal** de ISO/SAE 21434 solo por usarla como guía.
- **Niveles:** A = vehículo real solo lectura · B = adaptador aislado · C = laboratorio simulado.

## Relación con JAC Diagnostic Lab

La aplicación Android operativa vive en [`../jac-diagnostic-lab`](../jac-diagnostic-lab).  
Este directorio concentra investigación, TARA, lab aislado, hallazgos y hardening.

Módulos en la APK (`app.jac.diagnosticlab.security`):

- `BluetoothSecurityAuditor`
- `Elm327SecurityAnalyzer`
- `ObdCommandPolicy` (vía `ReadOnlyGate`)
- `VehicleFaultCorrelation`
- `ThreatModelEngine` / `HardeningAdvisor` / `SecurityAssessment`
- Pestaña **Seguridad** en la UI

## Estado verificado (2026-11-11)

| Hecho | Evidencia |
|---|---|
| APK instalada en Xiaomi 2512BPNDAG | `pm path app.jac.diagnosticlab` |
| Enlace BLE OBDBLE + GATT `FFF1`/`FFF2` | sesión `2026-11-11T2136-8803` |
| `ATI` → ELM327 v2.1 (posible clon) | misma sesión |
| Protocolo CAN 11/500 (`A6`) | `ATDP` / `ATDPN` |
| ECU responde `0100` | trama `7E8…4100…` |
| DTC stored `P0579`, `P2299` | modo `03` |
| No se envió servicio `04` | política + transcripción |
| Gateway independiente | **DOCUMENTAL de línea**; **UNKNOWN en esta unidad** |
| Año / ECU FW / multimedia / telemática | **DESCONOCIDO** |

Una conexión exitosa **no** prueba vulnerabilidad ni aptitud para circular.  
Los DTC **no** demuestran un ataque.

## Arranque rápido

1. Abrir y construir `jac-diagnostic-lab` (ver su README).
2. Leer `research/vehicle-inventory/inventory.json`.
3. Leer hallazgos en `reports/findings/`.
4. TARA: `docs/tara/TARA-2026-11-11.md`.
5. Lab adversarial: solo bajo `lab/` (nunca contra el vehículo).

## Estructura

```text
c4a-automotive-security/
  research/          inventario y fuentes
  docs/              arquitectura, TARA, hardening, safety, hitos
  lab/               simuladores Nivel B/C (esqueleto)
  reports/findings/  C4A-AUTO-2026-XXX
  android/           puntero al app real
```

## Próximo paso verificable

1. Confirmar año/VIN de la unidad (con autorización).  
2. Prueba Nivel A con **segundo teléfono propio** (T-001), vehículo estacionado.  
3. Hito 5: emulador ELM/ECU en `lab/` con aislamiento comprobable.
