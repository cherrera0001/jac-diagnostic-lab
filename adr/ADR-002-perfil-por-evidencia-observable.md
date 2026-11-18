# ADR-002 — El perfil de la ECU se elige por evidencia observable, no por el nombre del modelo

**Fecha:** 2026-11-18
**Estado:** aceptado
**Spec:** SPEC-031

## Contexto

«JAC T8 2.0 CTI» no identifica una ECU. Fuentes públicas descargadas el 2026-11-18 (`research/source-manifest.json`):

- XTOOL, función JAC V21.18 (catálogo de herramienta, S18): la familia HFC4DB aparece con Bosch EDC17C81 (国四/国五, con y sin DPF) y con Delphi DCM6.2AP (共轨国五).
- Alientech (S11): Delphi DCM6.2AP en T8 **Pro** 4×4 2.0 diésel desde 2023.
- Dimsport (S32): T8 2.0 TD 100 kW → DCM6.2AP; T8 PRO 107 kW → Bosch MD1CS089; T8 2.0 Turbo gasolina → Delphi MT80. AutoTuner (S33): DCM6.2AP en T8 2020+. MDC Repuestos Chile (S36): DCM6.2 AP con código OEM 1026400FD020B-007.
- DCM6.2AP-6D aparece documentada para SsangYong (S22); para JAC solo como aporte del usuario. Se modela como candidato propio para no confundirla con DCM6.2AP.
- Ficha JAC China (S19): T8 con HFC4DB2-2E2, 110 kW, 400 Nm, 国VIb. Ficha JAC Chile (S03): 147 HP, 360 Nm. El par difiere: no es la misma calibración.
- Tesis UPS 2024 (S09, p. 47): perfil de norma equivocado en un escáner comercial produjo IAT/MAF/ECT fuera de rango en una T8 diésel.

La API pública de JAC Global (`/index.php/api/car/carInformationList.html`) publica OBD/reparación solo para Sunray Pro-EU, LE796, N75EV, E30X, M3EV y e-JS4. No hay paquete T8.

## Decisión

1. El perfil genérico J1979 siempre está activo y no depende de la marca.
1b. Modelos de ECU distintos nunca se mezclan: si un identificador más largo contiene a otro en el mismo texto gana el largo; varias coincidencias del mismo proveedor dan `SUPPLIER_ONLY`, de proveedores distintos `AMBIGUOUS`.
2. Un perfil de ECU se resuelve solo si un identificador **documentado por una fuente concreta** aparece en CALID, nombre de ECU o en la etiqueta física escrita por el usuario. Se cita la fuente y dónde apareció.
3. Las señales OEM entran al catálogo como referencia (`UNKNOWN_ENCODING`, `readAllowlisted=false`) y no se consultan hasta tener codificación, fuente y ECU coincidente.
4. El catálogo vive en Kotlin (sin dependencia JSON en la app) y se exporta a `jac-t8-profiles/catalog-v1.json`; una prueba impide que diverjan.

## Descartado

- **Elegir el perfil por modelo y año declarados.** Mismo nombre comercial, distinta ECU y norma.
- **Importar las 40 señales de OBD Lens.** No publica request ni fórmula y su licencia es personal e intransferible (S12, términos §5).
- **Inferir el proveedor desde el formato del CALID.** Ninguna fuente descargada documenta ese formato para JAC.

## Consecuencias

- Mientras el usuario no escriba la etiqueta de la ECU o el CALID no traiga un identificador documentado, la app dice `PARTIAL` y solo lee J1979. Es la respuesta correcta, no una carencia que esconder.
- Añadir una señal OEM exige un PR que traiga fuente, codificación y vectores de prueba.
