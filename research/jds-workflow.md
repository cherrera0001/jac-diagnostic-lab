# JDS (JAC Diagnostic System) — flujo según el material público

**Fuente única:** S16, presentación de 38 diapositivas «Service Training Materials — JAC Customer Service Department» en un espejo de terceros (en.ppt-online.org/1230239). Sin fecha ni versión (diap. 38 con campo de versión vacío). La descarga automática recibió captcha; se leyó con el agente web-fetch, sin evadir la protección. **Nivel: UNVERIFIED** — no se confirmó que sea la versión vigente ni que cubra la T8.

## Arquitectura (diap. 3–5)

```text
OBD (pines 6/14 CAN; 3/11 alternativo) ─► VCI «JAC_DIAG» (pieza JACT0074) ─Bluetooth─► App Android (teléfono/PAD, p. ej. Launch X431 PAD)
                                                                                  │
                                                         red ◄──────────────────┘
                                                          ▼
                                       Plataforma de datos de diagnóstico (WEB, servidor JAC)
```

- PAD/Android: para distribuidores. PC: «uso interno de JAC, no abierto a distribuidores».
- Los archivos de diagnóstico se descargan del servidor en cada uso y «no se guardan en el teléfono» (diap. 7). Modo offline solo con aprobación (diap. 29).
- Cuentas «código de distribuidor + número de serie», asignadas por JAC; el formulario pide plataforma: Passenger / Lightduty / Heavyduty / **Pickup** (diap. 10).
- Cobertura declarada: «passenger car S4, heavy truck K7», el resto «se actualizará» (diap. 8). **La T8 no aparece.**

## Flujo de uso (diap. 6–31)

| Paso | JDS | Equivalente en JAC Diagnostic Lab | Estado |
|---|---|---|---|
| Emparejar VCI (sin PIN), luego abrir app | diap. 6, 13 | Vincular → ELM327 SPP/BLE (`bt/Bluetooth.kt`, `LinkPhase`) | IMPLEMENTADO (antes de esta iteración) |
| Login en red | diap. 11 | No aplica: la app es offline y sin cuenta | Decisión de diseño |
| Plataforma → modelo → configuración → identificación manual o automática | diap. 14–15 | Modelo/año declarados (SPEC-002) + **resolvedor de perfil** con Mode 09 y etiqueta (SPEC-031) | IMPLEMENTADO en 1.9.0 |
| Escaneo de todo el vehículo / borrado global | diap. 16 | Escaneo completo solo ECU motor por OBD genérico. **Borrado (Mode 04) bloqueado** | Lectura sí, borrado no (política) |
| Lista de ECUs → ECU | diap. 17 | Solo 7E8 (motor). Otras ECUs requieren direcciones/DIDs no documentados | NO VERIFICADO |
| Info ECU: 图号 (número de pieza), 供应商代码 (código proveedor), nombre de sistema | diap. 19 | Mode 09 CALID/CVN/ECU name + **etiqueta escrita** cruzada con candidatos documentados | IMPLEMENTADO en 1.9.0 |
| DTC actual / histórico / borrar | diap. 20 | Modes 03/07/0A; borrar bloqueado | IMPLEMENTADO (lectura) |
| Escritura de datos / VIN / configuración | diap. 21–22 | **Fuera de alcance** (escritura) | No se implementa |
| Flujos de calibración y aprendizaje; control I/O; reflash | diap. 23–25, 27 | **Fuera de alcance** (actuadores/programación) | No se implementa |
| Datos en vivo, máx. 6 ítems (más ítems = más retardo) | diap. 26, 37 | Monitor con Hz medido por PID y bandas FAST/MED/SLOW (SPEC-023). La regla «menos señales = más resolución» coincide | IMPLEMENTADO |
| Grabación de bus en SD del VCI (archivo JCL binario, lo convierte JAC) | diap. 32–33 | JSONL append-only por medición (SPEC-027) | Patrón equivalente, formato propio |
| Actualización de software / firmware VCI | diap. 30, 34 | Releases versionados con SHA-256 | IMPLEMENTADO |

## Qué se adopta (implementación original)

1. **Identificación por evidencia de ECU** (número de pieza + proveedor) en lugar de solo modelo → SPEC-031.
2. **Límite explícito de señales simultáneas** para no prometer Hz → ya existe; el Monitor muestra el Hz real.
3. **Funciones no soportadas en gris** (diap. 36) → la matriz de Capacidad ya muestra motivo de ausencia, no botones muertos.

## Qué no se intenta

Obtener el APK de JDS, crear cuentas, usar el VCI de JAC o llamar al servidor de diagnóstico. Requieren autorización de JAC (gaps G-01).
