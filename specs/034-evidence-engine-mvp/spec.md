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

### R7 — Estimación de Combustible sin Mirar el Tablero
- **Fuente primaria (`PID 0x2F`):** Leer `PID 0x2F` (*Fuel Tank Level Input*, %) si la ECU lo soporta y entrega un valor válido, combinándolo con la capacidad nominal del estanque (ej. 76 L por defecto para JAC T8, configurable) para estimar litros restantes.
- **Respaldo por integración (`PID 0x5E` / `PID 0x10`):** Si la ECU no soporta `PID 0x2F` (o no responde dato válido), estimar el consumo acumulado y los litros restantes integrando en el tiempo `PID 0x5E` (*Engine Fuel Rate*, L/h) o, en su defecto, `PID 0x10` (*MAF*, g/s, usando perfil de combustible diésel/gasolina y relación aire/combustible + densidad) a partir de un evento manual explícito de **«Estanque lleno»**.
- **Regla estricta de etiquetado:** El resultado en litros debe etiquetarse **siempre** como `ESTIMACIÓN/INFERENCIA` (nunca como medición directa de volumen), con invariante en código (`isInference = true`, `isDirectMeasurement = false`) y exportación compatible con `DiagnosticInference` (R1). Si faltan fuentes válidas o falta el evento «Estanque lleno», informar honestamente `UNAVAILABLE` sin inventar cifras.

### R8 — Captura Continua en Segundo Plano (`ForegroundService`)
- Mantener un `ForegroundService` (`DiagnosticConnectionService`, `foregroundServiceType="connectedDevice"`) con notificación persistente (`ongoing = true`) mientras exista enlace OBD-II o captura activa (`LIVE`, `ROAD`, monitor continuo o autodiagnóstico).
- La notificación persistente debe informar el estado real de la captura OBD-II y del registro de evidencia (enlace, modo activo, muestras/tramas acumuladas y reconexión) y permitir volver a la aplicación sin interrumpir la sesión, asegurando continuidad aun cuando la pantalla se apague o se utilice otra app en primer plano (ej. Waze/Maps/Spotify).

### R9 — Soporte para Android Auto (`androidx.car.app:app`)
- Integrar la librería oficial `androidx.car.app:app` en el módulo `:app`.
- Proveer un `CarAppService` (`JacCarAppService`) con pantalla *glanceable* en **solo lectura** que muestre estado de enlace, DTCs activos y lecturas clave del vehículo (incluyendo estimación de combustible R7 etiquetada como inferencia cuando esté disponible), compartiendo el `LabController` sin duplicar conexiones Bluetooth ni comandos de escritura/borrado.
- Declarar el servicio y metadatos en `AndroidManifest.xml` (`com.google.android.gms.car.application`, `androidx.car.app.minCarAppApiLevel`, categoría compatible `androidx.car.app.category.IOT` para pruebas en modo desarrollador / Fuentes desconocidas) junto con `res/xml/automotive_app_desc.xml` (`<uses name="template" />`).

### R10 — Perfil de Captura Rápida en Vivo (Set Crítico de 8 PIDs)
- Proveer un modo de monitoreo ligero (**«Captura rápida de falla»**, `FAST_FAULT`) que consulte **únicamente** los 8 PIDs críticos: `0x49` (Pedal D), `0x4A` (Pedal E), `0x04` (Carga calculada), `0x62` (Torque/par real), `0x0C` (RPM), `0x0D` (Velocidad), `0x23` (Presión de riel) y `0x42` (Tensión ECU).
- Objetivo: evitar saturar adaptadores ELM327 (especialmente clones BLE/RFCOMM v2.1), maximizar la frecuencia de muestreo en el instante del evento y prevenir pérdidas de enlace durante pruebas dinámicas o registro en ruta/banco.

### R11 — Diagnóstico Diferencial y Recomendación Automática en Informe Técnico (`WorkshopReport` / `WorkshopPdf`)
- En la sección **Diagnóstico principal**, agregar un bloque explícito de **Diagnóstico diferencial** que separe estrictamente **Hechos observables** de **Hipótesis (no confirmadas)** y distinga:
  1. Evidencia compatible con **corte de torque / potencia** (ej. *brake override* `P0579`/`P2299`: pedal pisado con recorte de par/carga manteniendo giro de motor, riel y tensión).
  2. Evidencia compatible con **apagado total de motor** (caída de presión de riel `0x23` o caída de tensión ECU `0x42` precediendo el desplome de RPM).
- Si en la sesión ocurrió **pérdida de enlace / fallos de comunicación** o **datos no capturados en Mode 02 (cuadro congelado)**, incluir automáticamente la recomendación de usar el **Set Crítico de 8 PIDs (`0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42`)** en la siguiente captura.

---

## 1.1 Trazabilidad con GitHub Issues / Projects

Revisión del repositorio oficial `cherrera0001/jac-diagnostic-lab` y Project #22 (`JAC T8 / C4A Automotive Security`):

| Issue / Project | Estado | Mapeo a SPEC-034 |
| --- | --- | --- |
| **#1 (`SAFE-001`)** — *Corte de potencia al frenar — diagnosticar y resolver* (`P0`, `diag`, `safety`) | **OPEN** (requiere validación física en vehículo) | **R8** (captura continua en segundo plano sin perder eventos), **R9** (monitoreo seguro en Android Auto), **R10** (Set Crítico de 8 PIDs para capturar el corte sin saturar el ELM327), **R11** (Diagnóstico Diferencial corte de torque vs apagado total en informe PDF). |
| **#2–#11** (`DIAG-001`..`007`, `SEC-BT-001`, `SEC-TARA-001`, `LAB-CAN-001`) | **CLOSED** | Consolidados como base de `R1`–`R6`; **R7** (estimación de combustible sin mirar el tablero) extiende la telemetría de solo lectura sin inventar mediciones directas. |

---

## 2. Plan de Pruebas (TDD — Rojo → Verde)

Antes de implementar cada requisito, crear las siguientes pruebas unitarias en `jac-diagnostic-lab/app/src/test/java/app/jac/diagnosticlab/`:

1. `r17_evidenceEnvelopeSeparatesFactsMissingDataAndInferences`: Verifica que una sesión con DTCs y datos ausentes nunca mezcle inferencias dentro de `observedFacts`.
2. `r18_unknownEcuDegradesGracefullyWithoutInventingHealthScore`: Verifica que ante un CALID desconocido o sin respuesta OBD, el motor devuelva estado `UNVERIFIED_PROFILE` / `MANUAL_INSPECTION` y ningún puntaje porcentual global.
3. `r19_exportedEvidenceBundleIncludesSha256AndCatalogVersion`: Verifica que la exportación JSON/PDF contenga el hash SHA-256 válido y la versión del catálogo.
4. `r20_sessionComparisonDetectsResolvedAndNewDtcs`: Verifica la comparación entre una sesión pre-reparación (ej. P0579, P2299) y una post-reparación.
5. `r21_readOnlyPolicyBlocksWriteCommands`: Verifica que cualquier intento de enviar comandos de escritura sea rechazado por la política de comandos.
6. `r22_shiftAndLoadGuidanceUsesObservedRpmSpeedAndLoadSafely`: Verifica la inferencia de marcha por `RPM/Velocidad`, detección de sobreesfuerzo (bajas RPM + alta carga) y que se etiquete como inferencia.
7. `r23_fuelEstimationUsesPid2FOrFallsBackToFuelRateOrMafAndAlwaysLabelsAsInference`: Verifica lectura primaria por PID `0x2F`, respaldo por integración de PID `0x5E` y PID `0x10` (MAF) desde evento manual «Estanque lleno», degradación honesta sin datos y etiquetado obligatorio como `ESTIMACIÓN/INFERENCIA` (nunca medición directa).
8. `r24_foregroundServiceContractSupportsBackgroundObdCaptureAndNotification`: Verifica la declaración de `DiagnosticConnectionService` en `AndroidManifest.xml` (`foregroundServiceType="connectedDevice"`) y que las notificaciones persistentes (`ongoing = true`) reflejen la captura continua en segundo plano y reconexión.
9. `r25_androidAutoCarAppServiceAndManifestDeclareGlanceableReadOnlyDashboard`: Verifica dependencia `androidx.car.app:app`, declaración en `AndroidManifest.xml` + `automotive_app_desc.xml` (categoría `IOT` compatible con modo desarrollador) y contrato de solo lectura de `CarDashboardModel` (estado de enlace, DTCs activos, lecturas clave y combustible estimado como inferencia).
10. `r26_criticalFaultCaptureProfileRestrictsToEightEssentialPids`: Verifica que el perfil «Captura rápida de falla» (`FAST_FAULT`) consulte exclusivamente los 8 PIDs críticos (`0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42`), respete la política de solo lectura y analice eventos de caída de riel/tensión o corte de potencia.
11. `r27_workshopReportIncludesDifferentialDiagnosisAndCriticalSetRecommendation`: Verifica que `WorkshopReport` distinga en Diagnóstico Principal hechos observables vs hipótesis (corte de torque *brake override* P0579/P2299 frente a apagado total por riel `0x23` o tensión `0x42`) y recomiende automáticamente el Set Crítico de 8 PIDs ante pérdida de enlace o datos no capturados en Mode 02.

---

## 3. Criterio de aceptación
- Todas las pruebas previas + `r17`..`r27`, lint y ensamblado APK en verde mediante:
  ```bash
  cmd.exe /c "gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain"
  ```

