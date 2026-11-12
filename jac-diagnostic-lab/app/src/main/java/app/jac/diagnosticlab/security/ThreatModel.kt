package app.jac.diagnosticlab.security

data class ThreatScenario(
    val id: String,
    val title: String,
    val asset: String,
    val precondition: String,
    val adversaryGoal: String,
    val entrySurface: String,
    val trustBoundary: String,
    val potentialImpact: String,
    val feasibility: String,
    val controlsVerified: List<String>,
    val controlsUnknown: List<String>,
    val evidence: List<String>,
    val residualRisk: String,
    val proposedMeasure: String,
    val validationMethod: String,
)

data class HardeningItem(
    val layer: String,
    val action: String,
    val status: String,
    val reversible: Boolean,
    val note: String,
)

object ThreatModelEngine {
    fun baselineFromSession(
        bleValidated: Boolean,
        ecuTalking: Boolean,
        ati: String,
        dtcCodes: List<String>,
    ): List<ThreatScenario> {
        val btEvidence = if (bleValidated) {
            listOf("Sesión real: GATT FFF1/FFF2 + ATI")
        } else {
            listOf("BLE no validado en esta sesión")
        }
        return listOf(
            ThreatScenario(
                id = "T-001",
                title = "Dispositivo Bluetooth desconocido intenta acceder al adaptador",
                asset = "Autenticación / capacidad de diagnóstico",
                precondition = "Adaptador energizado y anunciable o previamente vinculado",
                adversaryGoal = "Establecer enlace y emitir comandos AT/OBD",
                entrySurface = "Bluetooth Classic/BLE del ELM327",
                trustBoundary = "Teléfono ↔ adaptador",
                potentialImpact = "Lectura de datos; con otra app, posible borrado DTC u otras operaciones",
                feasibility = if (bleValidated) "Media-alta en presencia física cercana (enlace propio demostrado)" else "No medida",
                controlsVerified = if (bleValidated) listOf("Esta APK usa allowlist ObdCommandPolicy") else emptyList(),
                controlsUnknown = listOf(
                    "Si el adaptador exige emparejamiento frente a un segundo teléfono no autorizado",
                    "Si el PIN es modificable o predeterminado",
                ),
                evidence = btEvidence,
                residualRisk = "UNKNOWN hasta prueba Nivel A con segundo teléfono propio o Nivel B aislado",
                proposedMeasure = "Retirar el ELM327 cuando no se use; inventariar vínculos",
                validationMethod = "Segundo dispositivo propio, vehículo estacionado, sin fuerza bruta",
            ),
            ThreatScenario(
                id = "T-002",
                title = "ELM327 con credenciales o identidad débiles/imitables",
                asset = "Integridad de las comunicaciones / autenticación",
                precondition = "Adaptador genérico instalado en OBD-II",
                adversaryGoal = "Confiar en una identidad falsa o reutilizar acceso trivial",
                entrySurface = "ATI / emparejamiento Bluetooth",
                trustBoundary = "Adaptador ↔ dueño",
                potentialImpact = "Falsa sensación de autenticidad; acceso no monitoreado",
                feasibility = if (ati.contains("ELM327", true)) "Alta para clones que imitan ATI" else "Indeterminada",
                controlsVerified = emptyList(),
                controlsUnknown = listOf("Procedencia real del hardware", "Capacidad de cambiar PIN"),
                evidence = if (ati.isNotBlank()) listOf("ATI=$ati (imitable)") else emptyList(),
                residualRisk = "Presente mientras el adaptador genérico permanezca expuesto",
                proposedMeasure = "No usar ATI como prueba de autenticidad; retirar adaptador",
                validationMethod = "Documentación del fabricante + Nivel B",
            ),
            ThreatScenario(
                id = "T-003",
                title = "Aplicación móvil no confiable usa el adaptador",
                asset = "Capacidad de diagnóstico / integridad de evidencias",
                precondition = "Adaptador emparejado y accesible al teléfono",
                adversaryGoal = "Enviar comandos fuera de política (p. ej. 04)",
                entrySurface = "App Android de terceros",
                trustBoundary = "App ↔ adaptador",
                potentialImpact = "Borrado de DTC, pérdida de evidencia, operaciones no autorizadas",
                feasibility = "Alta conceptualmente: la allowlist de esta APK no vincula al adaptador",
                controlsVerified = listOf("ObdCommandPolicy bloquea 04 y no-allowlist"),
                controlsUnknown = listOf("Comportamiento de otras apps instaladas"),
                evidence = listOf("Advertencia arquitectónica en ObdCommandPolicy"),
                residualRisk = "Alto si el dongle permanece emparejado",
                proposedMeasure = "Desemparejar tras uso; revisar apps con Bluetooth",
                validationMethod = "Revisión de apps + Nivel B",
            ),
            ThreatScenario(
                id = "T-004",
                title = "Dispositivo de diagnóstico comprometido intenta operaciones no permitidas",
                asset = "Integridad del software / disponibilidad de ECU",
                precondition = "Herramienta con canal OBD abierto",
                adversaryGoal = "Reprogramar, actuar o borrar memoria",
                entrySurface = "OBD-II / ELM327",
                trustBoundary = "Herramienta ↔ vehículo",
                potentialImpact = "Pérdida de evidencia, cambios no autorizados",
                feasibility = "No ensayado contra el vehículo (Nivel A lo prohíbe)",
                controlsVerified = listOf("Nivel A allowlist de solo lectura en esta APK"),
                controlsUnknown = listOf("Filtrado real del gateway JAC en esta unidad"),
                evidence = if (ecuTalking) listOf("0100 respondió en sesión real") else emptyList(),
                residualRisk = "Depende de gateway/UDS no medidos en esta unidad",
                proposedMeasure = "Solo lectura; ensayos adversariales solo en lab",
                validationMethod = "Nivel C simulador; nunca contra camioneta",
            ),
            ThreatScenario(
                id = "T-005",
                title = "La interfaz de diagnóstico expone datos que exceden su propósito",
                asset = "Confidencialidad de datos",
                precondition = "Sesión OBD activa; VIN autorizado o logs verbosos",
                adversaryGoal = "Obtener identificadores o telemetría",
                entrySurface = "0902 / exports / logcat",
                trustBoundary = "App ↔ almacenamiento / export",
                potentialImpact = "Exposición de VIN/MAC",
                feasibility = "Media si se exporta sin anonimizar",
                controlsVerified = listOf("VIN solo con autorización", "Export con anonimización opcional"),
                controlsUnknown = emptyList(),
                evidence = listOf("Evidence export + preferencia authorizeVin"),
                residualRisk = "Bajo si se anonimiza; medio si se comparte crudo",
                proposedMeasure = "Anonimizar por defecto; no versionar VIN/MAC",
                validationMethod = "Tests de anonymize + revisión de evidence/captured",
            ),
            ThreatScenario(
                id = "T-006",
                title = "Pérdida de comunicación genera diagnósticos inconsistentes",
                asset = "Integridad de las evidencias",
                precondition = "Enlace Bluetooth inestable o ECU no responde",
                adversaryGoal = "N/A (disponibilidad/integridad de diagnóstico)",
                entrySurface = "Enlace RF / bus",
                trustBoundary = "App ↔ evidencia",
                potentialImpact = "Falsos negativos de DTC o de seguridad",
                feasibility = "Observada en intentos fallidos previos",
                controlsVerified = listOf("Kinds de respuesta explícitos", "NO DATA ≠ ausencia de DTC"),
                controlsUnknown = emptyList(),
                evidence = listOf("Transcripciones con TIMEOUT/PARTIAL/NO_DATA"),
                residualRisk = "Bajo si se preservan crudos",
                proposedMeasure = "Conservar bytes crudos; marcar sesiones incompletas",
                validationMethod = "Revisión de exports JSON/TXT",
            ),
            ThreatScenario(
                id = "T-007",
                title = "Componente externo sin actualizaciones o soporte",
                asset = "Integridad del software / autenticación",
                precondition = "Dongle genérico sin canal de update verificable",
                adversaryGoal = "Explotar firmware antiguo o inauditable",
                entrySurface = "Firmware del adaptador",
                trustBoundary = "Proveedor ↔ dueño",
                potentialImpact = "Superficie permanente desconocida",
                feasibility = "Alta para clones sin soporte",
                controlsVerified = emptyList(),
                controlsUnknown = listOf("Canal de actualización del adaptador", "Firma de firmware"),
                evidence = if (ati.isNotBlank()) listOf("ATI=$ati sin prueba de update") else emptyList(),
                residualRisk = "Aceptado mientras se use clon genérico",
                proposedMeasure = "Preferir dispositivos con soporte documentado; retirar cuando no se use",
                validationMethod = "Documentación de proveedor + Nivel B",
            ),
            ThreatScenario(
                id = "T-008",
                title = "Operaciones sobre el adaptador podrían interferir con funciones del vehículo",
                asset = "Disponibilidad de sistemas / seguridad de ocupantes",
                precondition = "Comandos no estándar o control de actuadores",
                adversaryGoal = "Alterar comportamiento del vehículo",
                entrySurface = "OBD-II vía ELM327",
                trustBoundary = "Adaptador ↔ ECU / redes internas",
                potentialImpact = "Comportamiento indeterminado; riesgo funcional",
                feasibility = "No ensayado contra el vehículo (prohibido en Nivel A)",
                controlsVerified = listOf("Nivel A solo lectura", "Sesión real sin servicio 04"),
                controlsUnknown = listOf("Gateway/filtrado en esta unidad", "Alcance de modos propietarios"),
                evidence = if (ecuTalking) listOf("ECU respondió a 0100") else emptyList(),
                residualRisk = "Depende de arquitectura no medida en esta unidad",
                proposedMeasure = "Mantener solo lectura; adversariales solo en lab",
                validationMethod = "Nivel C; no contra camioneta",
            ),
            ThreatScenario(
                id = "T-009",
                title = "Exposición innecesaria de datos identificadores",
                asset = "Confidencialidad",
                precondition = "Logs, exports o commits con VIN/MAC",
                adversaryGoal = "Reidentificación",
                entrySurface = "Repositorio / logcat / exports",
                trustBoundary = "Evidencia ↔ terceros",
                potentialImpact = "Filtración de identificadores",
                feasibility = "Media si no se redacta",
                controlsVerified = listOf("Redacción en evidence/captured versionada", "anonymize()"),
                controlsUnknown = emptyList(),
                evidence = listOf("Commits de anonimización VIN/MAC"),
                residualRisk = "Bajo en repo redactado; alto en copias crudas del teléfono",
                proposedMeasure = "Anonimizar antes de compartir; no subir logcat crudo",
                validationMethod = "grep de VIN/MAC en evidence/",
            ),
            ThreatScenario(
                id = "T-010",
                title = "Módulo o aplicación comprometidos intentan atravesar un límite de confianza",
                asset = "Segmentación / integridad de comunicaciones",
                precondition = "Acceso OBD y posible recorrido a buses internos",
                adversaryGoal = "Pasar del dominio diagnóstico a dominios críticos",
                entrySurface = "OBD-II → gateway → redes internas",
                trustBoundary = "OBD ↔ Power/Body/Chassis/Entertainment CAN (documental de línea)",
                potentialImpact = "Indeterminado sin medición en esta unidad",
                feasibility = "No medida en Nivel A (sin barrido CAN)",
                controlsVerified = emptyList(),
                controlsUnknown = listOf(
                    "Si esta unidad concreta tiene gateway independiente",
                    "Política de filtrado OBD real",
                    "SecOC / SecurityAccess",
                ),
                evidence = listOf(
                    "Documental de línea JAC T8 (manual 2018-2022 / listas de diagnóstico): Independent Gateway / GW — NO verificado en esta unidad",
                ),
                residualRisk = "UNKNOWN para esta camioneta hasta año/VIN y prueba no invasiva de arquitectura",
                proposedMeasure = "No barrer CAN en vehículo; lab C + documentación del año exacto",
                validationMethod = "Inventario por VIN + fuentes manufacturer-evidence; lab aislado",
            ),
        )
    }
}

object HardeningAdvisor {
    fun recommendations(
        adapterPresent: Boolean,
        bleValidated: Boolean,
    ): List<HardeningItem> = listOf(
        HardeningItem(
            layer = "Adaptador",
            action = "Retirar el ELM327 del puerto OBD-II cuando no se diagnostique",
            status = if (adapterPresent) "PENDIENTE_OPERATIVA" else "APLICABLE",
            reversible = true,
            note = "Reduce T-001/T-002/T-003 sin tocar ECU.",
        ),
        HardeningItem(
            layer = "Adaptador",
            action = "Inventariar y eliminar vínculos Bluetooth no necesarios (OBDII/OBDBLE)",
            status = "PENDIENTE_OPERATIVA",
            reversible = true,
            note = "Delegar emparejamiento al sistema; no forzar PIN en la app.",
        ),
        HardeningItem(
            layer = "Android",
            action = "Permisos mínimos BLUETOOTH_CONNECT/SCAN; sin internet; backup desactivado",
            status = "VERIFICADO_EN_APK",
            reversible = true,
            note = "Manifest de jac-diagnostic-lab.",
        ),
        HardeningItem(
            layer = "Aplicación OBD",
            action = "Allowlist ObdCommandPolicy + bloqueo de 04/UDS escritura",
            status = "VERIFICADO_EN_APK",
            reversible = true,
            note = "No sustituye seguridad del dongle.",
        ),
        HardeningItem(
            layer = "Aplicación OBD",
            action = "Exportación con anonimización de VIN/MAC",
            status = "VERIFICADO_EN_APK",
            reversible = true,
            note = "Evidence export.",
        ),
        HardeningItem(
            layer = "Vehículo",
            action = "Consultar boletines/actualizaciones oficiales JAC; no flashear ECU",
            status = "NO_VERIFICADO",
            reversible = true,
            note = "Año/firmware DESCONOCIDOS en esta unidad.",
        ),
        HardeningItem(
            layer = "Operación",
            action = "No circular basándose solo en una sesión OBD exitosa; EPC/DTC requieren taller si persisten",
            status = "ACTIVO",
            reversible = true,
            note = if (bleValidated) "Sesión real demostró comunicación; no aptitud de circulación." else "Sin sesión BLE validada.",
        ),
    )
}

object SecurityAssessment {
    fun inventory(): List<InventoryAttribute> = listOf(
        InventoryAttribute("make_model", "Marca/modelo", "JAC T8", "VERIFIED", "Propietario", "Declaración", "HIGH"),
        InventoryAttribute("year", "Año", null, "UNKNOWN", null, "Pendiente VIN/placa", null),
        InventoryAttribute("ecu_fw", "Firmware ECU", null, "UNKNOWN", null, "No leído", null),
        InventoryAttribute(
            "gateway",
            "Gateway de seguridad",
            "Documentado en línea T8 (2018-2022) como Independent Gateway / GW; NO verificado en esta unidad",
            "DOCUMENTARY_LINE_UNKNOWN_UNIT",
            "Manual reparación / listas diagnóstico",
            "Fuentes manufacturer-evidence",
            "LOW_FOR_THIS_UNIT",
        ),
        InventoryAttribute("obd_protocol", "Protocolo OBD", "ISO 15765-4 CAN 11/500 (A6)", "VERIFIED", "ELM ATDPN", "Sesión 2136-8803", "HIGH"),
        InventoryAttribute("adapter", "Adaptador", "OBDBLE / ATI ELM327 v2.1", "VERIFIED_IMITABLE", "ATI", "BLE GATT", "MEDIUM"),
        InventoryAttribute("phone", "Teléfono", "Xiaomi 2512BPNDAG Android 17", "VERIFIED", "ADB", "getprop", "HIGH"),
    )

    fun findingsFromEvidence(
        bleValidated: Boolean,
        ecuTalking: Boolean,
        ati: String,
        storedDtcs: List<String>,
    ): List<SecurityFinding> {
        val out = mutableListOf<SecurityFinding>()
        if (bleValidated) {
            out += SecurityFinding(
                findingId = "C4A-AUTO-2026-001",
                component = "Bluetooth OBD adapter",
                title = "Perfil BLE FFF0 permitió escritura de comandos AT tras conexión",
                status = FindingStatus.VERIFIED,
                severity = "MEDIUM",
                confidence = "HIGH",
                evidenceIds = listOf("session-2026-11-11T2136-8803", "live-transcript"),
                evidenceKind = EvidenceKind.DIRECT,
                impact = "Un dispositivo/app capaz de abrir el GATT puede hablar con el intérprete ELM.",
                recommendation = "Retirar dongle cuando no se use; restringir apps Bluetooth.",
                validationMethod = "Sesión Nivel A: subscribe FFF1 + write FFF2 + ATI",
                residualRisk = "Persiste mientras el adaptador esté expuesto y emparejado",
                threatIds = listOf("T-001", "T-003"),
            )
        }
        if (ati.contains("ELM327", true)) {
            out += SecurityFinding(
                findingId = "C4A-AUTO-2026-002",
                component = "ELM327 identity",
                title = "Identidad ATI imitable (ELM327 v2.1); autenticidad de fabricante no demostrada",
                status = FindingStatus.VERIFIED,
                severity = "LOW",
                confidence = "HIGH",
                evidenceIds = listOf("ATI"),
                evidenceKind = EvidenceKind.DIRECT,
                impact = "No se puede confiar en ATI como control de autenticidad.",
                recommendation = "Tratar como clon/probable genérico hasta prueba de procedencia.",
                validationMethod = "Comparar documentación ELM Electronics vs respuesta ATI",
                residualRisk = "Aceptado mientras se use dongle genérico",
                threatIds = listOf("T-002", "T-007"),
            )
        }
        out += SecurityFinding(
            findingId = "C4A-AUTO-2026-003",
            component = "Application control plane",
            title = "Allowlist de la APK no protege frente a otras apps en el mismo teléfono",
            status = FindingStatus.VERIFIED,
            severity = "MEDIUM",
            confidence = "HIGH",
            evidenceIds = listOf("ObdCommandPolicy.kt"),
            evidenceKind = EvidenceKind.DOCUMENTARY,
            impact = "Otra app OBD podría enviar 04 u otros comandos al mismo adaptador.",
            recommendation = "Control operativo del emparejamiento; no dejar dongle siempre montado.",
            validationMethod = "Revisión de arquitectura + Nivel B",
            residualRisk = "Inherent al diseño del dongle Bluetooth abierto",
            threatIds = listOf("T-003"),
        )
        if (ecuTalking) {
            out += SecurityFinding(
                findingId = "C4A-AUTO-2026-004",
                component = "OBD-II path",
                title = "Camino diagnóstico OBD-II genérico alcanzable desde el adaptador Bluetooth",
                status = FindingStatus.VERIFIED,
                severity = "INFO",
                confidence = "HIGH",
                evidenceIds = listOf("0100", "03"),
                evidenceKind = EvidenceKind.DIRECT,
                impact = "Lectura de PIDs/DTC estándar posible; alcance a buses propietarios no medido en esta unidad.",
                recommendation = "Mantener solo lectura; gateway de esta unidad = UNKNOWN (documental de línea aparte).",
                validationMethod = "0100/03 en vehículo estacionado",
                residualRisk = "UNKNOWN respecto de redes internas de esta unidad",
                threatIds = listOf("T-008", "T-005", "T-010"),
            )
        }
        if (storedDtcs.isNotEmpty()) {
            out += SecurityFinding(
                findingId = "C4A-AUTO-2026-005",
                component = "Vehicle fault (non-cyber unless proven)",
                title = "DTC stored presentes: ${storedDtcs.joinToString()}",
                status = FindingStatus.VERIFIED,
                severity = "INFO",
                confidence = "HIGH",
                evidenceIds = listOf("mode 03"),
                evidenceKind = EvidenceKind.DIRECT,
                impact = "Síntomas EPC/potencia requieren diagnóstico mecánico/electrónico; no se atribuye causa cibernética.",
                recommendation = "VehicleFaultCorrelation + escáner JAC / taller.",
                validationMethod = "Bytes 03 crudos",
                residualRisk = "N/A (falla técnica, no exploit)",
                threatIds = emptyList(),
            )
        }
        out += SecurityFinding(
            findingId = "C4A-AUTO-2026-006",
            component = "Vehicle architecture",
            title = "Gateway/telemática/firmware de ESTA unidad no verificados; línea T8 documenta Independent Gateway",
            status = FindingStatus.INCONCLUSIVE,
            severity = null,
            confidence = "LOW_FOR_THIS_UNIT",
            evidenceIds = listOf("manufacturer-evidence/sources.md"),
            evidenceKind = EvidenceKind.DOCUMENTARY,
            impact = "No se puede afirmar filtrado OBD ni SecOC en esta camioneta.",
            recommendation = "Confirmar año/VIN; no barrido CAN; lab C para políticas simuladas.",
            validationMethod = "Fuentes manufacturer-evidence + inventario",
            residualRisk = "UNKNOWN",
            threatIds = listOf("T-010"),
        )
        return out
    }

    fun build(
        bleValidated: Boolean,
        ecuTalking: Boolean,
        ati: String,
        protocol: String,
        protocolNumber: String,
        atrv: String,
        storedDtcs: List<String>,
        devices: List<app.jac.diagnosticlab.bt.BtDevice>,
        gatt: List<app.jac.diagnosticlab.bt.GattCharView>,
        link: String,
        transportKind: String,
        commands: List<String>,
    ): SecurityDashboard {
        val findings = findingsFromEvidence(bleValidated, ecuTalking, ati, storedDtcs)
        val threats = ThreatModelEngine.baselineFromSession(bleValidated, ecuTalking, ati, storedDtcs)
        val hardening = HardeningAdvisor.recommendations(adapterPresent = true, bleValidated = bleValidated)
        val bt = BluetoothSecurityAuditor.audit(devices, gatt, bleValidated, link, transportKind)
        val elm = Elm327SecurityAnalyzer.analyze(ati, protocol, protocolNumber, atrv, commands)
        val known = findings.count { it.status == FindingStatus.VERIFIED }
        return SecurityDashboard(
            inventory = inventory(),
            findings = findings,
            threats = threats,
            hardening = hardening,
            controlsEvaluated = hardening.size,
            controlsKnown = hardening.count { it.status.startsWith("VERIFICADO") || it.status == "ACTIVO" },
            notes = listOf(
                "No hay porcentaje de seguridad arbitrario.",
                "Controles evaluados=${hardening.size}; verificados/activos=${hardening.count { it.status.startsWith("VERIFICADO") || it.status == "ACTIVO" }}.",
                "Hallazgos verificados=$known.",
                elm.authenticityNote,
                elm.policyNote,
                "BT risks: ${bt.risksVerified.joinToString(" | ").ifBlank { "(ninguno nuevo)" }}",
                "BT unknowns: ${bt.unknowns.joinToString(" | ")}",
            ),
        )
    }
}

data class SecurityCheck(
    val number: Int,
    val title: String,
    val result: String,
    val detail: String,
)

object SecurityGuide {
    val results = setOf("Comprobado", "Te toca a ti", "Sin comprobar", "Declarado por ti")

    fun checks(
        adapterId: String,
        ecuTalking: Boolean,
        codes: List<String>,
        bleValidated: Boolean,
        declaredModel: String = "",
        declaredYear: String = "",
    ): List<SecurityCheck> {
        val knownCodes = codes.map { it.trim() }.filter { it.isNotBlank() }
        val identity = when {
            adapterId.isBlank() -> SecurityCheck(
                3,
                "El nombre del aparato",
                "Sin comprobar",
                "Todavía no contestó. Cuando conteste, el texto ELM327 no prueba que el aparato sea original.",
            )
            adapterId.contains("ELM327", true) -> SecurityCheck(
                3,
                "El nombre del aparato",
                "Comprobado",
                "Contestó «$adapterId». Ese texto se puede copiar. No prueba que el aparato sea original.",
            )
            else -> SecurityCheck(
                3,
                "El nombre del aparato",
                "Comprobado",
                "Contestó «$adapterId». No alcanza para decir que el aparato sea original.",
            )
        }
        val reached = if (ecuTalking || knownCodes.isNotEmpty()) {
            SecurityCheck(
                4,
                "Llegó hasta la computadora",
                "Comprobado",
                "La computadora contestó por el enchufe de diagnóstico. Eso no significa que la camioneta esté sana ni que puedas circular.",
            )
        } else {
            SecurityCheck(
                4,
                "Llegó hasta la computadora",
                "Sin comprobar",
                "En esta pantalla todavía no hay una respuesta de la computadora.",
            )
        }
        val radio = if (bleValidated) {
            SecurityCheck(
                5,
                "El aparato acepta órdenes por Bluetooth",
                "Comprobado",
                "Después de conectar, aceptó órdenes de esta aplicación. Otra aplicación del mismo teléfono no pasa por este filtro. Ciérrala y saca el aparato al terminar.",
            )
        } else {
            SecurityCheck(
                5,
                "El aparato acepta órdenes por Bluetooth",
                "Sin comprobar",
                "Este enlace todavía no se validó. Aunque se valide, otra aplicación del teléfono puede hablar directo con el aparato.",
            )
        }
        val fault = if (knownCodes.isEmpty()) {
            SecurityCheck(
                7,
                "Los avisos no son una intrusión",
                "Sin comprobar",
                "No hay avisos en esta copia. Si aparecen, se leen como una falla del vehículo. Esta lectura no demuestra que alguien haya entrado a la computadora.",
            )
        } else {
            SecurityCheck(
                7,
                "Los avisos no son una intrusión",
                "Comprobado",
                "Hay avisos guardados. Son una falla del vehículo. Esta lectura no demuestra que alguien haya entrado a la computadora.",
            )
        }
        return listOf(
            SecurityCheck(
                1,
                "Esta aplicación no borra avisos",
                "Comprobado",
                "El borrado está bloqueado en esta aplicación. El bloqueo no viaja al aparato: otra aplicación sí podría borrarlos.",
            ),
            SecurityCheck(
                2,
                "Esta aplicación no sale a internet",
                "Comprobado",
                "No envía la lectura a un servidor. No pide ni guarda el PIN del Bluetooth.",
            ),
            identity,
            reached,
            radio,
            SecurityCheck(
                6,
                "Sacar el aparato al terminar",
                "Te toca a ti",
                "El teléfono no ve si el aparato sigue enchufado. Si se queda puesto, quien ya esté emparejado puede volver a hablar con la camioneta.",
            ),
            fault,
            declaredVehicle(declaredModel, declaredYear),
            SecurityCheck(
                9,
                "Pruebas que no se hacen en la camioneta",
                "Sin comprobar",
                "No se probó un segundo teléfono y no se lanzó ninguna prueba de ataque contra el vehículo. Si esas pruebas se hacen, van en un aparato aparte.",
            ),
        )
    }

    private fun declaredVehicle(model: String, year: String): SecurityCheck {
        val written = model.trim()
        val typedYear = year.trim()
        if (written.isBlank() && typedYear.isBlank()) {
            return SecurityCheck(
                8,
                "Modelo y año",
                "Sin comprobar",
                "Ingresa el modelo y el año en Hoy. No salen de la computadora. El programa y la compuerta de esta unidad siguen sin comprobar.",
            )
        }
        val spoken = when {
            written.isNotBlank() && typedYear.isNotBlank() -> "$written, año $typedYear"
            written.isNotBlank() -> written
            else -> "año $typedYear"
        }
        return SecurityCheck(
            8,
            "Modelo y año",
            "Declarado por ti",
            "Declaraste $spoken. No salió de la computadora. El programa y la compuerta de esta unidad siguen sin comprobar.",
        )
    }
}
