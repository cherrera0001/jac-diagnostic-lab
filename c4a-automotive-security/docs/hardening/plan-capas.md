# Plan de hardening por capas

Prioridad: medidas reversibles, verificables, sin alterar ABS/inmovilizador/firmware.

## Capa 1 — Adaptador

1. Retirar ELM327 cuando no se diagnostique.  
2. Inventariar vínculos OBDII/OBDBLE.  
3. No dejar dongle permanentemente energizado (drena pin 16).  
4. Evaluar reemplazo por dispositivo con autenticación y soporte documentados.  
5. PIN: no forzar desde la app; pendiente prueba manual de cambio legítimo (Nivel B).

## Capa 2 — Android

- Permisos mínimos (ya en APK).  
- Sin internet / backup off (ya).  
- Anonimizar exports (ya).  
- Pendiente: integridad APK / Keystore si se añaden secretos (hoy no hay secretos de dongle).

## Capa 3 — Aplicación OBD

- `ObdCommandPolicy` allowlist (ya + tests).  
- Timeouts / kinds de respuesta (ya).  
- Dashboard Seguridad (ya).  
- Pendiente: export del informe de seguridad junto a la sesión.

## Capa 4 — Vehículo

- Confirmar año/VIN.  
- Revisar boletines JAC oficiales.  
- No flashear ECU ni instalar filtros CAN activos sin validación profesional.

## Capa 5 — Operación

- Conservar sesiones crudas.  
- No borrar DTC hasta que el taller lo decida.  
- Síntomas de EPC y códigos leídos → línea de falla técnica, no ciber, hasta prueba en contrario. El detalle queda en el informe local.
