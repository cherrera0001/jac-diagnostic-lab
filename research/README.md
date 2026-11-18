# research/ — investigación JAC T8 (2026-11-18)

| Archivo | Qué contiene |
|---|---|
| [source-manifest.json](source-manifest.json) | 37 fuentes: URL, fecha UTC, HTTP, bytes, SHA-256, nivel de evidencia, alcance, licencia, hallazgo |
| [source-matrix.md](source-matrix.md) | Resumen legible del manifiesto |
| [jac-t8-evidence.md](jac-t8-evidence.md) | Qué se sabe de la T8, de dónde sale y qué no prueba de esta unidad |
| [api-discovery.md](api-discovery.md) | APIs reales vs potenciales vs no accesibles; conclusión sobre API OEM |
| [jds-workflow.md](jds-workflow.md) | Flujo de JAC Diagnostic System según el material público y su equivalente propio |
| [oem-re-analysis.md](oem-re-analysis.md) | OSINT/REA realizado, con qué acceso, y lo no realizado con su motivo |
| [app-capability-matrix.csv](app-capability-matrix.csv) | Función observada → fuente → diseño propio → estado |
| [gaps.md](gaps.md) | Brechas abiertas y borrador de solicitud a JAC Chile |
| [validation/2026-11-18-unidad-real.md](validation/2026-11-18-unidad-real.md) | Lectura real de la unidad: bytes, ECU confirmada, códigos, fallos de la app corregidos |
| [tools/build_manifest.py](tools/build_manifest.py) | Regenera el manifiesto desde los registros de descarga |
| `_fetch-log.tsv`, `_fetch-log-extra.tsv` | Registro crudo de cada descarga (HTTP, bytes, SHA-256, hora UTC) |

`_downloads/` y `_extract/` **no se versionan** (manuales y páginas con derechos). Para reproducir: volver a descargar las URL del manifiesto y comparar el SHA-256; las páginas web dinámicas pueden cambiar de hash sin cambiar de contenido relevante.

Niveles: `OEM_EXACT_VARIANT` (ninguna fuente alcanza este nivel para esta unidad), `OEM_OTHER_VARIANT`, `ACADEMIC_VALIDATED`, `VENDOR_CATALOG`, `COMMUNITY_REPRODUCED`, `STANDARD_PUBLIC`, `UNVERIFIED`. La app muestra el nivel de cada fuente en Capacidad → Perfil del vehículo y lo exporta en el JSON (`extended.profile.sources`).
