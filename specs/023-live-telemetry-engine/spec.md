# SPEC-023 — VehicleTelemetryEngine

**Estado:** en implementación  
**Depende de:** SPEC-022  
**AC:** AC-004

## Requisitos

1. Un solo comando activo hacia el ELM (cola secuencial).  
2. Bandas FAST / MEDIUM / SLOW con intervalos objetivo derivados del intervalo base (no Hz prometidos).  
3. Métricas **por PID**: muestras, fallos, latencia última, Hz efectivo, edad, jitter (min/max gap).  
4. `pause` / `resume` para no competir con lectura DTC/mapa.  
5. Sin pruebas en movimiento mientras SAFE-001 esté abierto (UI/estación).  
6. Monitor muestra Hz efectivo por canal medido, no teórico.

## Criterios de aceptación

- [ ] `TEST-TEL-001` `nextDue` no propone dos PIDs a la vez (una decisión = un PID).  
- [ ] `TEST-TEL-002` FAST gana a SLOW si ambos están vencidos.  
- [ ] `TEST-TEL-003` Hz efectivo se calcula desde gaps reales.  
- [ ] `TEST-TEL-004` en pause, `nextDue` = null.  
- [ ] `TEST-TEL-005` canal MEDIUM no queda en starvation indefinida si está vencido.

## Fuera de alcance

Room/parquet; grabación en circulación; consultas paralelas al dongle.
