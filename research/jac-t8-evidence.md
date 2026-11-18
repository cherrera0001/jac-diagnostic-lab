# JAC T8 — qué se sabe, de dónde sale y qué NO prueba de esta unidad

Revisión: 2026-11-18. Unidad de referencia: JAC T8 2023 del propietario (Chile). **Actualización 2026-11-18 (lectura real):** VIN leído (año 2023 por ISO 3779), ECU **Delphi DCM6.2** por CALID 0904, CVN `A7BB65AE`, CAN 11/500 en 7E8. Variante AP / AP-6D sin determinar. Ver `research/validation/2026-11-18-unidad-real.md`.
Cada afirmación cita un id de `source-manifest.json`. Estados: `DOCUMENTADO` (fuente), `DESCARGADO` (archivo con hash), `ANALIZADO` (extraído y leído), `IMPLEMENTADO` (en la app), `PROBADO EN REPLAY` (prueba JVM), `PROBADO EN HARDWARE` (ninguno en esta iteración), `NO VERIFICADO`.

## 1. Identidad de la familia (no de la unidad)

| Dato | Valor | Fuente / nivel | Estado | Qué NO prueba |
|---|---|---|---|---|
| Motor T8 Chile | 2.0 CTI diésel, 147 HP, 360 Nm, manual 6 | S03 `OEM_OTHER_VARIANT` | ANALIZADO | Que la unidad 2023 tenga esa calibración (la ficha es 2024) |
| Motor T8 China | HFC4DB2-2E2, 110 kW, 400 Nm, 国Ⅵb | S19 `OEM_OTHER_VARIANT` | ANALIZADO | Equivalencia con Chile: **el par difiere (400 vs 360 Nm)** |
| Código de motor T8 2.0 CTI | HFC4DB2-2E | S12 `COMMUNITY_REPRODUCED` | ANALIZADO | Año ni mercado (perfil sin años) |
| ECU familia HFC4DB | Bosch **EDC17C81** (国四/国五, +SCR o +DPF) **o** Delphi **DCM6.2AP** (国五) | S18 `VENDOR_CATALOG` | ANALIZADO | Cuál lleva esta unidad |
| ECU T8 **Pro** 2023 | Delphi DCM6.2AP | S11 `VENDOR_CATALOG` | ANALIZADO | La T8 estándar |
| ECU T8 2.0TDI 2024 (137 hp/320 Nm) | Delphi DCM6.2, HW 1026400FD020, SW 1MJACLSA_A010D11 | S21 `VENDOR_CATALOG` | ANALIZADO | Otra potencia que la ficha Chile |
| ECU T8 2.0 TD 100 kW / T8 **PRO** 107 kW / T8 2.0 Turbo gasolina 130 kW | DCM6.2AP / **Bosch MD1CS089** / Delphi MT80 | S32 Dimsport `VENDOR_CATALOG` | ANALIZADO | Cuál corresponde a esta unidad (la ficha Chile dice 147 HP ≈ 110 kW, que no coincide con ninguna fila) |
| ECU T8 2020+ 2.0 diésel | Delphi DCM6.2AP (SPC5674) | S33 AutoTuner `VENDOR_CATALOG` | ANALIZADO | Variante exacta |
| Repuesto Chile | «JAC Delphi DCM6.2 AP, código OEM 1026400FD020B-007» | S36 MDC Repuestos `VENDOR_CATALOG` (aportada por el usuario, verificada) | ANALIZADO | Que esta unidad lleve ese código |
| Repuestos 1026400FD020 / 1026400FD020B054 | Delphi T6/T8 sin variante; JT8 sin proveedor | S35, S34 `VENDOR_CATALOG` | ANALIZADO | Variante AP vs AP-6D |
| DCM6.2AP-6D | Existe como variante Delphi, **documentada para SsangYong** (BitBox FID 89) | S22 `VENDOR_CATALOG` | ANALIZADO | Que exista en JAC T8 (aportado por el usuario, sin fuente JAC descargada) |
| IPAR 1026400FD020 T8 2023 HFC4DB2 | — | S37 `UNVERIFIED` | FALLIDO (sin producto en el sitio) | Nada hasta verificar |
| Bus OBD | CAN 11 bit, 500 kbps, ISO 15765-4, ECU motor 7E0/7E8 | S09 p.28 `ACADEMIC_VALIDATED` | ANALIZADO | Esta unidad; la app ya lo observa con `ATDPN` y cabeceras |
| Testigo EOBD; EPC gobernada por su módulo; DPF «si está configurado»; regeneración forzada | Manual oficial T8 Chile | S02 p.13, 16, 17, 148 `OEM_OTHER_VARIANT` | ANALIZADO (OCR) | Que esta unidad tenga DPF |

**Conclusión fundamentada:** el candidato con más respaldo para una T8 diésel 2020+ es **Delphi DCM6.2AP** (S18, S11, S32, S33, S36), pero existen variantes documentadas con Bosch EDC17C81 y, para T8 PRO, Bosch MD1CS089. Con fuentes públicas no se puede asignar proveedor de ECU ni norma a esta T8 2023. Hay al menos tres variantes documentadas con potencia/par distintos. La app resuelve el perfil por evidencia observable (SPEC-031, ADR-002) y queda `PARTIAL` hasta que Mode 09 o la etiqueta de la ECU traigan un identificador documentado.

## 2. Lecciones de la tesis UPS 2024 (S09) aplicadas

| Hallazgo (página) | Aplicación en la app | Estado |
|---|---|---|
| p.47: escáner con perfil EURO 5 sobre motor EURO 3 mostró IAT/MAF/ECT >110 °C fuera de rango | Perfil por evidencia, no por modelo; nota en señales 0x0F/0x10 del catálogo | IMPLEMENTADO, PROBADO EN REPLAY (R6, R9) |
| p.17: tabla MAF escribe `/32` con máximo 655,35 | Errata: 655,35 = 65535/100. Se usa J1979 `/100` (decoder existente) | ANALIZADO; documentado en catálogo |
| p.54: dos herramientas consultando a la vez retrasan o corrompen datos | Ya existe cola de un comando activo (SPEC-023 R1). Añadido a `gaps.md` como advertencia de operación | DOCUMENTADO |
| p.28: 7E0/7E8, 500 kbps | Coherente con la detección actual (`isCan`, cabeceras 7E8) | Sin cambio |
| p.55: otros módulos no estudiados | Igual que la app: solo ECU de motor por OBD genérico | Sin cambio |

## 3. P0579 / P2299

- Ambos son rangos SAE (P0xxx y P2xxx están controlados por J2012). Un resumen automático de búsqueda afirmó lo contrario para P2299; se descartó por contradecir la estructura de J2012.
- Texto exacto de J2012 no descargado (norma de pago). Ninguna fuente JAC descargada define estos códigos para la T8. La app mantiene su redacción prudente actual («definición pública», sin pieza a cambiar) — `analysis/LiveSituation.kt`, `evidence/Export.kt:340-355`.
- XTOOL (S18) lista para EDC17C81 y DCM6.2AP pruebas de actuador como «巡航控制灯测试» (luz de crucero) que **no** se implementan: la app es solo lectura.

## 4. Qué haría falta para cerrar la identificación

0. **CHANGELOG v1.8.0 «ECU Delphi DCM6.2»: VERIFICADO el 2026-11-18** con 0904 = `DCM6.2` en la unidad real.
1. **Botón «Identificar ECU (Mode 09)» o escaneo completo** (`0904` CALID, `090A` nombre ECU) en la unidad. La app ya lo hace y ahora lo cruza con los candidatos.
2. **Foto o texto de la etiqueta de la ECU** (campo nuevo en Capacidad → Perfil del vehículo).
3. **VIN**: se lee directamente (0902) cuando la ECU lo declara; se usa el carácter 10 para comparar el año. La exportación ofrece anonimizarlo si el archivo se comparte.
4. Respuesta de JAC Chile (S05) con manual de taller y versión de ECU — ver `gaps.md` G-01.
