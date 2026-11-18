# SPEC-031 — Huella del vehículo y perfil JAC T8 con procedencia

**Objetivo (prompt maestro v2, 2026-11-18):** soportar perfiles por vehículo + año + motor + norma + proveedor/modelo de ECU, sin aplicar a esta T8 datos de otra variante.

**Por qué ahora.** La investigación de `research/` (2026-11-18) encontró que la misma denominación «T8 2.0 CTI / HFC4DB2» aparece con dos proveedores de ECU distintos (Bosch EDC17C81 y Delphi DCM6.2/6.2AP, catálogo XTOOL V21.18; Alientech para T8 **Pro** 2023) y con normas distintas (国VIb en la ficha china, 360 Nm en Chile frente a 400 Nm en China). La tesis UPS 2024 documenta que un escáner con perfil de norma equivocado (EURO 5 sobre una unidad EURO 3) mostró IAT/MAF/ECT fuera de rango. Un perfil mal resuelto produce lecturas falsas.

**Qué no cubre.** No lee DIDs propietarios (Mode 22). No escribe nada en la ECU. No publica el VIN.

### R5 — La huella dice qué la sostiene
**Estado:** verificado
**Comportamiento:** `VehicleFingerprint` reúne modelo y año declarados, el año codificado en el VIN (ISO 3779, posición 10, solo si hay VIN), CALID y nombre de ECU (Mode 09), protocolo y cabeceras de respuesta observadas, y el texto de la etiqueta física de la ECU que escribe el usuario. Cada campo conserva su origen (`DECLARED`, `ECU_MODE09`, `ECU_LABEL_TYPED`, `PROTOCOL_OBSERVED`).
**Criterio de aceptación:**
- [x] Con VIN cuyo carácter 10 es `P`, el año del VIN es 2023 y coincide con el declarado 2023.
- [x] Con año declarado 2023 y VIN con carácter 10 `N` (2022), se informa discrepancia; no se corrige el año declarado.
- [x] Sin VIN, el año queda solo como declarado.
**Verificado por:** `r5_vinYearMatchesOrContradictsTheDeclaredYear`

### R6 — El perfil de ECU solo se resuelve con un identificador documentado
**Estado:** verificado
**Comportamiento:** Cada candidato de ECU lista los identificadores que una fuente concreta documenta (p. ej. `EDC17C81`, `MD1CS089`, `DCM6.2AP`, `DCM6.2AP-6D`, `1026400FD020B-007`). Si alguno aparece en CALID, nombre de ECU o etiqueta escrita, el candidato queda como coincidente con la cita de dónde apareció. Si dos identificadores coinciden en el mismo texto y uno contiene al otro (`DCM6.2AP-6D` ⊃ `DCM6.2AP`), solo cuenta el más largo. Un candidato → `IDENTIFIED`. Varios del mismo proveedor → `SUPPLIER_ONLY` (proveedor sí, modelo no). Proveedores distintos → `AMBIGUOUS`. Ninguno → `PARTIAL` (solo perfil genérico J1979).
**Criterio de aceptación:**
- [x] Sin datos de ECU, el estado es `PARTIAL` y el texto no nombra proveedor.
- [x] Etiqueta «DELPHI DCM6.2AP» → `IDENTIFIED` (DCM6.2AP) con origen `ECU_LABEL_TYPED`.
- [x] Etiqueta «DCM6.2AP-6D» → `IDENTIFIED` (DCM6.2AP-6D), no DCM6.2AP.
- [x] Etiqueta «MD1CS089» → `IDENTIFIED` Bosch MD1CS089, distinto de EDC17C81.
- [x] Etiqueta «DCM6.2AP» y CALID con `1026400FD020` → `SUPPLIER_ONLY` (Delphi, variante no determinada).
- [x] CALID con `EDC17C81` y etiqueta con `DCM6.2AP` → `AMBIGUOUS`.
- [x] La coincidencia ignora puntos, espacios y mayúsculas, pero no acepta una subcadena dentro de otro identificador alfanumérico más largo (`EDC17C810` no es `EDC17C81`; `1026400FD020B054` no es `1026400FD020`).
**Verificado por:** `r6_ecuProfileResolvesOnlyFromADocumentedIdentifier`

### R7 — Una señal OEM no se consulta sin fuente, codificación y ECU coincidente
**Estado:** verificado
**Comportamiento:** Las señales ampliadas que un tercero lista para la T8 (OBD Lens) entran al catálogo con `UNKNOWN_ENCODING` y `readAllowlisted=false`. El conjunto activable de un perfil excluye toda señal sin codificación de request/response, sin fuente distinta de `UNVERIFIED` o cuyo alcance de ECU no coincida con el perfil resuelto.
**Criterio de aceptación:**
- [x] Con el catálogo v1, las señales activables son solo las genéricas J1979, aunque el perfil esté `IDENTIFIED`.
- [x] Una señal OEM ficticia de prueba con codificación y fuente, pero con ECU Bosch, no se activa en un perfil Delphi.
**Verificado por:** `r7_oemSignalNeverActivatesWithoutSourceEncodingAndMatchingEcu`

### R8 — Mapa de cobertura frente a lo que reporta la ECU
**Estado:** verificado
**Comportamiento:** Para cada señal del perfil: `SUPPORTED_VERIFIED` (la ECU la declaró en el bitmap y hubo valor válido en esta sesión), `SUPPORTED_UNVALIDATED` (declarada, sin valor válido aún), `UNSUPPORTED` (fuera del bitmap), `UNKNOWN` (bitmap no leído o codificación desconocida). «Verificado» habla del soporte, no de la exactitud del valor.
**Criterio de aceptación:**
- [x] Bitmap leído con 0x0C y una lectura válida de 0x0C → `SUPPORTED_VERIFIED`.
- [x] 0x10 fuera del bitmap → `UNSUPPORTED`.
- [x] Señal OEM → `UNKNOWN` aunque el bitmap esté leído.
**Verificado por:** `r8_coverageComparesProfileWithWhatTheEcuReported`

### R9 — El catálogo versionado del repositorio es el que usa la app
**Estado:** verificado
**Comportamiento:** `jac-diagnostic-lab/jac-t8-profiles/catalog-v1.json` se genera desde el catálogo Kotlin. Una prueba falla si difieren.
**Verificado por:** `r9_versionedCatalogFileMatchesTheAppCatalog`

### R11 — Identificación de ECU por Mode 09, solo lectura, con raw por ECU
**Estado:** verificado
**Origen:** usuario, 2026-11-18: «consultar Mode 09 02 VIN, 09 04 CALID, 09 06 CVN, 09 0A nombre ECU cuando exista; registrar respuestas raw, ECU origen y timestamps».
**Comportamiento:** Botón «Identificar ECU (Mode 09)» y el escaneo completo consultan `0900` y luego `0904`, `0906`, `090A` solo si su bit vino en 1. `0902` (VIN) siempre que la ECU lo declare: el vehículo es del usuario (decisión del propietario, 2026-11-18). Cada consulta queda como registro: comando, hora, latencia, tipo de respuesta, raw y valores separados por cabecera de ECU (7E8, 7E9…). El VIN no se copia al registro: queda solo en el intercambio `0902`, que la exportación anonimizada ya omite. Ningún comando es de escritura; la política existente (`ObdCommandPolicy`) los rechaza.
**Criterio de aceptación:**
- [x] Con bitmap `0900` que declara 02, 04 y 0A, el plan es `0900, 0902, 0904, 090A`; si no declara 02, no se pide.
- [x] Una respuesta `0904` con tramas de 7E8 y 7E9 produce dos grupos de CALID, uno por cabecera.
- [x] El registro de `0902` no contiene el VIN.
- [x] El plan solo contiene comandos que `ObdCommandPolicy` permite y ninguno de 04, 14, 2E, 31 ni 34.
**Verificado por:** `r11_mode09IdentificationIsReadOnlyPerEcuAndKeepsVinOut`

### R10 — El mapa Mode 01 se recorre por páginas solo si la página anterior declara la siguiente
**Estado:** verificado (cierra SPEC-022 requisito 5)
**Comportamiento:** Función pura `walkSupportPages`. 0x00 siempre; 0x20, 0x40… solo si el bit de esa página vino en 1 en la anterior. Si la página 0x00 no responde, `bitmapRead=false`.
**Verificado por:** `r10_supportPagesFollowTheNextPageBitOnly`

**Depende de:** ADR-002, SPEC-022, SPEC-002 (R4).
