# SEC-BT-001 — Caracterización Bluetooth OBDII / OBDBLE

**Issue:** [#9](https://github.com/cherrera0001/jac-diagnostic-lab/issues/9)  
**Fecha:** 2026-11-12  
**Alcance:** Nivel A (solo lectura en vehículo estacionado) y observación de superficie. **Sin** fuzz, DoS ni escritura fuera de allowlist hacia el T8.

## Superficies observadas

| Nombre | Tipo | Rol en esta app |
| --- | --- | --- |
| OBDII | BR/EDR (RFCOMM SPP) | `ClassicElmTransport` → `TransportKind.ProductionVehicle` |
| OBDBLE | BLE GATT | `BleInspector` → `TransportKind.ProductionVehicle` |

## Nivel A (evidencia de sesión)

- Emparejamiento: sistema Android (PIN no capturado por la app).
- Tras conectar BLE: características WRITE + NOTIFY; validación únicamente con `ATI`.
- Sesión productiva conocida: ATI `ELM327 v2.1` (imitable), protocolo A6 CAN 11/500, ECU `7E8`.
- Fallo típico de radio: GATT **133** (establecimiento). No se interpreta como vulnerabilidad de la ECU.
- Allowlist OBD en la APK (`ObdCommandPolicy` / `ReadOnlyGate`). Mode `04` denegado.
- El dongle **no** aplica la allowlist: otra app del teléfono podría hablar distinto.

## Nivel B (adaptador / lab, sin camión)

- `ResearchLabTransport` + fixtures de prueba (`TransportKind.ResearchLab`).
- `LabController` rechaza ResearchLab en la ruta de producción (`TransportIsolation`).
- Emuladores bajo `c4a-automotive-security/lab/` — prohibido apuntar al T8.

## Qué no se hizo (a propósito)

- No fuzz de GATT/RFCOMM contra el vehículo.
- No DoS de bus CAN.
- No captura de tráfico de otras apps.
- No se afirma que el enlace BLE esté cifrado de extremo a extremo con la ECU.

## Conclusión

La superficie Bluetooth del adaptador es **real y explotable en el sentido de “quien controla el teléfono puede enviar AT/OBD al dongle”**. Eso no demuestra un ataque que explique P0579/P2299. Mitigación operativa: retirar el ELM cuando no se lea; no dejar apps OBD desconocidas con el dongle enchufado; seguir SAFE-001.
