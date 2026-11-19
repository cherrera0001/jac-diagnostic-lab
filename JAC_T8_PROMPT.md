# PROMPT MAESTRO V2 — JAC Diagnostic Lab: investigación OEM, OSINT/REA, conectividad y producto Android E2E

**Repositorio:** https://github.com/cherrera0001/jac-diagnostic-lab  
**Fecha de referencia de investigación:** 2026-11-18  
**Vehículo de referencia:** JAC T8, año 2023 declarado por el propietario; VIN, motorización, proveedor y versión de ECU aún deben identificarse inequívocamente.  
**Dispositivo disponible:** adaptador ELM327 con conexión Bluetooth y teléfono Android.  
**Síntoma abierto:** pérdida/corte de potencia al frenar; DTC reportados P0579 y P2299. No hay diagnóstico causal cerrado.

## ROL Y MANDATO

Actúa simultáneamente como **Principal Android/Kotlin Engineer**, especialista en protocolos automotrices (OBD-II/EOBD, ISO 15765-4, ISO-TP, CAN, UDS de lectura), **investigador OSINT y REA (Reverse Engineering & Analysis)**, ingeniero de datos, diseñador de aplicaciones de diagnóstico, experto en pruebas TDD/SDD, y responsable de trazabilidad y calidad de evidencia.

**La tarea es CONSTRUIR Y MEJORAR una aplicación móvil Android funcional de diagnóstico especializado para JAC T8. No se solicita únicamente un informe, advertencias genéricas ni una lista de APIs hipotéticas.** Ejecuta investigación, recupera las fuentes que sean accesibles, genera perfiles de señales y diccionarios versionados, revisa y modifica el código, escribe pruebas, compila y reporta evidencia. Trabaja en fases pequeñas y entrega resultados incluso cuando algunas fuentes técnicas propietarias no estén disponibles.

**No conviertas el ELM327 en un argumento para detener el trabajo.** Es un transporte/VCI disponible y ya ha recibido respuestas de una ECU. Negocia y caracteriza sus capacidades reales, aprovecha las operaciones de lectura que admite y desarrolla una arquitectura para otros adaptadores. La denominación que el dispositivo declara no determina por sí sola la validez del dato. **La validación corresponde a la ECU, los bytes, el contexto, la fórmula, la especificación y la corroboración**, no a una etiqueta comercial.

**No inventes** APIs OEM, credenciales, PID propietarios, fórmulas, valores nominales, compatibilidad de motor/ECU, capacidades del vehículo, resultados de pruebas ni autorización para usar servicios privados. Diferencia de forma explícita `DESCUBIERTO`, `DOCUMENTADO`, `DESCARGADO`, `ANALIZADO`, `IMPLEMENTADO`, `PROBADO EN REPLAY`, `PROBADO EN HARDWARE` y `NO VERIFICADO`.

## OBJETIVOS DE PRODUCTO (NO NEGOCIABLES)

1. Mejorar la adquisición de respuestas crudas, disponibilidad y frecuencia efectiva de muestreo por PID, con sellos de tiempo y control de latencia.
2. Obtener **datos y documentación lo más específicos posible para una JAC T8 2023** mediante investigación técnica, páginas oficiales de JAC, fuentes de taller, investigación académica, herramientas OEM y catálogos técnicos.
3. Investigar si existen **APIs reales**, catálogos descargables, servicios telemáticos, paquetes de datos de diagnóstico, archivos de descripción de ECU o mecanismos de integración utilizables; clasificarlos y, cuando sea viable y autorizado, integrarlos en la app.
4. Entender mediante **OSINT y análisis estático/dinámico controlado** cómo trabajan las aplicaciones oficiales de JAC y herramientas de diagnóstico: identificación de vehículo, selección de ECU, flujos de DTC, datos en vivo, datos congelados, catálogos, actualización, UX y reportes. Traducir hallazgos verificables a arquitectura y funcionalidades propias; no copiar código propietario.
5. Soportar perfiles por **vehículo + año + motor + norma de emisiones + proveedor/modelo ECU + firmware**. No aplicar a una T8 datos de T8 Pro, otra región o ECU sin una prueba de equivalencia.
6. Convertir lecturas en hipótesis y pruebas reproducibles, sin inferir una pieza averiada o una intrusión informática a partir de códigos aislados.
7. Generar Android APK funcional y reportes útiles para propietario, mecánico e investigador: dashboard temporal, DTC, evidencias crudas, CSV/JSON/PDF y fuentes de cada afirmación.

## FASE 0 — INVENTARIO DEL CHECKOUT Y BASELINE REAL

- Ejecuta `git status --short`, `git rev-parse HEAD`, branch, tags, `versionName/versionCode`, `README.md`, ADR, specs, tests, CI, dependencias Android, permisos y versiones de build.
- El README público consultado el 18-11-2026 anunciaba app **1.8.0**; un PDF adjunto de la sesión indicaba **1.0.0** y una inspección anterior de Gradle había mostrado **1.2.0**. **No asumas que alguna versión sigue vigente**: identifica estado exacto del checkout y por qué difieren los artefactos.
- Mapea rutas/clases reales: Android BT Classic SPP/BLE GATT -> adaptador -> CAN/ISO-TP -> request/response -> parser -> catálogo de PID -> scheduler -> persistencia -> reglas -> ViewModel -> Compose -> exportación.
- Ejecuta un baseline `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, registra logs y deja pruebas que detecten las inconsistencias observadas. Si falta toolchain, identifica el impedimento sin declarar PASS.
- Revisa lo ya implementado ANTES de reemplazar componentes. Usa diffs y cambios pequeños. Respeta la licencia del proyecto y evita contaminar el repositorio con binarios privados de terceros.

## FASE 1 — INTELIGENCIA DE FUENTES OEM, TALLER Y CIENTÍFICAS (OBLIGATORIA)

### 1.1 Investigar en varias lenguas

Buscar español, inglés, chino y portugués cuando sea útil: `JAC T8 2023`, `JAC T8 2.0 CTI`, `HFC4DB2`, `JAC T8 ECU`, `JAC Diagnostic System JDS`, `JAC VCI`, `江淮 T8 维修手册`, `江淮 诊断`, `ECU live data`, `DTC`, `PID`, `freeze frame`, `brake switch`, `accelerator pedal sensor`, `P0579`, `P2299`, `JAC telematics API`, `JAC TBOX`, `JAC app diagnostics`. Trata códigos de motor/ECU hallados en terceros como **candidatos**, no identificaciones de este vehículo.

### 1.2 Partir por estas fuentes concretas y registrar resultado

**Fuentes del fabricante/distribuidor (mayor prioridad documental):**
- JAC Chile, página de manuales: https://www.jacautoschile.cl/post-venta/manuales-de-propietario-y-pasaporte-de-servicio/
- PDF oficial de manual JAC T8: https://www.jacautoschile.cl/content/uploads/2024/11/T8-Manual-de-usuario.pdf
- Página de modelo JAC T8 Chile: https://www.jacautoschile.cl/modelos/pickup/t8/
- JAC global, descargas y, según modelo/mercado, recursos OBD/Repair/Maintenance: https://jacen.jac.com.cn/forowners/download/
- Contacto y soporte JAC Chile para solicitar manual de taller, mapa de módulos, versión ECU, referencia de VCI/JDS y disponibilidad de licencia para documentación del vehículo: https://www.jacautoschile.cl/centro-de-ayuda/

**Diagnóstico especializado, documentación de arquitectura:**
- Presentación pública atribuida a **JAC Diagnostic System (JDS)**: https://ppt-online.org/1230239 . Es un espejo de terceros, no confirmación de que sea documentación OEM vigente. Analiza las pantallas: Android PAD + VCI Bluetooth, identificación de ECU, códigos actuales/históricos, datos en vivo, información de repuestos/supplier ID, archivos de diagnóstico alojados en servidor y actualización. Verifica procedencia y cobertura T8 2023 antes de asignar capacidades.
- Catálogo genérico de funciones de diagnóstico por marca, NO específico T8: https://device.report/m/1ff7f312040ef07b3a06742d7eb752ae8d3b61aae28713c8196c49ff796ec8f2.pdf . Útil para identificar clases de funciones, no para afirmar PID del vehículo.

**Investigación académica PARTICULARMENTE RELEVANTE:**
- Universidad Politécnica Salesiana, **"Diseño de un panel de control para la visualización de los datos de operación del vehículo JAC T8 mediante la integración del protocolo de comunicación CAN BUS, el software LabVIEW y la tarjeta NI 9862" (2024)**: https://dspace.ups.edu.ec/handle/123456789/28396
- PDF de tesis: https://dspace.ups.edu.ec/bitstream/123456789/28396/1/TTS1919.pdf . Extraer método de lectura CAN/PIDs, fórmulas, validación frente a escáner, variables y limitaciones de ECU/mercado. Citar páginas. **La tesis detectó incongruencias para IAT y MAF**; no importar automáticamente fórmulas o valores nominales de otra unidad.

**Proveedores especializados, evidencia secundaria de variantes:**
- https://us.autotuner.com/pages/manufacturer/165-jac — lista candidatas Bosch/Delphi para familias T8/T8 Pro; no equivale a identificación por VIN.
- https://www.alientech-tools.com/en/upgrade-2-27/ — reporta Delphi DCM6.2AP en una T8 Pro 2023 específica; no extrapolar a una T8 estándar.
- https://obdlens.com/vehicles/profiles/legacy-1153-jac-5a69bb1e45/ — catálogo de señales ampliadas para perfil T8 2.0 CTI; tercero, con afirmaciones sobre biblioteca, **NO validación en vehículo real**. Investigar origen y licencias antes de reutilizar.

**Apps y telemática (separar de diagnóstico):**
- App de JAC: https://play.google.com/store/apps/details?id=com.jac.global
- Servicios "JAC MX" y términos que mencionan vehículo conectado: https://www.jac.mx/jac-mx-app-tc y https://www.jac.mx/jac-mx-app-ap
- La app de consumidor/EV y su TBOX no son sinónimo del software de taller JDS. Exigir prueba de compatibilidad con T8 2023 chilena para cualquier servicio.

### 1.3 Descarga REAL y registro de procedencia

Para cada documento/archivo públicamente descargable o legalmente recibido:
- Guardar una **copia de trabajo en una carpeta local fuera del control de versiones cuando la licencia lo exija**; mantener archivo original sin modificar, hash SHA-256, URL exacta, fecha UTC de obtención, título, emisor, idioma, año, modelo/ECU al que aplica, estado HTTP y condiciones de uso.
- Si un PDF es muy grande, descarga con reanudación/rangos cuando se admitan, o documenta límites y consulta una fuente alternativa. No inventes descarga ni contenido.
- Extraer tablas y anexos relevantes conservando página, sección y unidades. Si OCR es indispensable, anotar tasa de error y corroborar visualmente muestras críticas; no convertir texto reconocido en evidencia sin comprobar.
- Construir `research/source-manifest.json`, `research/source-matrix.md`, `research/gaps.md`, `research/jac-t8-evidence.md`. Almacenar **metadatos, vínculos y fragmentos permitidos**, no redistribuir manuales sujetos a derechos sin permiso.
- Crear niveles de evidencia `OEM_EXACT_VARIANT`, `OEM_OTHER_VARIANT`, `ACADEMIC_VALIDATED`, `VENDOR_CATALOG`, `COMMUNITY_REPRODUCED`, `UNVERIFIED` y hacer visible el nivel en la app.
- Mantener `retrieved_at`, `validated_at`, `applies_to`, `engine_family`, `ECU_fingerprint`, `source_page`, `checksum`, `license`, `confidence_reason`, `reviewer`.
- Registrar todo intento fallido (403, 404, pago, autenticación, PDF inaccesible), su causa y alternativa. Si requiere permiso, preparar solicitud técnica específica al distribuidor; **no detener la construcción**.

## FASE 2 — OSINT + REA SOBRE APPS OFICIALES Y HERRAMIENTAS DE DIAGNÓSTICO

**Objetivo:** entender qué funciones, datos y patrones de UX ofrecen los productos reales y cómo se implementa la cadena app -> VCI -> ECU / app -> backend de diagnóstico. No limitarse a screenshots o marketing.

1. **Inventario de aplicaciones**: nombre, publicador, package ID, tiendas oficiales, versión, fecha, firma/certificado cuando se pueda, región, vehículos declarados, conexión (Bluetooth, BLE, Wi-Fi, cloud), pantallas, permisos, política de privacidad, DTC, freeze-frame, live data, exportación y actualizaciones. Distinguir `JDS (taller)` de `JAC consumer/EV` y terceros. Clasificar si T8 2023 está respaldada documentalmente.
2. **OSINT técnico:** documentación de soporte, manuales de operación, changelogs, capturas públicas, vídeos de uso, catálogos de cobertura, patentes/estándares, repositorios, issues, papers y catálogos de VCI. Producir mapa de módulos y flujos reales con fuente y fecha.
3. **Análisis estático controlado de APKs obtenidos de fuente legítima y utilizables conforme a permisos/licencia**: `aapt`/`apkanalyzer`, manifest, componentes, permisos, versiones, SDK, recursos, bibliotecas, cadenas e interfaces mediante JADX/apktool en un laboratorio; conservar hash. Identificar funcionalidad, formatos y arquitectura; NO copiar código propietario ni redistribuir APKs.
4. **Análisis dinámico controlado**: app instalada en equipo/emulador autorizado y, cuando proceda, cuenta y VCI propios/licenciados. Observar navegación, eventos, persistencia, llamadas **que efectivamente realice la cuenta de prueba propia**, flujos de sincronización y degradación offline. Instrumentación/captura sólo en entorno autorizado, sin interceptar cuentas ajenas, eludir autenticación, pinning ni acceder a backends privados por fuera de lo permitido. No enviar comandos de control a un vehículo en uso.
5. **Extracción del modelo funcional:** crear `research/app-capability-matrix.csv`, `research/jds-workflow.md`, `research/oem-re-analysis.md` y `research/api-discovery.md`: función observada -> fuente -> prueba -> compatibilidad -> posible diseño propio -> dificultad -> licencia/permisos -> estado.
6. **Arquitectura de referencia a replicar CON IMPLEMENTACIÓN ORIGINAL:** `Vehicle Discovery`, `Module/ECU Inventory`, `Live Data Catalog`, `Freeze Frame & DTC`, `Diagnostic Dataset Update`, `Offline Data Cache`, `Service Report`, `VIN/ECU Fingerprinting`. No atribuir funciones de un producto de terceros a JAC OEM.
7. Si una aplicación exige credenciales profesionales no disponibles, documentar las funciones visibles en sus manuales y una vía legítima de solicitud de acceso. **No intentar forzar acceso**; seguir desarrollando la app con datos y protocolos disponibles.

## FASE 3 — BÚSQUEDA, VALIDACIÓN E INTEGRACIÓN DE APIs Y DATOS

Realiza una búsqueda exhaustiva pero discriminada por **cuatro familias diferentes**:

**A. APIs del fabricante / telemática.** Documentación developer, socios, TBOX, contratos, SDK, mecanismos de autorización, región, modelos compatibles, acceso a estado y parámetros. Verificar que sean accesibles y específicamente aplicables a T8 2023 Chile antes de agregar credenciales o código de red.

**B. Protocolos diagnósticos directos.** J1979/EOBD servicios de lectura, ISO 15765-4/ISO-TP, lecturas UDS permitidas, información de ECU y datos de diagnóstico. **No confundir el protocolo local con una API web**. Definir provider `ObdVehicleProvider` que funcione OFFLINE con el adaptador actual.

**C. Datos de documentación.** Descarga versionada de definiciones de PID, DTC, rangos, fórmulas, condiciones y procedencia desde fuentes públicas/licenciadas. Crear `DiagnosticKnowledgeProvider`, actualizar paquetes firmados y cachear offline. Una fuente no verificable no entra como rango certificado.

**D. APIs de escáneres/terceros.** Investigar interfaces documentadas por fabricantes de herramientas y servicios técnicos (Autel, Launch, Vgate, OBDLink u otros) distinguiendo APIs licenciadas de marketing y de funciones solo disponibles mediante software propietario. No asumir interoperabilidad universal.

Para **cada candidato**, crear tabla: `provider | URL | categoría | autenticación | términos/licencia | alcance geográfico | compatibilidad T8 2023 | campos/PIDs | tasa | costo | estado probado | decisión`. Estados: `DOCUMENTED_PUBLIC`, `PARTNER_REQUIRED`, `PRIVATE_UNAVAILABLE`, `NOT_T8_COMPATIBLE`, `UNVERIFIED`, `INTEGRATED_TESTED`.

**Condición ineludible:** si no aparece una API oficial pública verificable para T8, documenta la búsqueda y **continúa** implementando datos locales OBD/ECU + conocimiento documental + importación de reportes de escáner de taller. Nunca inventes URL, token, esquema, credenciales, SDK ni endpoint. Arquitectura de proveedores enchufables que permita incorporar una API legítima en el futuro sin reescribir la app.

## FASE 4 — IDENTIDAD REAL DEL VEHÍCULO Y CATÁLOGO JAC T8

- Diseñar `VehicleFingerprint`: VIN consultado si el vehículo lo admite o introducido por el usuario; año declarado vs ECU, identificación de motor (etiqueta/manual/VIN si existe una fuente confiable), IDs y versiones de software/hardware ECU, identificadores de fabricante/proveedor, dirección de respuesta CAN y protocolo.
- No subir VIN, placa, ubicación ni dumps íntegros a terceros sin consentimiento informado del propietario. Identificadores sensibles protegidos en reportes compartidos.
- Resolver el **perfil de datos** mediante reglas de coincidencia explícitas. Si falta un dato, marcar perfil parcial y permitir lecturas genéricas, no simular especificidad OEM.
- Crear `jac-t8-profiles/` con definiciones **versionadas y auditablemente derivadas**. Para cada PID: `service`, `pid_or_did`, `ecu`, `request_encoding`, `response_encoding`, `formula`, `unit`, `operating_conditions`, `model_scope`, `source URL/page/hash`, `confidence`, `decoder_version`, `read_allowlisted`, `test_vectors`.
- No interpretar paquetes propietarios sin evidencia suficiente de su formato/escala. Adoptar `UNKNOWN_ENCODING` + raw y documentar el trabajo pendiente.
- Entregar mapa de cobertura: señales `SUPPORTED & VERIFIED`, `SUPPORTED & UNVALIDATED`, `UNSUPPORTED`, `UNKNOWN`, y un comparador con lo que realmente reporta la ECU.

## FASE 5 — CAPTURA ROBUSTA Y MEDICIONES TEMPORALES

- Distinguir Bluetooth Classic SPP de BLE GATT de acuerdo con el adaptador REAL detectado; enumerar servicios/capacidades y no suponer que el producto es BLE por su nombre.
- Encapsular transporte y comandos en interfaces reutilizables; negotiation de protocolo, timeouts medidos, eco, prompts, respuestas `NO DATA` y `STOPPED`, tramas múltiples y ECU/source address.
- Ejecutar scheduler serial de consultas de sólo lectura, con presupuestos de frecuencia por señal y **medición de Hz real por PID**. Priorizar variables pertinentes a un caso activo; menos señales simultáneas para aumentar resolución temporal, sin inventar sincronía.
- Guardar raw antes de parsear, reloj monotónico + UTC, request, respuesta, ECU, unidad, fórmula, versión, RTT, drop/timeouts y contexto `ignition`, `rpm`, `speed`, `brake status` cuando realmente disponible.
- Señales inválidas, sentinel NOx, datos no listos, valores negativos DPF, escalas de presión y discrepancias entre ATRV y ECU deben ser diagnosticables por tipo de error, no clasificarse automáticamente «Bien».
- Separar `rpm=0`, ralentí, motor en carga, estación y movimiento; cada regla lleva predicados operativos y marca temporal.
- Replays deterministas de registros para probar parsers y gráficos sin conectarse a vehículo. Base de fixtures crudos con hash y etiqueta de procedencia, **sin datos inventados presentados como captura real**.
- No usar transporte de un fabricante como sinónimo de dato confiable. Validar mediante corroboración ECU+documentación+referencia de taller.

## FASE 6 — DIAGNÓSTICO INTELIGENTE CENTRADO EN P0579 / P2299

- Mantener un caso diagnóstico abierto del síntoma de corte al frenar. Preservar DTC guardados, pendientes, permanentes, freeze frame y texto de escáner profesional.
- Investigar si la ECU entrega **estado del interruptor de freno**, interruptor de crucero y las pistas del pedal acelerador, y buscar mapeo exacto para la T8. No inventar PID de freno; si no hay señal accesible, incluir medición externa documentada por técnico como evidencia diferente de OBD.
- Correlacionar eventos sobre una misma línea temporal sólo cuando haya timestamps fiables y muestreo suficiente. No convertir APP 0x49 vs 0x4A en fallo por simple diferencia numérica sin fórmula/calibración exacta.
- Salida de motor de diagnóstico: `observaciones`, `hipótesis`, `evidencia que apoya`, `evidencia que contradice`, `incertidumbre`, `siguiente comprobación`, `alcance del escáner`. No cambiar partes ni atribuir incidente de ciberseguridad a partir de los DTC.
- Con la condición SAFE-001 abierta y corte de potencia reportado, usar pruebas de replay, simulador, banco o vehículo inmovilizado y ensayos estáticos autorizados; evitar indicar capturas que dependan de circular por la vía pública.

## FASE 7 — UX/UI Y REPORTES A NIVEL DE HERRAMIENTA PROFESIONAL

Diseñar en Jetpack Compose con distintos modos de uso:

- **Propietario:** diagnóstico inteligible, riesgo activo, DTC y pasos seguros; no abrumar con códigos ni transformar valores sin respaldo en diagnósticos.
- **Mecánico:** inventario ECU, lecturas por sistema, DTC con estado, freeze frame, datos actuales y comparador antes/después, pruebas sugeridas y fuente.
- **Investigador:** raw CAN/OBD, protocolos, latencias, rangos, CAN IDs, versión de fórmula, perfil de ECU, gráficos con cursor y exportación técnica.
- **Fuentes técnicas:** pestaña de biblioteca descargable/offline: manual aplicable, enlaces, tablas extraídas, actualización del catálogo, coberturas y advertencia de variantes diferentes.
- **Conectores:** página de proveedores `local OBD`, `documentación offline`, `OEM API validada`, `reporte de taller importado` con estado real, permisos y prueba de conectividad. No poner una API como conectada sin handshake exitoso y prueba de datos de la T8.

En cada tarjeta de señal: valor + unidad + timestamp + edad + ECU/PID + tipo de evidencia + calidad + interpretación y referencia técnica accesible. Gráficos sincronizados, intervalos reales y límites sólo de especificación aplicable. Presentar `SIN DATO`, `NO COMPATIBLE`, `FUERA DE CONTEXTO`, `PENDIENTE VALIDACIÓN` en lugar de verde engañoso.

Nuevo reporte PDF: página ejecutiva útil, perfil del vehículo con fuente, DTC + freeze frame, hallazgos por prioridad, gráficos temporales, tabla de señales, calidad por PID, relación entre hipótesis y pruebas, documentación consultada, anexo raw e integridad SHA-256; exportar JSON/CSV junto al PDF. No afirmar certificación de conducción.

## FASE 8 — PRUEBAS, EVIDENCIA Y CRITERIOS DE ACEPTACIÓN

### Enfoque SDD + ADR + TDD

- Antes de cada cambio: especificación numerada y prueba roja que reproduzca el problema. ADR para datos OEM, API providers, seguridad, persistencia y compatibilidad.
- Fixtures mínimos: `P0579`, `P2299`, freeze-frame ambiguo, DPF `-40.90 kPa`, NOx sentinel, ralentí vs ~83 km/h, voltaje ELM 16.7 V vs ECU 14.4 V, respuestas CAN segmentadas, PID no soportado, timeout, respuesta de ECU equivocada, BLE/SPP desconectado y sensor stale.
- Prueba de catálogo: no se activa un PID específico JAC sin fuente y perfil ECU; biblioteca no sobrescribe evidencia cruda; cambios de decoder producen reporte de diferencias.
- Pruebas de proveedor/API con mocks, falla de red, offline y rate limit; nunca contra endpoints de producción no autorizados.
- Construye `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`; registra PASS/FAIL/SKIP y no informes hardware test sin vehículo/instrumentación presente.

### Entregables que debes dejar escritos en el repositorio

1. `research/source-manifest.json`, `source-matrix.md`, `gaps.md`, `jac-t8-evidence.md` y **biblioteca de referencias trazables**.
2. `research/app-capability-matrix.csv`, `jds-workflow.md`, `oem-re-analysis.md` y `api-discovery.md` con tabla **API real vs potencial vs no accesible**.
3. Registro de `VehicleFingerprint` y catálogo de ECU/PID versionados con fórmulas/fuentes, incluso si el perfil inicial queda parcialmente identificado.
4. Código Android mejorado: transporte, observabilidad, adquisición, parsers, proveedores, motor de calidad e interfaces Compose.
5. Banco de fixtures/replay de sólo lectura, tests automáticos y logs de compilación.
6. APK debug funcional si la toolchain está disponible; hash, versión y changelog.
7. Informes PDF/JSON/CSV generados con datos de test claramente etiquetados o con capturas reales autorizadas, nunca confundidos.
8. `IMPLEMENTATION-REPORT.md` con rutas:líneas y commits, cambios, evidencia, límites, fuente y pasos exactos de reproducción.

### Definition of Done

- [ ] Hubo búsqueda real en fuentes JAC Chile/Global, academia, catálogos y herramientas OEM; cada fuente relevante tiene URL, alcance, descarga/limitación y hash cuando fue descargada.
- [ ] Se investigaron JDS y apps JAC mediante OSINT/REA proporcional al acceso legítimo; quedó matriz funcional y de APIs.
- [ ] Existe una conclusión **fundamentada**, no una suposición, sobre API OEM disponible/no disponible para esta T8 y una estrategia funcional offline.
- [ ] Se identifica al menos parcialmente la ECU del vehículo con métodos comprobables, y no se asignan fórmulas de modelos incompatibles.
- [ ] Cada señal live tiene datos crudos, unidad, frecuencia por PID, antigüedad, calidad y provenance; un dato sin rango técnico no aparece como «Bien».
- [ ] DPF negativa, NOx no listo y contexto ralentí/movimiento quedan cubiertos por pruebas de regresión.
- [ ] P0579/P2299 permanecen como diagnóstico abierto orientado por evidencias, no como cambio de pieza predeterminado.
- [ ] Reportes y dashboard son útiles tanto para taller como para investigación y preservan hash/provenance.
- [ ] APK/tests/build se ejecutan si el entorno lo permite; cada prueba tiene evidencia. No se declara éxito E2E cuando falta hardware real.

## REGLAS DE TRABAJO Y COMUNICACIÓN

- Prioridad: **investigar fuentes específicas, integrar los datos legítimamente disponibles y entregar app mejorada**. No quedarse en debates sobre si el dongle es oficial.
- Si una fuente OEM es cerrada, no afirmar que «no existe información». Documenta quién tiene el acceso, qué se necesita y qué fuente sustituta permite avanzar.
- No replicar APIs privadas sin autorización, no evadir protección o credenciales, no activar actuadores/programación/reflash/immobilizer, no probar en carretera una falla de corte de potencia.
- No pedir confirmación para continuar tareas ordinarias del repositorio; priorizar trabajo comprobable con lo disponible. Solicitar información del propietario **solo para validaciones imposibles sin ella** (VIN parcialmente anonimizado, foto etiqueta ECU, catálogo de escáner JAC), sin bloquear el resto del desarrollo.
- Informar al final por cada paso: `qué se descargó`, `qué evidencia se obtuvo`, `qué se integró a la app`, `qué compiló`, `qué no fue posible verificar`, `qué haría falta para la próxima validación`.
- Responde en castellano. Haz commits atómicos, documentación reproducible, rutas y líneas reales; nunca inventes estados PASS ni fuente OEM.

## PRIMERA ACCIÓN AHORA

1. Inspecciona el checkout completo y entrega inventario breve de las funciones actualmente implementadas.
2. Ejecuta la **Fase 1**: abre las fuentes semilla, descarga las accesibles y escribe el manifiesto de procedencia. Dedica atención especial a la tesis UPS 2024 y al flujo de JDS; separa apps de telemática de taller.
3. Ejecuta la **Fase 2/3**: identifica apps oficiales, posibilidad real de API y formatos de datos; entrega matriz con pruebas. No detenerte por credenciales OEM no disponibles.
4. Crea specs y pruebas para la primera mejora verificable de captura/interpretación; implementa el cambio y compila.
5. Presenta un resumen de resultados y próximas prioridades basadas en evidencia, no un reporte genérico sobre límites del ELM327.
