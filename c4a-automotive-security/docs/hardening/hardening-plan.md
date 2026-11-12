# Plan de hardening (reversible)

## Capa 1 — Adaptador

1. Retirar ELM327 cuando no se diagnostique.
2. Inventariar vínculos OBDII/OBDBLE.
3. No usar ATI como prueba de autenticidad.
4. Preferir equipos con autenticación y soporte documentados (evaluación futura).

## Capa 2 — Android

- Permisos mínimos CONNECT/SCAN.
- Sin secretos en logs.
- Export anonimizable.
- Offline por defecto.

## Capa 3 — App OBD

- `ObdCommandPolicy` allowlist.
- Bloqueo `04` / UDS escritura / cabeceras.
- Timeouts y una solicitud activa.

## Capa 4 — Vehículo

- Año/firmware/gateway: **DESCONOCIDO**.
- Consultar canales oficiales JAC; no flashear ni instalar gateways activos sin validación profesional.

## Capa 5 — Operación

- Conservar evidencias con hash cuando se exporten.
- Tras mantenimiento, repetir inventario BT.
- EPC/DTC: taller / escáner JAC; no circular solo por sesión OBD OK.
