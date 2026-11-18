# Changelog

## v1.11.0 — 2026-11-18 (JAC Diagnostic Lab) — validada en la unidad real

Primera lectura completa de la unidad con la app nueva: [research/validation/2026-11-18-unidad-real.md](research/validation/2026-11-18-unidad-real.md).

- **ECU confirmada por lectura: Delphi DCM6.2** (CALID 0904 = `DCM6.2`, CVN `A7BB65AE`). El catálogo suma «DCM6.2» como identificador de familia (S21): el perfil pasa de `PARTIAL` a `IDENTIFIED`. La variante AP / AP-6D sigue sin determinar.
- **Informe de taller profesional:** PDF con diseño propio (`report/WorkshopPdf.kt`): encabezado con ficha del vehículo, resumen con semáforo y códigos, **diagnóstico principal primero** (significado práctico del grupo de códigos, contexto del cuadro congelado, historial de borrado, pasos en orden), hallazgos con etiquetas de gravedad, tablas con columnas, «página X de Y» y espacio de firma del taller.
- Correcciones con datos reales: código con varias categorías una sola vez; `7F 0A 13` = «servicio no admitido»; ceros imposibles del cuadro congelado marcados «no capturado»; sin alerta de frío con el motor recién encendido; el PDF se guarda una sola vez.
- **Pestaña Estado con detalle:** cada problema, aviso y código se toca para ver qué es, con qué dato se detectó y qué revisar; botón «Ver los N datos leídos».
- **Prueba nueva «Freno contra acelerador» (P2299/P0579):** detecta si la ECU corta el par al frenar con el acelerador pisado y si las pistas del pedal van en proporción. Pendiente de ejecutar en la unidad.
- **Enlace BLE:** reconexión automática (hasta 3) cuando el clon v2.1 se cuelga; un escaneo sin respuesta ya no borra los datos buenos.

## v1.10.1 — 2026-11-18 (JAC Diagnostic Lab)

Corrección encontrada en el vehículo: tras un comando sin respuesta (020200) Android rechazaba las escrituras GATT y la app registraba «TIMEOUT 0 ms» para siempre. Ahora reintenta, declara el enlace caído, cancela el comando pendiente del ELM con CR y detiene el monitor tras 12 fallos seguidos.

## v1.10.0 — 2026-11-18 (JAC Diagnostic Lab)

Informe para el taller y ajuste de dimensiones. [SPEC-033](specs/033-workshop-report/spec.md)

- **Informe de taller (pestaña Informe):** «Leer todo y generar informe» ejecuta el escaneo completo si falta algo y genera el PDF solo al terminar. Si se genera igual, dice «INCOMPLETO» y qué falta (R13).
- Contenido (R14): vehículo y ECU (VIN y año ISO 3779, CALID/CVN por ECU, perfil con fuentes); resumen (MIL, DTC con categoría, salud ATENCIÓN/REVISAR, monitores incompletos, Mode 06 fuera de límite); **detalle por código** con los datos relacionados ahora y en el cuadro congelado y pasos de verificación genéricos (J2012 + práctica de taller, marcados como no-JAC); cuadro congelado completo; todas las señales con bytes; monitores; Mode 06; salud; registro de comunicación (comandos por tipo, latencias, fallos, errores).
- Se guarda en Descargas/JAC-lectura: `JAC-taller-<sesión>.pdf` + `.sha256` + `.json` con cada comando y sus bytes.
- **Cuadro congelado completo:** el escaneo lee `02PP00` (Mode 02, solo lectura) para 18 PID cuando hay código guardado.
- **Dimensiones (R15):** la cabecera usa el alto real de la barra de estado; barra de pestañas de 60 dp; pie de versión sobre la barra del sistema. Casilla «Ocultar VIN» legible sobre fondo oscuro.
- Límite: no existe información oficial JAC pública para taller de la T8 (manual, valores nominales). El informe usa lo que responde la ECU y definiciones públicas, y lo dice.

## v1.9.0 — 2026-11-18 (JAC Diagnostic Lab)

Identificación de ECU y perfil JAC T8 por evidencia observable. [SPEC-031](specs/031-vehicle-fingerprint-profiles/spec.md) [ADR-002](adr/ADR-002-perfil-por-evidencia-observable.md) [research/](research/)

- Botón «Identificar ECU (Mode 09)» en Capacidad: 0900 y luego 0902 (VIN), 0904 (CALID), 0906 (CVN), 090A (nombre ECU) si la ECU los declara. Cada respuesta guarda raw, hora, latencia y valores **por cabecera de ECU** (R11). El escaneo completo usa el mismo módulo.
- VIN: se lee directamente cuando la ECU lo declara; ya no hay casilla de autorización. La exportación sigue ofreciendo anonimizarlo para compartir.
- Perfil del vehículo: cruza CALID, nombre de ECU y el texto de la etiqueta física con candidatos documentados — Delphi DCM6.2AP, DCM6.2AP-6D, familia JAC P/N 1026400FD020, Delphi MT80, Bosch EDC17C81 y Bosch MD1CS089 — sin mezclar modelos. Estados `IDENTIFIED`, `SUPPLIER_ONLY`, `AMBIGUOUS`, `PARTIAL` (R6). Año del VIN (ISO 3779) contra el declarado (R5).
- Catálogo versionado `jac-t8-profiles/catalog-v1.json` con fuente, nivel de evidencia y hash por entrada; una prueba impide que se aparte del código (R9). Las señales OEM de terceros quedan `UNKNOWN_ENCODING` y no se consultan (R7). Mapa de cobertura frente al bitmap de la ECU (R8).
- Mapa Mode 01 por páginas en función pura probada (R10; cierra SPEC-022 req. 5).
- JSON exportado: `extended.profile` y `extended.ecuIdentification`.
- Investigación: 37 fuentes con SHA-256 (JAC Chile/China/Global, tesis UPS 2024, JDS, XTOOL, Dimsport, AutoTuner, Alientech, MDC Repuestos…). La API pública de JAC Global no incluye la T8.
- Nota: la mención «ECU Delphi DCM6.2» de v1.8.0 no tiene una respuesta Mode 09 archivada que la respalde; queda como no verificada hasta la próxima identificación.
- Versión siempre visible: pie central bajo las pestañas («JAC Diagnostic Lab v1.9.0 (22)»), leída del paquete instalado. La misma cadena va en el pie del PDF, la primera línea del TXT y `appVersion` del JSON. [SPEC-032](specs/032-app-version-visible/spec.md)
- Corrección: el pie del PDF decía «JAC Diagnostic Lab 1.0.0» fijo en todas las versiones; por eso informes de sesión mostraban 1.0.0.
- Sin cambios de política: 04, UDS de escritura, calibraciones y actuadores siguen bloqueados.

## v1.8.0 — 2026-11-18 (JAC Diagnostic Lab)

Rediseño completo y diagnóstico de salud real. Validado contra la JAC T8 con el motor en marcha (ECU Delphi DCM6.2, CAN 11/500).

- Interfaz nueva: Estado (veredicto + mosaicos por sistema), En vivo (medidores con mini-gráfico), Pruebas, Datos (señales agrupadas), Informe. Lo anterior queda en «Herramientas avanzadas».
- Escaneo completo: cada PID que la ECU declara (68 en esta unidad, 82–84 señales), con catálogo J1979 de varias señales (riel, turbo, EGR, DPF, EGT, NOx, torque, regeneración).
- Peticiones multi-PID (hasta 6 por trama CAN), con respaldo PID a PID.
- Monitores 0101/0141 decodificados como diésel o gasolina. La mariposa abierta en ralentí ya no se marca en diésel (DIAG-002).
- Mode 06 (pruebas a bordo, mín/máx, aprobado/fallado) y Mode 09 (calibración, CVN, nombre ECU, rendimiento en uso). VIN sigue exigiendo autorización.
- Evaluación de salud por sistema con reglas genéricas de taller. No es especificación JAC y no hay porcentaje global.
- Pruebas guiadas: ralentí, calentamiento/termostato, aceleración libre, carga eléctrica y registro en ruta/banco con detección de corte de potencia (SAFE-001), falta de turbo, caída de tensión, regeneración DPF y marca manual. Cada prueba se guarda como CSV.
- Política: se permiten 01 multi-PID, 06xx, 09xx y tres comandos exactos de control de flujo ISO-TP. 04, UDS de escritura y cabeceras siguen bloqueados.
- Correcciones: reintento de 0100 mientras la ECU despierta; protocolo CAN deducido por la cabecera; relectura de DTC fallidos; un toque duplicado ya no muestra error; CSV con punto decimal en cualquier idioma.
- Limitación conocida: el clon ELM327 v2.1 corta las respuestas ISO-TP tras 4 tramas consecutivas, incluso con FC estándar. Algunas pruebas Mode 06 y contadores 090B llegan incompletos y se marcan.

## v0.6.0 — 2026-11-11

Copia muestra el PDF junto a JSON, TXT y CSV. El archivo se arma al pulsar y no se crea si la sesión no tiene contenido. [R1](specs/001-exportacion/spec.md) [R2](specs/001-exportacion/spec.md) [ADR-001](adr/ADR-001-el-archivo-se-arma-al-pulsar.md)

## v0.5.0 — 2026-11-11

La pestaña Seguridad muestra nueve pasos de validación, cada uno con un resultado. No incluye lecturas de una unidad.

- Resultados posibles: comprobado en esta aplicación, te toca a ti, o sin comprobar.
- No hay porcentaje de seguridad. Comprobado no autoriza a circular.
- El mismo listado entra en el PDF local.
- El detalle técnico queda detrás de un botón.

## v0.4.0 — 2026-11-11

La pantalla de entrada guía la lectura en ocho pasos numerados y guarda un informe PDF local con su huella. No incluye lecturas de una unidad.

- Pestaña Hoy: un paso a la vez, con el rol de quien lo hace.
- El PDF y el archivo `.sha256` se escriben en Descargas, carpeta `JAC-lectura`. La huella del archivo no va dentro del PDF.
- El texto del informe traduce solo números conocidos y no condena una pieza.
- Pruebas: `ProtocolTest` 15, `SecurityPolicyTest` 9, `ObdCommandPolicyTest` 4. Cero fallos.
- El informe de una medición sigue fuera del repositorio.

## v0.2.0 — 2026-11-11

Corte de software de la evaluación. No incluye lecturas de una unidad.

- Allowlist de solo lectura. El servicio `04` no se envía.
- Dashboard Seguridad y modelo TARA T-001 a T-010.
- README de entrada con el proceso, los diagramas y el uso.
- Pruebas: `ProtocolTest` 15, `SecurityPolicyTest` 6, `ObdCommandPolicyTest` 4. Cero fallos.
- El informe de una medición queda fuera del repositorio.

## v0.1.0 — 2026-11-11

Primera APK de diagnóstico de solo lectura: enlace Classic y BLE, sesión ELM, lectura de DTC y exportación local.
