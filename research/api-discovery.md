# Descubrimiento de APIs — real vs potencial vs no accesible

Revisión 2026-11-18. Ninguna URL, token, esquema ni endpoint de esta tabla es inventado: cada fila tiene fuente en `source-manifest.json`. Nada de esto se integra con credenciales.

## Conclusión

**No existe una API oficial pública verificable que entregue datos de diagnóstico de una JAC T8 2023 chilena.** La única API JAC pública encontrada (S25) publica documentos RMI de 6 modelos de exportación UE y no incluye la T8. La telemática de consumidor (S13, S14/S15) no cubre la T8 y no es diagnóstico de taller. JDS (S16) depende de cuenta asignada por JAC al distribuidor y de un VCI propio.

Estrategia funcional: **datos locales OBD/ECU** (`ObdVehicleProvider` = sesión ELM actual, offline) + **conocimiento documental versionado** (`jac-t8-profiles/catalog-v1.json`) + **importación futura de reportes de taller**. El catálogo admite añadir una fuente o proveedor sin reescribir la app (ADR-002).

## Tabla

| provider | URL | categoría | autenticación | términos/licencia | alcance geográfico | compatibilidad T8 2023 | campos/PIDs | tasa | costo | estado probado | decisión |
|---|---|---|---|---|---|---|---|---|---|---|---|
| JAC Global RMI list | `https://jacen.jac.com.cn/index.php/api/car/carInformationList.html` (S25, descubierto en S30/S31) | A. Fabricante — documentación | Ninguna (GET público que llama la web oficial) | Datos RMI publicados por JAC; documentos con derechos | Modelos exportación UE | **NO**: 6 modelos (Sunray Pro-EU, LE796, N75EV, E30X, M3EV, e-JS4) | `name`, `obd_path`, `repair_path`, `maintenance_path`, `manual_path`, `emergency_path`, `eu_declarations_path`… | 1 consulta manual | 0 | `DOCUMENTED_PUBLIC` — HTTP 200, JSON válido (UTF-8 BOM), 6 entradas | `NOT_T8_COMPATIBLE`. Volver a consultar en cada release por si aparece T8 (ver gaps G-02). No se integra en la app. |
| JAC Global /api/car/… sin `index.php` | `https://jacen.jac.com.cn/api/car/carInformationList.html` | A | — | — | — | — | — | — | — | HTTP 404 | Descartado (ruta incorrecta). |
| App JAC (JAC ITALY SRL) | Play `com.jac.global` (S13) | A. Telemática consumidor | Cuenta de usuario | Términos Play / privacidad del editor | Europa (EV) | **NO declarada**: carga, bloqueo, A/C, ventanas | Estado de carga, cerraduras, ventanas | — | — | `PRIVATE_UNAVAILABLE` (backend privado, sin doc developer) | No se integra. No es diagnóstico. |
| JAC MX | `https://www.jac.mx/jac-mx-app-tc` (S14), `…-ap` (S15) | A. Telemática consumidor | Cuenta | Términos JAC MX | México | **NO**: lista cerrada Sei4/6/7 Pro, J7, E10X, E J7, E Sei4Pro | Combustible/energía, km, TPMS, ubicación | — | — | `NOT_T8_COMPATIBLE` | No se integra. |
| JDS (JAC Diagnostic System) | Material S16 | B/A. Taller OEM | Cuenta «código distribuidor + serie» asignada por JAC; VCI JACT0074 | Uso de distribuidor | Distribuidores JAC | Desconocida (material menciona S4 y K7) | ECU info (图号, 供应商代码), DTC actual/histórico, datos en vivo ≤6, flujos, reflash | — | VCI comprado a JAC | `PARTNER_REQUIRED` | No se accede. Solicitud legítima vía JAC Chile (gaps G-01). Se imitan solo patrones de UX propios. |
| OBD-II / EOBD local | SAE J1979, ISO 15765-4 (protocolo, no web) | B. Protocolo directo | Ninguna | Norma | Universal | **SÍ** (ECU respondió en sesiones previas; S09 confirma CAN 500k) | Mode 01/02/03/06/07/09/0A | Medida por PID (SPEC-023) | 0 | `INTEGRATED_TESTED` (replay/JVM); hardware en sesiones previas del propietario | Núcleo de la app (`ElmSession`, `PidCatalog`, `Mode09Decoder`). |
| OBD Lens | `https://obdlens.com/…legacy-1153…` (S12, S28) | D. Tercero | App propia | Licencia personal e intransferible (S29 §5) | — | Perfil «T8 2.0 CTI» sin años | 40 nombres de señal; **sin request ni fórmula** | — | — | `UNVERIFIED` para esta unidad | Solo nombres como referencia `UNKNOWN_ENCODING`, no consultables. |
| XTOOL / Autel / Launch | S18; Autel citado en S09 | D. Escáneres | Hardware + licencia | Propietarios | — | Catálogo de funciones por familia de motor | Funciones, no PIDs | — | Licencia | `PRIVATE_UNAVAILABLE` (sin API documentada pública) | Solo como evidencia de variantes ECU. |
| Delphi/Bosch (ECU) | S11, S21, S22 | D. Tuning | Herramientas comerciales | Propietarios | — | Variantes T8/T8 Pro | IDs HW/SW | — | — | Fuera de alcance (lectura/escritura de flash) | Solo identificadores para el resolvedor. **No se escribe la ECU.** |
