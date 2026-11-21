# SPEC-034 — Motor de Evidencia Técnica (Blue Ocean MVP)

**Estado:** Borrador SDD (derivado de la Investigación OSINT y Estrategia de Océano Azul, 2026-11-20)  
**Base:** `SPEC-031` (Perfiles y Mode 09), `SPEC-032` (Versión visible), `SPEC-033` (Informe para el taller, v1.10.0–v1.11.0)  
**Propósito:** Evolucionar `jac-diagnostic-lab` hacia un **Vehicle & Machinery Health Evidence Engine** reutilizable, offline-first y de solo lectura, enfocado en reducir incertidumbre y días de inmovilización en activos productivos (camionetas de trabajo, microflotas y maquinaria) y talleres independientes.

---

## 1. Requisitos funcionales (SDD)

### R1 — Contrato Canónico de Evidencia (`EvidenceEnvelope`)
- Toda sesión de diagnóstico debe producir un contenedor canónico agnóstico de marca que separe estrictamente:
  1. **`observedFacts`**: lecturas crudas verificadas (bytes, timestamp, latencia, unidad, perfil ECU que las decodificó).
  2. **`missingData`**: parámetros o módulos que no respondieron, fueron rechazados (ej. Mode 0A) o carecen de perfil verificado.
  3. **`inferences`**: hipótesis diagnósticas o correlaciones, siempre etiquetadas con nivel de confianza y nunca presentadas como causa raíz confirmada.

### R2 — Degradación Elegante (ECU desconocida o sin electrónica)
- Si el vehículo/máquina tiene una ECU no catalogada o no responde OBD:
  - La app debe operar en modo estándar (OBD-II básico) y declarar explícitamente qué no puede leer.
  - Debe permitir registrar **inspección física guiada** (horómetro/odómetro manual, observaciones, estado visual) para equipos antiguos o mecánicos.
- **Regla estricta:** Prohibido inventar o mostrar «porcentajes de salud» globales.

### R3 — Trazabilidad Criptográfica y Portabilidad
- Todo informe exportado (PDF + JSON) para taller o segunda opinión debe incluir:
  - Versión de la app (`AppVersion`) y del catálogo de perfiles (`catalog-v1.json`).
  - Hash **SHA-256** del paquete de evidencia cruda.
  - Funcionamiento **100% offline** (sin requerir conexión a internet para capturar, analizar o exportar).

### R4 — Comparabilidad Antes / Después de Reparación
- El motor debe permitir comparar dos sesiones del mismo activo (por VIN o identificador local):
  - DTCs resueltos, persistentes o nuevos.
  - Diferencia en lecturas clave (ej. distancia desde borrado de códigos, estado de monitores).

### R5 — Política de Solo Lectura
- El producto mantiene operación **estrictamente de solo lectura** en campo. No se incluyen rutinas de escritura, borrado automático ni reprogramación de ECU.

### R6 — Modo Tablero / Tablet en Vivo y Asistencia de Conducción Segura
- **Diseño Glanceable / Hands-Free:** Vista apaisada (landscape) de alto contraste con tipografía grande, sin tablas densas ni botones pequeños, pensada para soporte fijo en tablero/tablet (cero interacción táctil requerida al conducir).
- **Reconexión en vivo resiliente:** Indicador visual claro (`CONECTADO`, `RECONECTANDO`, `PAUSADO`) que conserva la sesión y los datos acumulados ante cortes Bluetooth/GATT.
- **Asistencia de marcha y carga (vehículos mecánicos):**
  - Inferencia declarada de marcha actual mediante relación `RPM / Velocidad` (cuando velocidad > 5 km/h).
  - Indicador simple de eficiencia/salud mecánica basado en `RPM`, `Velocidad`, `Carga de motor` y `Temperatura`: sugerir subir marcha (RPM altas con baja carga), bajar marcha (evitar sobreesfuerzo/*lugging* a bajas RPM con alta carga) o conducción suave si el motor está frío.
  - **Resumen post-viaje:** Reportar % de tiempo en sobreesfuerzo, en frío y en zona óptima, conectando la conducción con la salud del motor.

---

## 2. Plan de Pruebas (TDD — Rojo → Verde)

Antes de implementar cada requisito, crear las siguientes pruebas unitarias en `jac-diagnostic-lab/app/src/test/java/app/jac/diagnosticlab/`:

1. `r17_evidenceEnvelopeSeparatesFactsMissingDataAndInferences`: Verifica que una sesión con DTCs y datos ausentes nunca mezcle inferencias dentro de `observedFacts`.
2. `r18_unknownEcuDegradesGracefullyWithoutInventingHealthScore`: Verifica que ante un CALID desconocido o sin respuesta OBD, el motor devuelva estado `UNVERIFIED_PROFILE` / `MANUAL_INSPECTION` y ningún puntaje porcentual global.
3. `r19_exportedEvidenceBundleIncludesSha256AndCatalogVersion`: Verifica que la exportación JSON/PDF contenga el hash SHA-256 válido y la versión del catálogo.
4. `r20_sessionComparisonDetectsResolvedAndNewDtcs`: Verifica la comparación entre una sesión pre-reparación (ej. P0579, P2299) y una post-reparación.
5. `r21_readOnlyPolicyBlocksWriteCommands`: Verifica que cualquier intento de enviar comandos de escritura sea rechazado por la política de comandos.
6. `r22_shiftAndLoadGuidanceUsesObservedRpmSpeedAndLoadSafely`: Verifica la inferencia de marcha por `RPM/Velocidad`, detección de sobreesfuerzo (bajas RPM + alta carga) y que se etiquete como inferencia.

---

## 3. Criterio de aceptación
- Todas las pruebas previas (101) + `r17`..`r21` en verde mediante:
  ```bash
  cmd.exe /c "gradlew.bat :app:testDebugUnitTest --console=plain"
  ```

