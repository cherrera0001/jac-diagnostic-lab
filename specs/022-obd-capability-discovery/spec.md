# SPEC-022 — ObdCapabilityDiscoveryEngine

**Estado:** en implementación  
**Commit baseline:** `6b6470c`  
**AC:** AC-003

## Requisitos

1. Inventario tipado de PIDs Mode 01 de interés (catálogo local + bitmap ECU).  
2. Cada entrada declara `support_status` y, si falta, **motivo de ausencia** (no «Sin datos»).  
3. Distinguir: bitmap no leído, ECU no responde, PID fuera de mapa, NO DATA tras sonda, fórmula desconocida, requiere otro hardware (p. ej. freno).  
4. No inventar soporte OEM.  
5. Extender bitmaps `0x00 → 0x20 → 0x40 → 0x60…` solo si el bit de página siguiente está declarado.

## Criterios de aceptación

- [ ] `TEST-CAP-001` bitmap `BE 1F A8 13` marca `0x0C` DECLARED_SUPPORTED.  
- [ ] `TEST-CAP-002` PID fuera de bitmap → `PID_NOT_IN_BITMAP`.  
- [ ] `TEST-CAP-003` interruptor de freno → `REQUIRES_DIFFERENT_HARDWARE`.  
- [ ] `TEST-CAP-004` sin bitmap y sin ECU → motivos `BITMAP_NOT_READ` / `ECU_NOT_RESPONDING`.  
- [ ] UI «Capacidad» agrupa Disponible / No soportado / No evaluado / Otro hardware.

## Fuera de alcance

Exploración UDS propietaria en vehículo real; Mode 04; conducción.
