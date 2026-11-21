package app.jac.diagnosticlab

import app.jac.diagnosticlab.obd.FreezeFrame
import app.jac.diagnosticlab.obd.FreezeValue
import app.jac.diagnosticlab.report.WorkshopInput
import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.jac.diagnosticlab.analysis.AnalysisInput
import app.jac.diagnosticlab.analysis.AnalysisReport
import app.jac.diagnosticlab.analysis.JacAnalysis
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.bt.BleInspector
import app.jac.diagnosticlab.bt.BluetoothRepository
import app.jac.diagnosticlab.bt.BtDevice
import app.jac.diagnosticlab.bt.ClassicElmTransport
import app.jac.diagnosticlab.bt.DiagnosticConnectionService
import app.jac.diagnosticlab.bt.GattCharView
import app.jac.diagnosticlab.bt.GattObdProfile
import app.jac.diagnosticlab.bt.LinkPhase
import java.io.IOException
import app.jac.diagnosticlab.elm.ElmSession
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ObdTransport
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.lab.TransportIsolation
import app.jac.diagnosticlab.telemetry.ChannelStats
import app.jac.diagnosticlab.telemetry.SessionSummary
import app.jac.diagnosticlab.telemetry.TelemetryStore
import app.jac.diagnosticlab.telemetry.VehicleTelemetryEngine
import app.jac.diagnosticlab.evidence.EvidenceBundle
import app.jac.diagnosticlab.evidence.EvidenceEngine
import app.jac.diagnosticlab.evidence.Sample
import app.jac.diagnosticlab.evidence.SessionRecord
import app.jac.diagnosticlab.evidence.SnapshotReading
import app.jac.diagnosticlab.evidence.WorkshopGuide
import app.jac.diagnosticlab.evidence.anonymize
import app.jac.diagnosticlab.evidence.payloadHasContent
import app.jac.diagnosticlab.evidence.toCsv
import app.jac.diagnosticlab.evidence.toJson
import app.jac.diagnosticlab.evidence.toTxt
import app.jac.diagnosticlab.obd.CapabilitySnapshot
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.security.ClearDtcDecoder
import app.jac.diagnosticlab.security.ClearDtcGate
import app.jac.diagnosticlab.obd.DtcReadState
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.ObdCapabilityDiscoveryEngine
import app.jac.diagnosticlab.obd.decodeFreeze
import app.jac.diagnosticlab.obd.decodeVin
import app.jac.diagnosticlab.obd.milFromPayload
import app.jac.diagnosticlab.elm.InitReport
import app.jac.diagnosticlab.obd.PidDecoder
import app.jac.diagnosticlab.obd.PidReading
import app.jac.diagnosticlab.obd.parseAdapterVoltage
import app.jac.diagnosticlab.security.SecurityAssessment
import app.jac.diagnosticlab.security.SecurityDashboard
import app.jac.diagnosticlab.obd.OperationalState
import app.jac.diagnosticlab.obd.VoltageAssessment
import app.jac.diagnosticlab.obd.assessAdapterVoltage
import app.jac.diagnosticlab.obd.SessionTimeMetrics
import app.jac.diagnosticlab.analysis.DashboardLinkStatus
import app.jac.diagnosticlab.analysis.DrivingGuidanceEngine
import app.jac.diagnosticlab.analysis.DrivingGuidanceSnapshot
import app.jac.diagnosticlab.analysis.HealthAssessment
import app.jac.diagnosticlab.analysis.HealthInput
import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.TripGuidanceSummary
import app.jac.diagnosticlab.dynamic.GuidedTests
import app.jac.diagnosticlab.dynamic.TestResult
import app.jac.diagnosticlab.dynamic.TraceEvent
import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.obd.Mode06Decoder
import app.jac.diagnosticlab.obd.Mode06Result
import app.jac.diagnosticlab.obd.Mode09Decoder
import app.jac.diagnosticlab.obd.MonitorState
import app.jac.diagnosticlab.obd.PidCatalog
import app.jac.diagnosticlab.obd.Readiness
import app.jac.diagnosticlab.obd.ReadinessDecoder
import app.jac.diagnosticlab.obd.Signal
import app.jac.diagnosticlab.obd.VehicleInfo
import app.jac.diagnosticlab.profile.EcuIdentification
import app.jac.diagnosticlab.profile.JacT8Catalog
import app.jac.diagnosticlab.profile.Mode09Record
import app.jac.diagnosticlab.profile.ProfileResolution
import app.jac.diagnosticlab.profile.ProfileResolver
import app.jac.diagnosticlab.profile.SignalCoverage
import app.jac.diagnosticlab.profile.VehicleFingerprint
import app.jac.diagnosticlab.obd.canFromProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class LabState(
    val bluetoothOn: Boolean = false,
    val devices: List<BtDevice> = emptyList(),
    val scanning: Boolean = false,
    val selectedAddress: String? = null,
    val link: String = "Desconectado",
    val transportKind: String = "",
    val gatt: List<GattCharView> = emptyList(),
    val bleValidated: Boolean = false,
    val exchanges: List<Exchange> = emptyList(),
    val adapterId: String = "",
    val protocolText: String = "",
    val protocolNumber: String = "",
    val voltageText: String = "",
    val voltageAssessment: VoltageAssessment? = null,
    val operationalState: OperationalState = OperationalState.UNKNOWN,
    val timeMetrics: SessionTimeMetrics? = null,
    val sampleWindowMs: Long = 0L,
    val sampleEffectiveHz: Double = 0.0,
    val ecuTalking: Boolean = false,
    val dtcs: List<DtcReadResult> = emptyList(),
    val supported: Set<Int> = emptySet(),
    val samples: List<Sample> = emptyList(),
    val polling: Boolean = false,
    val intervalMs: Long = 1000L,
    val symptoms: Set<Symptom> = setOf(
        Symptom.EPC_LAMP,
        Symptom.ENGINE_WARNING,
        Symptom.BRAKE_POWER_LOSS,
        Symptom.CLEARS_AFTER_RESTART,
        Symptom.RETURNS_WITH_BRAKE,
    ),
    val analysis: AnalysisReport? = null,
    val vin: String? = null,
    /** El vehículo es del usuario: el VIN se lee sin pedir permiso. Se conserva el campo por compatibilidad. */
    val authorizeVin: Boolean = true,
    val error: String? = null,
    val recovery: String? = null,
    val sessionId: String = "",
    val freeze: List<FreezeNote> = emptyList(),
    /** Cuadro congelado completo (Mode 02, cuadro 00) leído en el escaneo. */
    val freezeValues: List<FreezeValue> = emptyList(),
    val busy: Boolean = false,
    val statusLine: String = "Listo para buscar el adaptador.",
    /** Marca de la última evidencia «antes de borrar» guardada y resultado del último borrado. */
    val evidenceSavedAtMs: Long? = null,
    val lastClear: String? = null,
    val protocol: String = "0",
    val simulated: Boolean = false,
    val security: SecurityDashboard? = null,
    val declaredModel: String = "",
    val declaredYear: String = "",
    val beforeSnapshot: SnapshotReading? = null,
    val afterSnapshot: SnapshotReading? = null,
    val capabilities: CapabilitySnapshot? = null,
    val bitmapRead: Boolean = false,
    val channelStats: Map<Int, ChannelStats> = emptyMap(),
    val linkPhase: LinkPhase = LinkPhase.DISCONNECTED,
    val sessionHistory: List<SessionSummary> = emptyList(),
    // v1.8 — escaneo completo y salud
    val scanSignals: List<Signal> = emptyList(),
    val scanAtMs: Long? = null,
    val readiness: Readiness? = null,
    val readinessCycle: Readiness? = null,
    val mode06: List<Mode06Result> = emptyList(),
    val vehicleInfo: VehicleInfo? = null,
    // v1.9 — SPEC-031 huella y perfil
    val ecuLabel: String = "",
    /** Versión instalada, p. ej. «v1.9.0 (22)». Visible en el pie y en cada exportación. */
    val appVersion: String = "",
    val ecuIdentification: List<Mode09Record> = emptyList(),
    val profile: ProfileResolution? = null,
    val profileCoverage: List<SignalCoverage> = emptyList(),
    val health: List<HealthItem> = emptyList(),
    val multiPid: Boolean? = null,
    // v1.8 — pruebas guiadas / ruta
    val activeTest: String? = null,
    val testLive: TraceFrame? = null,
    val testFrameCount: Int = 0,
    val testElapsedMs: Long = 0L,
    val testHz: Double = 0.0,
    val testCue: String? = null,
    val testEvents: List<TraceEvent> = emptyList(),
    val testResults: List<TestResult> = emptyList(),
    /** Últimas rondas para mini-gráficos (En vivo / prueba en curso). */
    val testHistory: List<TraceFrame> = emptyList(),
    // SPEC-034 R6 — Modo Tablero / Tablet Hands-Free y asistencia de conducción segura
    val liveLinkStatus: DashboardLinkStatus = DashboardLinkStatus.PAUSED,
    val drivingGuidance: DrivingGuidanceSnapshot? = null,
    val lastTripSummary: TripGuidanceSummary? = null,
)

class LabController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = BluetoothRepository(app)
    private val prefs = app.getSharedPreferences("jaclab", Context.MODE_PRIVATE)
    val state = MutableStateFlow(LabState(appVersion = AppVersion.of(app)))
    private var transport: ObdTransport? = null
    private var session: ElmSession? = null
    private var ble: BleInspector? = null
    private var pollJob: Job? = null
    private val telemetry = VehicleTelemetryEngine()
    private val telemetryStore = TelemetryStore(app.filesDir)
    private val autodiagGate = AtomicBoolean(false)
    private var dtcs: List<DtcReadResult> = emptyList()
    private var freeze: List<FreezeNote> = emptyList()
    private var samples: List<Sample> = emptyList()
    private val errors = mutableListOf<String>()
    /** Reconexiones automáticas tras enlace BLE bloqueado (el clon v2.1 se cuelga cada pocos minutos). */
    private var autoReconnects = 0
    private var startedAt = System.currentTimeMillis()
    private var sessionId = newId()
    private val vehicleNote =
        "JAC T8, encendida y estacionada, ELM327 Mini Bluetooth en el puerto OBD-II. " +
            "Los cinco síntomas marcados son el reporte del conductor del 11-11-2026, no mediciones."

    init {
        if (!prefs.contains("declaredModel")) {
            prefs.edit().putString("declaredModel", "JAC T8").putString("declaredYear", "2023").apply()
        }
        refresh()
        recompute()
    }

    fun declareVehicle(model: String, year: String) {
        prefs.edit().putString("declaredModel", model).putString("declaredYear", year).apply()
        state.update { it.copy(declaredModel = model, declaredYear = year) }
        recomputeProfile()
    }

    private class Mode09Read(val supported: Set<Int>, val info: VehicleInfo, val records: List<Mode09Record>)

    /**
     * SPEC-031 R11: Mode 09 solo lectura. 0900 y luego 0902/0904/0906/090A si la ECU los declara.
     * Guarda cada respuesta con raw, hora, latencia y valores por cabecera de ECU.
     */
    private suspend fun readMode09(current: ElmSession): Mode09Read {
        val bitmapEx = current.exchange("0900", 6000)
        val sup09 = Mode09Decoder.supported(bitmapEx).takeIf { bitmapEx.kind == ResponseKind.OBD_DATA }
        val exchanges = listOf(bitmapEx) + EcuIdentification.plan(sup09).drop(1).map { cmd ->
            current.exchange(cmd, if (cmd == "0902") 10000 else 8000)
        }
        exchanges.firstOrNull { it.command == "0902" }?.let { ex -> decodeVin(ex)?.let { vin -> state.update { it.copy(vin = vin) } } }
        fun byCmd(c: String) = exchanges.firstOrNull { it.command == c }
        val records = exchanges.map { EcuIdentification.record(it) }
        val info = VehicleInfo(
            calibrationIds = byCmd("0904")?.let { Mode09Decoder.calibrationIds(it) }.orEmpty(),
            cvns = byCmd("0906")?.let { Mode09Decoder.cvns(it) }.orEmpty(),
            ecuName = byCmd("090A")?.let { Mode09Decoder.ecuName(it) },
            supported = sup09.orEmpty(),
        )
        state.update { it.copy(ecuIdentification = records, vehicleInfo = info, exchanges = current.exchanges.toList()) }
        return Mode09Read(sup09.orEmpty(), info, records)
    }

    /** Botón «Identificar ECU (Mode 09)». */
    fun identifyEcu() = scope.launch {
        val current = session ?: return@launch
        if (!autodiagGate.compareAndSet(false, true)) return@launch
        telemetry.pause()
        state.update { it.copy(busy = true, statusLine = "Identificando ECU (Mode 09)…") }
        try {
            val r = readMode09(current)
            recomputeProfile()
            val p = state.value.profile
            state.update {
                it.copy(
                    statusLine = if (r.supported.isEmpty()) "La ECU no respondió a Mode 09 (0900)." else
                        "Mode 09: ${r.records.size} respuestas. Perfil: ${p?.status ?: "—"}.",
                )
            }
            persist()
        } catch (e: Exception) {
            explain(e.message ?: "Falló la identificación Mode 09.")
        } finally {
            telemetry.resume()
            autodiagGate.set(false)
            state.update { it.copy(busy = false) }
        }
    }

    /** SPEC-031 R5/R6: texto de la etiqueta física de la ECU, escrito por el usuario. */
    fun setEcuLabel(text: String) {
        prefs.edit().putString("ecuLabel", text).apply()
        state.update { it.copy(ecuLabel = text) }
        recomputeProfile()
    }

    private fun recomputeProfile() {
        val st = state.value
        val headers = st.exchanges.flatMap { ex -> ex.frames.mapNotNull { it.header } }.toSet()
        val fp = VehicleFingerprint.from(
            declaredModel = st.declaredModel,
            declaredYear = st.declaredYear,
            vin = st.vin,
            calibrationIds = EcuIdentification.calibrationIds(st.ecuIdentification).ifEmpty {
                st.vehicleInfo?.calibrationIds.orEmpty().map { null to it }
            },
            ecuName = EcuIdentification.ecuNames(st.ecuIdentification).firstOrNull()?.second ?: st.vehicleInfo?.ecuName,
            ecuLabel = st.ecuLabel,
            protocolNumber = st.protocolNumber,
            responseHeaders = headers,
        )
        val resolution = ProfileResolver.resolve(fp)
        val validPids = st.samples.filter { it.valid && it.value != null }.map { it.pid }.toSet() +
            st.scanSignals.filter { it.value != null }.map { it.pid }.toSet()
        val coverage = ProfileResolver.coverage(st.bitmapRead, st.supported, validPids)
        state.update { it.copy(profile = resolution, profileCoverage = coverage) }
    }

    fun refresh() {
        val missing = missingPermissions()
        state.update {
            it.copy(
                bluetoothOn = repo.isEnabled(),
                devices = if (missing.isEmpty()) merge(repo.bonded(), it.devices) else it.devices,
                selectedAddress = it.selectedAddress ?: prefs.getString("address", null),
                declaredModel = if (prefs.contains("declaredModel")) prefs.getString("declaredModel", "").orEmpty() else it.declaredModel,
                declaredYear = if (prefs.contains("declaredYear")) prefs.getString("declaredYear", "").orEmpty() else it.declaredYear,
                ecuLabel = prefs.getString("ecuLabel", "").orEmpty(),
                error = if (missing.isEmpty()) it.error else "Faltan permisos de Bluetooth: ${missing.joinToString()}.",
                recovery = if (missing.isEmpty()) it.recovery else "Conceda los permisos de dispositivos cercanos y vuelva a buscar.",
            )
        }
    }

    fun scan() {
        if (missingPermissions().isNotEmpty()) {
            refresh()
            return
        }
        state.update { it.copy(scanning = true, statusLine = "Buscando OBDII y OBDBLE…", error = null) }
        try {
            repo.startScan(
                onDevice = { device ->
                    state.update { current -> current.copy(devices = merge(current.devices, listOf(device))) }
                },
                onError = { message -> explain(message) },
            )
        } catch (error: SecurityException) {
            explain(error.message ?: "Permiso Bluetooth denegado.")
        }
        scope.launch {
            delay(12_000)
            stopScan()
        }
    }

    fun stopScan() {
        repo.stopScan()
        state.update { it.copy(scanning = false) }
    }

    fun select(address: String) {
        state.update { it.copy(selectedAddress = address) }
        val device = state.value.devices.firstOrNull { it.address == address }
        prefs.edit().putString("address", address).putString("name", device?.name ?: "").apply()
    }

    fun connectSelectedClassic() = scope.launch { selectedDevice()?.let { connectClassic(it) } }

    fun inspectSelectedBle() = scope.launch { selectedDevice()?.let { inspectBle(it) } }

    fun validateBle(writeUuid: String, notifyUuid: String) = scope.launch {
        val inspector = ble ?: return@launch explain("Primero hay que descubrir el perfil GATT.")
        if (GattObdProfile.scorePair(writeUuid, notifyUuid) < 0) {
            explain(
                "Características GATT no OBD (${writeUuid.takeLast(8)} / ${notifyUuid.takeLast(8)}). " +
                    "Use FFF2 (escritura) y FFF1 (aviso). 2A05/2A00 no hablan con el ELM.",
            )
            return@launch
        }
        try {
            state.update { it.copy(busy = true, statusLine = "Validando BLE con ATI…") }
            val raw = inspector.selectAndValidate(writeUuid, notifyUuid)
            bindProductionTransport(inspector)
            promote()
            openSession(inspector.name)
            state.update {
                it.copy(
                    bleValidated = true,
                    busy = false,
                    statusLine = "BLE validado (FFF). Inicialice o inicie monitor.",
                    error = null,
                    linkPhase = LinkPhase.ELM_READY,
                )
            }
            trace("ATI de validación BLE:\n$raw")
        } catch (error: Exception) {
            state.update { it.copy(bleValidated = false, busy = false, linkPhase = LinkPhase.TRANSPORT_CONNECTED) }
            explain(error.message ?: "No se validó BLE.")
        }
    }

    fun initialize() = scope.launch {
        val current = session ?: return@launch explain("No hay un adaptador conectado.")
        state.update { it.copy(busy = true, statusLine = "Inicializando ELM327. ATZ solo reinicia el adaptador.") }
        val report = current.initialize(state.value.protocol)
        applyInit(report)
        state.update { it.copy(busy = false) }
        persist()
    }

    /** La UI llama esto SOLO después de guardar PDF + JSON; ClearDtcGate lo exige reciente. */
    fun markEvidenceSaved() { state.update { it.copy(evidenceSavedAtMs = System.currentTimeMillis()) } }

    /**
     * Mode 04 con compuerta: lee velocidad y RPM frescas, verifica precondiciones, envía UN comando 04,
     * y vuelve a leer 03/07/0A. Informa lo que la ECU realmente dijo; nunca asume que se borró.
     */
    fun clearCodes(phrase: String) = scope.launch {
        val current = session ?: return@launch explain("No hay sesión con la ECU.")
        if (!autodiagGate.compareAndSet(false, true)) return@launch state.update { it.copy(statusLine = "Espere: hay una lectura en curso.") }
        try {
            telemetry.pause()
            state.update { it.copy(busy = true, statusLine = "Verificando que sea seguro borrar…") }
            val speed = current.readPid(0x0D).takeIf { it.valid }?.value
            val rpm = current.readPid(0x0C).takeIf { it.valid }?.value
            val verdict = ClearDtcGate.evaluate(
                ClearDtcGate.Preconditions(current.ecuTalking, speed, rpm, state.value.evidenceSavedAtMs, System.currentTimeMillis(), phrase),
            )
            trace("BORRADO verificación: permitido=${verdict.allowed} velocidad=$speed rpm=$rpm bloqueos=${verdict.blockers.size}")
            if (!verdict.allowed) {
                state.update { it.copy(busy = false, lastClear = "NO se borró nada. " + verdict.blockers.joinToString(" "), statusLine = "Borrado bloqueado.") }
                return@launch
            }
            val before = dtcs.flatMap { r -> r.codes.map { it.code } }.distinct()
            state.update { it.copy(statusLine = "Enviando borrado (Mode 04)…") }
            val response = ClearDtcDecoder.decode(current.clearDtcs())
            trace("BORRADO respuesta: $response")
            var reread = false
            var after = 0
            if (response == ClearDtcDecoder.Response.Accepted) {
                dtcs = listOf(
                    current.readDtc(DtcCategory.STORED),
                    current.readDtc(DtcCategory.PENDING),
                    current.readDtc(DtcCategory.PERMANENT),
                )
                val stored = dtcs.first { it.category == DtcCategory.STORED }
                reread = stored.state == DtcReadState.VALID_EMPTY || stored.state == DtcReadState.VALID_WITH_CODES
                after = stored.codes.size
            }
            val outcome = ClearDtcDecoder.outcome(response, reread, after)
            val detail = (response as? ClearDtcDecoder.Response.Rejected)?.let { " (${ClearDtcDecoder.nrcText(it.nrc)})" }.orEmpty()
            val permanent = dtcs.firstOrNull { it.category == DtcCategory.PERMANENT }?.codes?.size ?: 0
            val msg = "${outcome.label}$detail Antes: ${before.joinToString().ifBlank { "—" }}." +
                (if (permanent > 0) " Quedan $permanent permanente(s) (Mode 0A): solo se borran al corregirse la causa." else "") +
                " Los monitores quedan incompletos hasta completar ciclos de manejo."
            state.update { it.copy(dtcs = dtcs, exchanges = current.exchanges.toList(), busy = false, lastClear = msg, statusLine = outcome.label) }
            recompute(); recomputeHealth(); persist()
        } finally {
            ClearDtcGate.disarm()
            autodiagGate.set(false)
            state.update { it.copy(busy = false) }
        }
    }

    fun readAllDtcs() = scope.launch {
        val current = session ?: return@launch explain("No hay sesión.")
        telemetry.pause()
        state.update { it.copy(busy = true, statusLine = "Leyendo DTC 03, 07 y 0A.") }
        try {
            dtcs = listOf(
                current.readDtc(DtcCategory.STORED),
                current.readDtc(DtcCategory.PENDING),
                current.readDtc(DtcCategory.PERMANENT),
            )
            state.update { it.copy(dtcs = dtcs, busy = false, exchanges = current.exchanges.toList()) }
            recompute()
            recomputeHealth()
            persist()
        } finally {
            telemetry.resume()
        }
    }

    fun readSupported() = scope.launch { readSupportedInternal() }

    fun startPoll(pids: Set<Int>) {
        if (session == null) {
            explain("No hay adaptador en sesión. Conecte OBDII/OBDBLE (FFF1/FFF2), inicialice y luego inicie el monitor.")
            return
        }
        pollJob?.cancel()
        telemetry.reset()
        val supported = state.value.supported
        val allowed = pids.filter { it != 0x00 && (supported.isEmpty() || it in supported || it < 0) }.toSet()
        val withAtrv = allowed + (-1)
        telemetry.plan(withAtrv, state.value.intervalMs)
        promote()
        pollJob = scope.launch {
            state.update {
                it.copy(
                    polling = true,
                    statusLine = "Telemetría activa · JSONL local · una consulta a la vez. Mantenga la app en primer plano o con la notificación.",
                    channelStats = telemetry.stats(),
                    error = null,
                )
            }
            var consecutiveFailures = 0
            while (true) {
                val current = session
                if (current == null) {
                    state.update {
                        it.copy(
                            polling = false,
                            statusLine = "ENLACE CAÍDO: la sesión se perdió. No se siguen capturando muestras. Reconecte el dongle.",
                            linkPhase = LinkPhase.DISCONNECTED,
                            error = "Monitor detenido: sin sesión OBD.",
                            recovery = "Estacione, contacto ON, cierre OBD Auto Doctor/Torque, reconecte OBDBLE (FFF2/FFF1) o OBDII, Inicializar, Iniciar monitor.",
                        )
                    }
                    break
                }
                if (telemetry.isPaused()) {
                    delay(80)
                    continue
                }
                val now = System.currentTimeMillis()
                val pid = telemetry.nextDue(now)
                if (pid == null) {
                    delay(telemetry.sleepHintMs(now) ?: 40L)
                    continue
                }
                val t0 = System.currentTimeMillis()
                try {
                    if (pid < 0) {
                        val voltage = current.exchange("ATRV", 2500)
                        if (voltage.kind == ResponseKind.DISCONNECTED) throw IOException("GATT/RFCOMM desconectado durante ATRV")
                        val parsed = parseAdapterVoltage(voltage.raw)
                        val latency = System.currentTimeMillis() - t0
                        if (parsed != null) {
                            val sample = Sample(
                                System.currentTimeMillis(), -1, "Tensión estimada del adaptador", parsed, "V",
                                true, parsed < 11.6 || parsed > 15.2, voltage.raw.trim(),
                            )
                            samples = (samples + sample).takeLast(480)
                            telemetry.recordSuccess(-1, latency)
                            telemetryStore.appendMeasurement(sessionId, sample, latency, "SLOW")
                            consecutiveFailures = 0
                        } else {
                            telemetry.recordFailure(-1, latency)
                            consecutiveFailures++
                        }
                    } else {
                        val reading = current.readPid(pid)
                        val latency = System.currentTimeMillis() - t0
                        val sample = Sample(
                            System.currentTimeMillis(), pid, reading.name, reading.value, reading.unit,
                            reading.valid, reading.suspect, reading.raw.joinToString(" ") { "%02X".format(it) },
                        )
                        samples = (samples + sample).takeLast(480)
                        if (reading.valid) {
                            telemetry.recordSuccess(pid, latency)
                            consecutiveFailures = 0
                        } else {
                            telemetry.recordFailure(pid, latency)
                            consecutiveFailures++
                        }
                        val band = VehicleTelemetryEngine.bandFor(pid).name
                        telemetryStore.appendMeasurement(sessionId, sample, latency, band)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    telemetry.recordFailure(pid, System.currentTimeMillis() - t0)
                    consecutiveFailures++
                    trace("MONITOR fallo pid=$pid: ${error.message}")
                    if (consecutiveFailures >= 5 || error.message.orEmpty().contains("desconect", ignoreCase = true) ||
                        error.message.orEmpty().contains("GATT", ignoreCase = true)
                    ) {
                        state.update {
                            it.copy(
                                polling = false,
                                samples = samples,
                                channelStats = telemetry.stats(),
                                statusLine = "ENLACE CAÍDO tras fallos de lectura. Muestras en RAM: ${samples.size}. Reconecte.",
                                error = error.message ?: "Enlace OBD perdido durante el monitor.",
                                recovery = "Cierre apps OBD rivales, no re-empareje desde Ajustes en marcha, reconecte FFF1/FFF2 o RFCOMM.",
                                linkPhase = LinkPhase.DISCONNECTED,
                            )
                        }
                        pollJob = null
                        break
                    }
                }
                // Lecturas inválidas seguidas sin excepción también son enlace caído (antes repetía para siempre).
                if (consecutiveFailures >= 12) {
                    trace("MONITOR detenido: $consecutiveFailures lecturas fallidas seguidas")
                    if (autoReconnects < 3) {
                        autoReconnects++
                        trace("RECONEXIÓN automática $autoReconnects/3")
                        scope.launch { delay(1500); reconnectAndResumeMonitor() }
                    }
                    state.update {
                        it.copy(
                            polling = false,
                            samples = samples,
                            channelStats = telemetry.stats(),
                            statusLine = "ENLACE CAÍDO: $consecutiveFailures lecturas seguidas sin respuesta. Pulse Reconectar.",
                            error = "El adaptador dejó de responder durante el monitor.",
                            recovery = "Pulse Reconectar. Si se repite, desenchufe y vuelva a enchufar el adaptador.",
                            linkPhase = LinkPhase.DISCONNECTED,
                        )
                    }
                    pollJob = null
                    break
                }
                state.update {
                    it.copy(
                        samples = samples,
                        exchanges = current.exchanges.toList(),
                        channelStats = telemetry.stats(),
                    )
                }
                refreshCapabilities()
                recompute()
                if (samples.size % 12 == 0) persist()
            }
        }
    }

    fun stopPoll() {
        pollJob?.cancel()
        telemetry.pause()
        state.update {
            it.copy(
                polling = false,
                channelStats = telemetry.stats(),
                statusLine = "Registro de sensores detenido.",
            )
        }
    }

    /** Arranca el monitor en el orden fijo de taller (solo PID soportados + ATRV). */
    fun startOrderedMonitor() {
        val supported = state.value.supported
        val ordered = WorkshopGuide.monitorOrder
            .map { it.pid }
            .filter { it > 0 && (supported.isEmpty() || it in supported) }
            .toSet()
        val fallback = setOf(0x0C, 0x05, 0x04, 0x0D, 0x11, 0x0B)
        val chosen = if (ordered.isNotEmpty()) ordered else fallback.filter { supported.isEmpty() || it in supported }.toSet()
        if (chosen.isEmpty() && supported.isNotEmpty()) {
            explain("Ningún canal del monitor está en el mapa soportado. Consulta PID otra vez.")
            return
        }
        // Si el clásico falló o el mapa está vacío pero hay sesión, usar fallback genérico.
        startPoll(if (chosen.isEmpty()) fallback else chosen)
    }

    fun captureSnapshot(phase: String) {
        val label = phase.trim().ifBlank { "captura" }
        val latest = linkedMapOf<Int, Double?>()
        for (channel in WorkshopGuide.monitorOrder) {
            val hit = samples.lastOrNull { it.pid == channel.pid && it.value != null }
                ?: state.value.samples.lastOrNull { it.pid == channel.pid && it.value != null }
            latest[channel.pid] = hit?.value
        }
        val codes = dtcs.flatMap { it.codes.map { code -> code.code } }.distinct()
        val snap = SnapshotReading(label, System.currentTimeMillis(), codes, latest)
        state.update {
            when {
                label.equals("después", ignoreCase = true) || label.equals("despues", ignoreCase = true) ||
                    label.equals("after", ignoreCase = true) -> it.copy(afterSnapshot = snap, statusLine = "Captura DESPUÉS guardada.")
                else -> it.copy(beforeSnapshot = snap, statusLine = "Captura ANTES guardada.")
            }
        }
        persist()
    }

    fun clearSnapshots() {
        state.update { it.copy(beforeSnapshot = null, afterSnapshot = null, statusLine = "Capturas antes/después borradas de la sesión.") }
    }

    fun refreshSessionHistory() {
        state.update { it.copy(sessionHistory = telemetryStore.listSessions()) }
    }

    fun setSymptom(symptom: Symptom, enabled: Boolean) {
        state.update { current ->
            val next = current.symptoms.toMutableSet()
            if (enabled) next += symptom else next -= symptom
            current.copy(symptoms = next)
        }
        recompute()
    }

    fun setProtocol(code: String) {
        if (code.length == 1 && code[0] in '0'..'9') state.update { it.copy(protocol = code) }
    }

    fun setInterval(ms: Long) {
        val next = ms.coerceIn(500L, 5000L)
        state.update { it.copy(intervalMs = next) }
        if (state.value.polling && telemetry.currentPlans().isNotEmpty()) {
            val pids = telemetry.currentPlans().map { it.pid }.toSet()
            telemetry.plan(pids, next)
        }
    }

    fun authorizeVin(enabled: Boolean) = state.update { it.copy(authorizeVin = enabled) }

    fun readVin() = scope.launch {
        val current = session ?: return@launch
        state.update { it.copy(busy = true, statusLine = "Consultando soporte del VIN.") }
        val support = current.exchange("0900", 8000)
        val bitmap = support.frames.firstOrNull { it.payload.size >= 6 && it.payload[0] == 0x49 && it.payload[1] == 0x00 }?.payload?.drop(2)
        val supported = bitmap != null && bitmap.size >= 4 && PidDecoder.supported(0x00, bitmap).contains(0x02)
        if (!supported) {
            state.update { it.copy(busy = false, statusLine = "La ECU no informó soporte del PID 0902. No se pidió el VIN.") }
            persist()
            return@launch
        }
        val vin = decodeVin(current.exchange("0902", 10000))
        state.update { it.copy(vin = vin, busy = false, exchanges = current.exchanges.toList(), statusLine = if (vin == null) "VIN incompleto. No se completaron caracteres." else "VIN leído.") }
        recomputeProfile()
        persist()
    }

    fun readFreeze() = scope.launch {
        val current = session ?: return@launch
        state.update { it.copy(busy = true, statusLine = "Leyendo freeze frame, modo 02.") }
        val note = decodeFreeze(current.exchange("020200", 8000))
        freeze = freeze + note
        state.update { it.copy(freeze = freeze, busy = false, exchanges = current.exchanges.toList()) }
        persist()
    }

    fun export(uri: Uri, format: String, anonymizeExport: Boolean) {
        val record = if (anonymizeExport) anonymize(buildRecord()) else buildRecord()
        val refusal = refusalFor(record, format)
        if (refusal != null) {
            discard(uri, refusal)
            return
        }
        val text = when (format) {
            "csv" -> toCsv(record)
            "txt" -> toTxt(record)
            else -> toJson(record)
        }
        writeExport(uri, text.toByteArray(Charsets.UTF_8), null)
    }

    fun exportBytes(uri: Uri, bytes: ByteArray, refusal: String?) = writeExport(uri, bytes, refusal)

    /** JSON completo de la sesión (todos los comandos con bytes), compañero del informe de taller. */
    fun sessionJson(): String = toJson(buildRecord())

    fun disconnect() {
        stopPoll()
        autodiagGate.set(false)
        scope.launch(Dispatchers.IO) {
            try {
                transport?.disconnect()
            } catch (_: Exception) {
            }
        }
        transport = null
        session = null
        ble = null
        app.stopService(Intent(app, DiagnosticConnectionService::class.java))
        state.update {
            it.copy(
                link = "Desconectado",
                bleValidated = false,
                busy = false,
                polling = false,
                linkPhase = LinkPhase.DISCONNECTED,
                statusLine = "Desconectado. La evidencia guardada no se borró.",
            )
        }
    }

    /**
     * Escape hatch: cancela monitor, libera la compuerta de autodiag, corta BT y limpia RAM.
     * Los archivos ya guardados en sessions/telemetry no se borran.
     * Siempre usable aunque [LabState.busy] esté pegado.
     */
    fun forceResetAll() {
        trace("RESET TOTAL: liberando busy/compuerta/enlace")
        testJob?.cancel()
        testJob = null
        pollJob?.cancel()
        pollJob = null
        telemetry.pause()
        telemetry.reset()
        autodiagGate.set(false)
        val oldTransport = transport
        transport = null
        session = null
        ble = null
        scope.launch(Dispatchers.IO) {
            runCatching { oldTransport?.disconnect() }
            runCatching { app.stopService(Intent(app, DiagnosticConnectionService::class.java)) }
        }
        dtcs = emptyList()
        freeze = emptyList()
        samples = emptyList()
        errors.clear()
        sessionId = newId()
        startedAt = System.currentTimeMillis()
        state.update {
            LabState(
                bluetoothOn = it.bluetoothOn,
                devices = it.devices,
                selectedAddress = it.selectedAddress,
                sessionId = sessionId,
                symptoms = it.symptoms,
                declaredModel = it.declaredModel,
                declaredYear = it.declaredYear,
                authorizeVin = it.authorizeVin,
                protocol = it.protocol,
                intervalMs = it.intervalMs,
                statusLine = "Reset completo. UI desbloqueada. Archivos previos siguen en el teléfono.",
                link = "Desconectado",
                linkPhase = LinkPhase.DISCONNECTED,
                busy = false,
                polling = false,
                bleValidated = false,
                error = null,
                recovery = "Puede volver a Leer / Reconectar. No hace falta forzar detención de la app.",
                sessionHistory = telemetryStore.listSessions(),
            )
        }
        refreshSessionHistory()
    }

    fun startAutodiag() {
        if (!autodiagGate.compareAndSet(false, true)) {
            // Ya hay una lectura en curso: un segundo toque o un intent duplicado no es un error.
            trace("Lectura ya en curso; se ignora la petición duplicada.")
            return
        }
        state.update { it.copy(error = null, recovery = null) }
        scope.launch {
            try {
                autodiagnose()
            } finally {
                autodiagGate.set(false)
                state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun autodiagnose() {
        resetSession()
        trace("INICIO autodiagnóstico de solo lectura")
        trace(vehicleNote)
        trace("Teléfono ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}")
        if (missingPermissions().isNotEmpty()) {
            explain("Sin permisos Bluetooth no se puede abrir el adaptador.")
            trace("BLOQUEO permisos ${missingPermissions()}")
            persist()
            return
        }
        refresh()
        scan()
        delay(8_000)
        stopScan()
        // Los emparejados OBDII/OBDBLE a veces no anuncian; hay que reinyectarlos tras el escaneo.
        val bonded = repo.bonded()
        state.update { it.copy(devices = merge(it.devices, bonded)) }
        val devices = state.value.devices
        trace("DISPOSITIVOS ${devices.joinToString { "${it.name} ${it.address} ${it.typeLabel} candidato=${it.candidate}" }}")
        trace("EMPAREJADOS ${bonded.joinToString { "${it.name} ${it.address} primary=${it.primary}" }}")
        if (devices.isEmpty()) {
            explain("No apareció ningún dispositivo Bluetooth.")
            persist()
            return
        }
        val remembered = prefs.getString("address", null)
        val classic = devices.firstOrNull { it.name.equals("OBDII", true) }
            ?: devices.firstOrNull { it.primary && !it.name.equals("OBDBLE", true) }
            ?: devices.firstOrNull { remembered != null && it.address.equals(remembered, true) && !it.name.equals("OBDBLE", true) }
        val lowEnergy = devices.firstOrNull { it.name.equals("OBDBLE", true) }
            ?: devices.firstOrNull { remembered != null && it.address.equals(remembered, true) && it.name.contains("BLE", true) }
            ?: bonded.firstOrNull { it.name.equals("OBDBLE", true) }
        var talked = false
        if (classic != null) {
            val device = repo.device(classic.address)
            if (device != null && connectClassic(device)) {
                val report = session?.initialize(state.value.protocol)
                if (report != null) {
                    applyInit(report)
                    talked = report.adapterIdentity.isNotBlank() || report.exchanges.any { it.kind == ResponseKind.RECOGNIZED }
                    if (talked && report.stoppedBecause == null) finishReads()
                }
            }
        } else {
            trace("No se vio un dispositivo llamado OBDII.")
        }
        val classicOk = state.value.adapterId.isNotBlank() || state.value.ecuTalking
        if (!classicOk && lowEnergy != null) {
            trace("El clásico no quedó identificado. Se inspecciona OBDBLE sin escribir características desconocidas.")
            disconnectQuiet()
            val device = repo.device(lowEnergy.address)
            if (device != null && inspectBle(device)) {
                val pairs = bleCandidates(state.value.gatt).take(3)
                trace("Pares GATT candidatos ${pairs.size}: ${pairs.joinToString { "${it.first.takeLast(8)}/${it.second.takeLast(8)}" }}")
                for ((write, notify) in pairs) {
                    val inspector = ble ?: break
                    try {
                        val raw = inspector.selectAndValidate(write, notify)
                        bindProductionTransport(inspector)
                        promote()
                        openSession(inspector.name)
                        state.update { it.copy(bleValidated = true, error = null, statusLine = "BLE validado con ATI.") }
                        trace("ATI de validación BLE ($write / $notify):\n$raw")
                        break
                    } catch (error: Exception) {
                        errors += "Validación BLE $write: ${error.message}"
                        trace("VALIDACION BLE falló $write/$notify: ${error.message}")
                    }
                }
                if (state.value.bleValidated) {
                    val report = session?.initialize("0")
                    if (report != null) {
                        applyInit(report)
                        if (report.stoppedBecause == null) finishReads()
                    }
                }
            }
            // Fallback: BLE no dejó ECU → reintentar RFCOMM OBDII (a veces el canal se liberó).
            if (!state.value.ecuTalking && classic != null) {
                trace("Fallback clásico tras BLE insuficiente.")
                disconnectQuiet()
                val device = repo.device(classic.address)
                if (device != null && connectClassic(device)) {
                    val report = session?.initialize(state.value.protocol)
                    if (report != null) {
                        applyInit(report)
                        if (report.stoppedBecause == null && (report.ecuTalking || report.adapterIdentity.isNotBlank())) {
                            finishReads()
                        }
                    }
                }
            }
        } else if (lowEnergy != null && classicOk) {
            trace("OBDBLE también está visible (${lowEnergy.address}). No se abre una segunda conexión porque el clásico ya respondió.")
        } else if (lowEnergy == null) {
            trace("No se vio un dispositivo llamado OBDBLE.")
        }
        recompute()
        persist()
        val ready = state.value.ecuTalking && session != null
        if (ready) {
            state.update { it.copy(busy = false, statusLine = "Lectura lista. Arrancando monitor continuo para captura…") }
            trace("FIN autodiagnóstico — auto-monitor")
            startOrderedMonitor()
        } else {
            state.update { it.copy(busy = false, statusLine = "Secuencia terminada. Sin ECU: no se inicia monitor. Transcripción guardada.") }
            trace("FIN autodiagnóstico")
        }
    }

    /**
     * Reabre el dongle sin borrar muestras/DTC ya leídos. Clásico primero; si falla, BLE FFF/FFE.
     * Pensado para cuando el enlace cae durante la revisión.
     */
    fun reconnectAndResumeMonitor() {
        if (!autodiagGate.compareAndSet(false, true)) {
            trace("Reconexión ignorada: ya hay una tarea en curso.")
            return
        }
        scope.launch {
            try {
                reconnectKeepEvidence()
            } finally {
                autodiagGate.set(false)
                state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun reconnectKeepEvidence() {
        stopPoll()
        trace("RECONEXIÓN sin borrar evidencia (muestras=${samples.size})")
        state.update {
            it.copy(
                busy = true,
                error = null,
                statusLine = "Reconectando dongle (clásico → BLE)…",
            )
        }
        if (missingPermissions().isNotEmpty()) {
            explain("Sin permisos Bluetooth no se puede reconectar.")
            return
        }
        disconnectQuiet()
        refresh()
        val bonded = repo.bonded()
        state.update { it.copy(devices = merge(it.devices, bonded)) }
        val devices = state.value.devices
        val classic = devices.firstOrNull { it.name.equals("OBDII", true) }
            ?: bonded.firstOrNull { it.name.equals("OBDII", true) }
        val lowEnergy = devices.firstOrNull { it.name.equals("OBDBLE", true) }
            ?: bonded.firstOrNull { it.name.equals("OBDBLE", true) }
        var linked = false
        if (classic != null) {
            val device = repo.device(classic.address)
            if (device != null && connectClassic(device)) {
                val report = session?.initialize(state.value.protocol)
                if (report != null) {
                    applyInit(report)
                    linked = report.ecuTalking || report.adapterIdentity.isNotBlank()
                }
            }
        }
        if (!linked && lowEnergy != null) {
            disconnectQuiet()
            val device = repo.device(lowEnergy.address)
            if (device != null && inspectBle(device)) {
                for ((write, notify) in bleCandidates(state.value.gatt).take(3)) {
                    val inspector = ble ?: break
                    try {
                        inspector.selectAndValidate(write, notify)
                        bindProductionTransport(inspector)
                        promote()
                        openSession(inspector.name)
                        state.update { it.copy(bleValidated = true, error = null) }
                        val report = session?.initialize("0")
                        if (report != null) {
                            applyInit(report)
                            linked = report.ecuTalking || report.adapterIdentity.isNotBlank()
                        }
                        if (linked) break
                    } catch (error: Exception) {
                        trace("RECONEXIÓN BLE falló $write: ${error.message}")
                    }
                }
            }
        }
        state.update { it.copy(busy = false) }
        if (linked && session != null) {
            state.update { it.copy(statusLine = "Reconectado. Reanudando monitor…") }
            startOrderedMonitor()
        } else {
            explain("No se pudo reconectar. Estacione, contacto ON, cierre apps OBD rivales y reintente.")
        }
    }

    private suspend fun finishReads() {
        val current = session ?: return
        readSupportedInternal()
        dtcs = listOf(
            current.readDtc(DtcCategory.STORED),
            current.readDtc(DtcCategory.PENDING),
            current.readDtc(DtcCategory.PERMANENT),
        )
        val mil = current.exchange("0101", 5000)
        state.update { it.copy(dtcs = dtcs, exchanges = current.exchanges.toList(), statusLine = "Capturando sensores para la ficha de falla…") }
        freeze = listOf(decodeFreeze(current.exchange("020200", 8000)))
        val interesting = PidDecoder.faultHuntPids.filter { it in state.value.supported }
        val deadline = System.currentTimeMillis() + 15_000L
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            round += 1
            for (pid in interesting) {
                val t0 = System.currentTimeMillis()
                val reading = current.readPid(pid)
                val latency = System.currentTimeMillis() - t0
                val sample = Sample(
                    System.currentTimeMillis(), pid, reading.name, reading.value, reading.unit,
                    reading.valid, reading.suspect, reading.raw.joinToString(" ") { "%02X".format(it) },
                )
                samples = samples + sample
                telemetryStore.appendMeasurement(sessionId, sample, latency, VehicleTelemetryEngine.bandFor(pid).name)
            }
            if (interesting.isEmpty()) break
            delay(400)
        }
        samples = samples.takeLast(400)
        refreshSessionHistory()
        val metrics = SessionTimeMetrics.fromTimestamps(samples.map { it.atEpochMs })
        state.update {
            it.copy(
                samples = samples,
                freeze = freeze,
                exchanges = current.exchanges.toList(),
                timeMetrics = metrics,
                statusLine = "Captura ${metrics.windowMs} ms, ${metrics.sampleCount} muestras, %.2f Hz. Rondas $round.".format(metrics.effectiveHz),
            )
        }
        trace("0101 ${mil.kind}; freeze=${freeze.firstOrNull()?.dtc}; hunt=${interesting.joinToString { "%02X".format(it) }}")
        fullScanInternal()
    }

    /* ═════════════════════ v1.8 — Escaneo completo y salud ═════════════════════ */

    private var testJob: Job? = null
    private var testFrames = mutableListOf<TraceFrame>()
    private var testManual = mutableListOf<TraceEvent>()

    private fun isCan(): Boolean = canFromProtocol(state.value.protocolNumber) == true ||
        state.value.protocolText.contains("15765", ignoreCase = true) ||
        // Cabecera CAN 11 bits (7E8…7EF) o 29 bits (18DAF1xx) en respuestas reales.
        session?.exchanges?.takeLast(40)?.flatMap { it.frames }?.any { f ->
            val h = f.header?.uppercase() ?: return@any false
            h.matches(Regex("7E[89A-F]")) || h.startsWith("18DAF1")
        } == true

    fun fullScan() {
        if (session == null) return explain("No hay adaptador en sesión. Pulse Leer la camioneta o Reconectar.")
        if (!autodiagGate.compareAndSet(false, true)) return state.update { it.copy(statusLine = "Espere: hay una lectura en curso.") }
        stopPoll()
        scope.launch {
            try {
                fullScanInternal()
            } finally {
                autodiagGate.set(false)
                state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Lee TODO lo que la ECU declara: cada PID Mode 01 soportado, monitores (0101/0141),
     * Mode 06 (pruebas a bordo) y Mode 09 (calibración, CVN, nombre ECU, rendimiento en uso).
     * El VIN (0902) solo si el usuario lo autorizó.
     */
    private suspend fun fullScanInternal() {
        val current = session ?: return
        // Siempre se relee el mapa: un mapa parcial de 0100 dejaba fuera las páginas 0160/0180.
        readSupportedInternal()
        // Si la lectura de códigos falló (ECU despertando), se repite ahora que responde.
        if (state.value.bitmapRead && (dtcs.isEmpty() || dtcs.any { it.kind != ResponseKind.OBD_DATA })) {
            dtcs = listOf(
                current.readDtc(DtcCategory.STORED),
                current.readDtc(DtcCategory.PENDING),
                current.readDtc(DtcCategory.PERMANENT),
            )
            state.update { it.copy(dtcs = dtcs, ecuTalking = true) }
            trace("DTC releídos en escaneo: ${dtcs.joinToString { "${it.category}=${it.kind}/${it.codes.size}" }}")
        }
        // Actualizar protocolo si quedó en «buscando» (A0) al inicio.
        if (canFromProtocol(state.value.protocolNumber) == null) {
            val dpn = current.exchange("ATDPN", 2500)
            val dp = current.exchange("ATDP", 2500)
            if (dpn.kind == ResponseKind.RECOGNIZED) state.update {
                it.copy(protocolNumber = dpn.raw.trim().lineSequence().firstOrNull().orEmpty(), protocolText = dp.raw.trim().lineSequence().firstOrNull().orEmpty())
            }
        }
        telemetry.pause()
        state.update { it.copy(busy = true, statusLine = "Escaneo completo: leyendo ${state.value.supported.size} PIDs declarados…") }
        try {
            val pids = state.value.supported.filter { !PidCatalog.isBitmap(it) && it != 0x02 }.sorted()
            val raw = current.readPidsRaw(pids, isCan())
            val signals = raw.flatMap { (pid, data) -> PidCatalog.decode(pid, data) }
            val readiness = raw[0x01]?.let { ReadinessDecoder.decode(it) }
            val cycle = raw[0x41]?.let { ReadinessDecoder.decode(it, thisCycle = true) }
            trace("ESCANEO pids=${pids.size} recibidos=${raw.size} señales=${signals.size} multiPid=${current.multiPidWorks}")
            // Enlace muerto: no reemplazar un escaneo bueno por uno vacío (sesión real 2026-11-18 20:42).
            if (raw.isEmpty() && pids.isNotEmpty()) {
                state.update {
                    it.copy(
                        error = "El adaptador no respondió al escaneo. Se conservan los datos del escaneo anterior.",
                        recovery = "Pulse Reconectar. Si se repite, desenchufe y vuelva a enchufar el adaptador.",
                        statusLine = "Escaneo sin respuesta: datos anteriores conservados.",
                    )
                }
                return
            }
            raw.forEach { (pid, data) -> trace("PID %02X = %s".format(pid, data.joinToString(" ") { "%02X".format(it) })) }

            state.update { it.copy(statusLine = "Escaneo completo: pruebas a bordo (Mode 06)…") }
            val mids = mutableSetOf<Int>()
            for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)) {
                if (base != 0x00 && base !in mids) break
                mids += Mode06Decoder.supported(base, current.exchange("06%02X".format(base), 5000))
            }
            val m06 = mids.filter { !Mode06Decoder.isSupportMid(it) }.sorted()
                .flatMap { Mode06Decoder.decode(current.exchange("06%02X".format(it), 6000)) }
            trace("MODE06 mids=${mids.size} resultados=${m06.size} fuera=${m06.count { !it.passed }}")
            var mode06 = m06
            val truncated = current.exchanges.any { ex -> ex.command.startsWith("06") && ex.frames.any { it.truncated } }
            val onlyEngine = current.exchanges.flatMap { it.frames }.mapNotNull { it.header }.all { it == "7E8" }
            if (truncated && onlyEngine && isCan()) {
                val ok = current.applyStandardFlowControl()
                trace("ISO-TP truncado detectado (clon corta tras 4 CF). FC estándar aplicado=$ok")
                if (ok) {
                    val again = mids.filter { !Mode06Decoder.isSupportMid(it) }.sorted()
                        .flatMap { Mode06Decoder.decode(current.exchange("06%02X".format(it), 6000)) }
                    val stillCut = current.exchanges.takeLast(mids.size).any { ex -> ex.frames.any { it.truncated } }
                    trace("MODE06 tras FC: resultados=${again.size} (antes ${m06.size}) truncado=$stillCut")
                    if (again.size >= m06.size) mode06 = again
                }
            }

            state.update { it.copy(statusLine = "Escaneo completo: identificación (Mode 09)…") }
            val ident = readMode09(current)
            val sup09 = ident.supported
            val diesel = readiness?.compressionIgnition == true
            val info = ident.info.copy(
                inUsePerformance = when {
                    diesel && 0x0B in sup09 -> Mode09Decoder.inUsePerformance(current.exchange("090B", 8000), true)
                    !diesel && 0x08 in sup09 -> Mode09Decoder.inUsePerformance(current.exchange("0908", 8000), false)
                    else -> emptyList()
                },
            )
            // Cuadro congelado: qué motor había cuando se guardó el código. Solo si hay código o cuadro.
            val hasCodes = dtcs.any { it.codes.isNotEmpty() }
            if (hasCodes && freeze.isEmpty()) freeze = listOf(decodeFreeze(current.exchange("020200", 8000)))
            val freezeValues = if (hasCodes || freeze.any { it.dtc != null }) {
                state.update { it.copy(statusLine = "Escaneo completo: cuadro congelado (Mode 02)…") }
                FreezeFrame.plan(state.value.supported).mapNotNull { pid ->
                    FreezeFrame.decode(pid, current.exchange(FreezeFrame.command(pid), 5000))
                }
            } else emptyList()
            trace("FREEZE valores=${freezeValues.size}")
            // Las señales del escaneo también alimentan la matriz de capacidad y el monitor.
            val now = System.currentTimeMillis()
            val scanSamples = signals.groupBy { it.pid }.mapNotNull { (pid, list) ->
                val first = list.first()
                Sample(now, pid, first.name, first.value, first.unit, first.value != null, false,
                    raw[pid].orEmpty().joinToString(" ") { "%02X".format(it) })
            }
            samples = (samples + scanSamples).takeLast(600)
            state.update {
                it.copy(
                    scanSignals = signals,
                    scanAtMs = now,
                    freeze = freeze,
                    freezeValues = freezeValues,
                    readiness = readiness,
                    readinessCycle = cycle,
                    mode06 = mode06,
                    vehicleInfo = info,
                    multiPid = current.multiPidWorks,
                    ecuTalking = it.ecuTalking || raw.isNotEmpty(),
                    error = if (raw.isNotEmpty()) null else it.error,
                    recovery = if (raw.isNotEmpty()) null else it.recovery,
                    linkPhase = if (raw.isNotEmpty()) LinkPhase.DIAGNOSTIC_READY else it.linkPhase,
                    samples = samples,
                    exchanges = current.exchanges.toList(),
                    statusLine = "Escaneo completo: ${raw.size}/${pids.size} PIDs, ${signals.size} señales, ${m06.size} pruebas Mode 06.",
                )
            }
            recomputeHealth()
            refreshCapabilities()
            recompute()
            persist()
        } finally {
            telemetry.resume()
        }
    }

    /** Todo lo leído, para el informe de taller (SPEC-033). */
    fun workshopInput(): WorkshopInput {
        val st = state.value
        val scanRaw = st.scanAtMs?.let { at -> st.samples.filter { it.atEpochMs == at }.associate { it.pid to it.raw } }.orEmpty()
        return WorkshopInput(
            appVersion = st.appVersion,
            sessionId = sessionId,
            generatedAtMs = System.currentTimeMillis(),
            declaredModel = st.declaredModel,
            declaredYear = st.declaredYear,
            vin = st.vin,
            protocol = listOf(st.protocolNumber, st.protocolText).filter { it.isNotBlank() }.joinToString(" "),
            adapterId = st.adapterId,
            adapterVoltage = st.voltageText,
            scanAtMs = st.scanAtMs,
            dtcs = dtcs.ifEmpty { st.dtcs },
            freezeNotes = st.freeze,
            freezeValues = st.freezeValues,
            signals = st.scanSignals,
            rawByPid = scanRaw,
            supportedPids = st.supported,
            readiness = st.readiness,
            readinessCycle = st.readinessCycle,
            mode06 = st.mode06,
            vehicleInfo = st.vehicleInfo,
            ecuIdentification = st.ecuIdentification,
            profile = st.profile,
            health = st.health,
            exchanges = st.exchanges,
            errors = errors.toList(),
            tests = st.testResults,
            catalogVersion = JacT8Catalog.v1.version,
        )
    }

    /** SPEC-034: paquete canónico de evidencia offline (JSON + PDF + SHA-256 + catálogo). */
    fun evidenceBundle(): EvidenceBundle = EvidenceEngine.exportBundle(workshopInput(), JacT8Catalog.v1)

    private fun recomputeHealth() {
        val st = state.value
        val values = st.scanSignals.associate { it.key to it.value }
        val texts = st.scanSignals.filter { it.text != null }.associate { it.key to it.text!! }
        val reads = dtcs.ifEmpty { st.dtcs }
        val health = HealthAssessment.evaluate(
            HealthInput(
                values = values,
                texts = texts,
                readiness = st.readiness,
                readinessThisCycle = st.readinessCycle,
                storedDtcs = reads.filter { it.category == DtcCategory.STORED }.flatMap { r -> r.codes.map { it.code } },
                pendingDtcs = reads.filter { it.category == DtcCategory.PENDING }.flatMap { r -> r.codes.map { it.code } },
                storedState = reads.firstOrNull { it.category == DtcCategory.STORED }?.state ?: DtcReadState.NOT_READ,
                pendingState = reads.firstOrNull { it.category == DtcCategory.PENDING }?.state,
                mode06 = st.mode06,
                vehicleInfo = st.vehicleInfo,
                adapterVoltage = parseAdapterVoltage(st.voltageText),
            ),
        )
        state.update { it.copy(health = health) }
    }

    /* ═════════════════════ v1.8 — Pruebas guiadas y ruta ═════════════════════ */

    private suspend fun sampleRound(current: ElmSession, pids: List<Int>): TraceFrame? {
        val raw = current.readPidsRaw(pids, isCan())
        if (raw.isEmpty()) return null
        val values = linkedMapOf<String, Double>()
        raw.forEach { (pid, data) -> PidCatalog.decode(pid, data).forEach { s -> s.value?.let { values[s.key] = it } } }
        return TraceFrame(System.currentTimeMillis(), values)
    }

    private fun cueFor(testId: String, elapsedMs: Long): String? {
        if (testId == "SNAP") {
            val t = elapsedMs / 1000
            return when (t) {
                in 0L..4L -> "Prepárese: neutro, freno de mano, pie fuera del pedal."
                in 5L..6L, in 15L..16L, in 25L..26L -> "¡PISAR A FONDO AHORA y soltar al llegar al corte!"
                in 7L..14L, in 17L..24L, in 27L..34L -> "Soltar. Esperar que vuelva a ralentí."
                else -> "Terminando. No tocar el pedal."
            }
        }
        if (testId == "ELECTRIC") {
            return if (elapsedMs < 15_000) "Todo apagado. No encender nada aún." else "Encender luces altas, ventilador máximo, desempañador y A/C."
        }
        return null
    }

    fun startGuidedTest(id: String) {
        val test = runCatching { GuidedTests.byId(id) }.getOrNull() ?: return explain("Prueba desconocida: $id")
        val current = session ?: return explain("No hay adaptador en sesión. Pulse Leer la camioneta o Reconectar antes de la prueba.")
        if (!autodiagGate.compareAndSet(false, true)) return state.update { it.copy(statusLine = "Espere: hay una lectura en curso.") }
        stopPoll()
        val supported = state.value.supported
        val pids = test.pids.filter { supported.isEmpty() || it in supported }
        testFrames = mutableListOf()
        testManual = mutableListOf()
        testJob = scope.launch {
            var started = false
            try {
                val first = sampleRound(current, pids)
                if (first == null) {
                    explain("La ECU no respondió a la primera ronda de la prueba.")
                    return@launch
                }
                test.precondition(first.values)?.let { reason ->
                    explain("No se inicia «${test.title}»: $reason")
                    return@launch
                }
                started = true
                testFrames += first
                val firstGuidance = DrivingGuidanceEngine.evaluateFrame(first.values)
                trace("PRUEBA ${test.id} inicio pids=${pids.joinToString { "%02X".format(it) }}")
                state.update {
                    it.copy(
                        activeTest = test.id,
                        testEvents = emptyList(),
                        testFrameCount = 1,
                        testLive = first,
                        testHistory = listOf(first),
                        liveLinkStatus = DashboardLinkStatus.CONNECTED,
                        drivingGuidance = firstGuidance,
                        error = null,
                        busy = false,
                        statusLine = "Prueba en curso: ${test.title}",
                    )
                }
                val t0 = first.atMs
                var misses = 0
                // Ruta / en vivo: señales rápidas (1 trama) cada ronda; las de varias tramas, 1 de cada 4.
                val fastSet = setOf(0x0C, 0x0D, 0x49, 0x61, 0x62, 0x04, 0x42, 0x0B)
                val split = test.id == "ROAD" || test.id == "LIVE"
                val fast = pids.filter { it in fastSet }
                val slow = pids.filter { it !in fastSet }
                var round = 0
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - t0
                    if (test.durationMs != null && elapsed >= test.durationMs) break
                    round++
                    val roundPids = if (split && fast.isNotEmpty()) fast + (if (round % 4 == 0) slow else emptyList()) else pids
                    val frame = sampleRound(current, roundPids)
                    if (frame == null) {
                        misses++
                        // SPEC-034 R6: ante microcortes BT no se borra la sesión; se marca RECONECTANDO.
                        state.update { it.copy(liveLinkStatus = DashboardLinkStatus.RECONNECTING) }
                        if (misses >= 5) {
                            explain("Enlace perdido durante la prueba (5 rondas sin respuesta). Se guarda lo capturado.")
                            break
                        }
                        continue
                    }
                    misses = 0
                    testFrames += frame
                    if (testFrames.size > 30_000) testFrames.removeAt(0)
                    if (test.id == "WARMUP" && (frame.values[GuidedTests.ECT] ?: 0.0) >= 85.0) break
                    val events = if (test.id == "ROAD" && testFrames.size % 10 == 0) GuidedTests.road(testFrames.takeLast(600)).second else state.value.testEvents
                    val window = testFrames.takeLast(20)
                    val hz = if (window.size > 1) (window.size - 1) * 1000.0 / (window.last().atMs - window.first().atMs).coerceAtLeast(1) else 0.0
                    state.update {
                        val merged = it.testLive?.values.orEmpty() + frame.values
                        it.copy(
                            // Pantalla: último valor conocido de cada señal. El CSV guarda solo lo leído en la ronda.
                            testLive = TraceFrame(frame.atMs, merged),
                            testHistory = (it.testHistory + frame).takeLast(90),
                            testFrameCount = testFrames.size,
                            testElapsedMs = frame.atMs - t0,
                            testHz = hz,
                            testCue = cueFor(test.id, frame.atMs - t0),
                            liveLinkStatus = DashboardLinkStatus.CONNECTED,
                            drivingGuidance = DrivingGuidanceEngine.evaluateFrame(merged),
                            testEvents = (events.filter { e -> it.testEvents.none { o -> o.atMs == e.atMs && o.kind == e.kind } } + it.testEvents)
                                .distinctBy { e -> e.atMs to e.kind }.sortedBy { e -> e.atMs },
                        )
                    }
                    if (test.id == "ROAD" && testFrames.size % 100 == 0) withContext(Dispatchers.IO) { saveTrace(test.id, null) }
                }
            } finally {
                withContext(NonCancellable) {
                    if (started) finishTest(test.id)
                    autodiagGate.set(false)
                    state.update { it.copy(activeTest = null, testCue = null, busy = false, liveLinkStatus = DashboardLinkStatus.PAUSED) }
                }
            }
        }
    }

    fun stopGuidedTest() {
        testJob?.cancel()
        testJob = null
    }

    fun markEvent(label: String = "Marcado por el acompañante") {
        val at = System.currentTimeMillis()
        val ev = TraceEvent(at, "MARCA", label, manual = true)
        testManual += ev
        state.update { it.copy(testEvents = (it.testEvents + ev).sortedBy { e -> e.atMs }) }
        trace("MARCA $label")
    }

    private suspend fun finishTest(id: String) {
        if (id == "LIVE") {
            val tripSummary = DrivingGuidanceEngine.summarizeTrip(testFrames.toList())
            withContext(Dispatchers.IO) { saveTrace(id, null) }
            state.update {
                it.copy(
                    lastTripSummary = tripSummary,
                    liveLinkStatus = DashboardLinkStatus.PAUSED,
                    statusLine = "Vista en vivo detenida. ${testFrames.size} rondas guardadas en CSV.",
                )
            }
            return
        }
        val test = GuidedTests.byId(id)
        val result = GuidedTests.analyze(test, testFrames.toList(), testManual.toList())
        val tripSummary = if (id == "ROAD") DrivingGuidanceEngine.summarizeTrip(testFrames.toList()) else state.value.lastTripSummary
        trace("PRUEBA $id fin frames=${result.frames} hz=%.2f veredicto=${result.verdict} ${result.summary}".format(result.effectiveHz))
        result.metrics.forEach { m -> trace("  ${m.name}: ${m.value?.let { "%.3f".format(it) } ?: "—"} ${m.unit} [${m.status.label}]") }
        result.events.forEach { e -> trace("  EVENTO ${e.kind}: ${e.detail}") }
        withContext(Dispatchers.IO) { saveTrace(id, result) }
        state.update {
            it.copy(
                testResults = (it.testResults + result).takeLast(20),
                lastTripSummary = tripSummary,
                statusLine = "Prueba terminada: ${result.title} → ${result.verdict.label}. ${result.summary}",
            )
        }
        persist()
    }

    private fun saveTrace(id: String, result: TestResult?) {
        val name = "$sessionId-$id"
        val csv = GuidedTests.toCsv(testFrames.toList(), (result?.events ?: state.value.testEvents))
        val dirs = listOfNotNull(File(app.filesDir, "traces"), app.getExternalFilesDir("traces"))
        dirs.forEach { dir ->
            runCatching {
                dir.mkdirs()
                File(dir, "$name.csv").writeText(csv)
            }
        }
    }

    /** SPEC-031: el perfil y su procedencia viajan en el JSON. El VIN no: solo el resultado del chequeo de año. */
    private fun profileMap(p: ProfileResolution, coverage: List<SignalCoverage>): Map<String, Any?> = linkedMapOf(
        "catalog" to p.catalogVersion,
        "status" to p.status.name,
        "yearCheck" to p.yearCheck.name,
        "summary" to p.summary,
        "matches" to p.matches.map { m ->
            linkedMapOf(
                "candidate" to m.candidate.id, "supplier" to m.candidate.supplier, "family" to m.candidate.family,
                "identifier" to m.identifier, "origin" to m.origin.name, "sources" to m.candidate.sourceIds,
            )
        },
        "activeSignals" to ProfileResolver.activeSignals(p).map { it.key },
        "coverage" to coverage.map { c -> linkedMapOf("key" to c.signal.key, "status" to c.status.name, "reason" to c.reason) },
        "sources" to JacT8Catalog.v1.sources.map { s -> linkedMapOf("id" to s.id, "url" to s.url, "level" to s.level.name, "sha256" to s.sha256) },
    )

    private fun extendedMap(): Map<String, Any?> {
        val st = state.value
        if (st.scanSignals.isEmpty() && st.testResults.isEmpty()) return emptyMap()
        return linkedMapOf(
            "basis" to HealthAssessment.BASIS,
            "scanAtMs" to st.scanAtMs,
            "multiPid" to st.multiPid,
            "readiness" to st.readiness?.let { r -> linkedMapOf("milOn" to r.milOn, "dtcCount" to r.dtcCount, "diesel" to r.compressionIgnition, "monitors" to r.monitors.map { mapOf("name" to it.name, "state" to it.state.name) }) },
            "readinessThisCycle" to st.readinessCycle?.monitors?.map { mapOf("name" to it.name, "state" to it.state.name) },
            "signals" to st.scanSignals.map { linkedMapOf("key" to it.key, "pid" to "%02X".format(it.pid), "name" to it.name, "value" to it.value, "unit" to it.unit, "text" to it.text) },
            "mode06" to st.mode06.map { linkedMapOf("mid" to "%02X".format(it.mid), "tid" to "%02X".format(it.tid), "uasid" to "%02X".format(it.uasid), "monitor" to it.monitorName, "value" to it.value, "min" to it.min, "max" to it.max, "unit" to it.unit, "passed" to it.passed) },
            "vehicleInfo" to st.vehicleInfo?.let { v -> linkedMapOf("calid" to v.calibrationIds, "cvn" to v.cvns, "ecuName" to v.ecuName, "ipt" to v.inUsePerformance.map { mapOf("name" to it.first, "count" to it.second) }) },
            "profile" to st.profile?.let { p -> profileMap(p, st.profileCoverage) },
            "ecuIdentification" to st.ecuIdentification.map { EcuIdentification.toMap(it) },
            "health" to st.health.map { linkedMapOf("system" to it.system, "status" to it.status.name, "title" to it.title, "detail" to it.detail, "evidence" to it.evidence) },
            "tests" to st.testResults.map { t ->
                linkedMapOf(
                    "id" to t.testId, "title" to t.title, "startedAtMs" to t.startedAtMs, "durationMs" to t.durationMs,
                    "frames" to t.frames, "hz" to t.effectiveHz, "verdict" to t.verdict.name, "summary" to t.summary,
                    "metrics" to t.metrics.map { m -> linkedMapOf("name" to m.name, "value" to m.value, "unit" to m.unit, "status" to m.status.name, "note" to m.note) },
                    "events" to t.events.map { e -> linkedMapOf("atMs" to e.atMs, "kind" to e.kind, "detail" to e.detail, "manual" to e.manual) },
                )
            },
        )
    }

    private fun extendedText(): List<String> {
        val st = state.value
        if (st.scanSignals.isEmpty() && st.testResults.isEmpty()) return emptyList()
        return buildList {
            add(HealthAssessment.BASIS)
            st.readiness?.let { r ->
                add("Motor: ${if (r.compressionIgnition) "DIÉSEL (encendido por compresión)" else "gasolina (chispa)"} · MIL ${if (r.milOn == true) "ENCENDIDA" else "apagada"} · códigos ${r.dtcCount}")
                r.monitors.filter { it.state != MonitorState.NOT_SUPPORTED }.forEach { add("  Monitor ${it.name}: ${it.state.label}") }
            }
            add("-- Salud --")
            st.health.forEach { add("[${it.status.label}] ${it.system}: ${it.title}. ${it.detail} (${it.evidence})") }
            add("-- Señales (${st.scanSignals.size}) --")
            st.scanSignals.forEach { s -> add("  %s %s = %s %s".format(s.key, s.name, s.text ?: s.value?.let { "%.3f".format(it) } ?: "—", s.unit)) }
            if (st.mode06.isNotEmpty()) {
                add("-- Mode 06 --")
                st.mode06.forEach { m -> add("  %s TID %02X: %.3f %s [%.3f…%.3f] %s".format(m.monitorName, m.tid, m.value, m.unit, m.min, m.max, if (m.passed) "OK" else "FUERA")) }
            }
            st.profile?.let { p ->
                add("-- Perfil (${p.catalogVersion}) --")
                add("  Estado: ${p.status} · Año: ${p.yearCheck}")
                add("  ${p.summary}")
                p.matches.forEach { m -> add("  ${m.candidate.supplier} ${m.candidate.family}: «${m.identifier}» en ${ProfileResolver.originText(m.origin)} [${m.candidate.sourceIds.joinToString()}]") }
                st.profileCoverage.groupBy { it.status }.forEach { (status, list) -> add("  $status: ${list.joinToString { it.signal.key }}") }
            }
            st.vehicleInfo?.let { v ->
                add("-- Mode 09 --")
                v.ecuName?.let { add("  ECU: $it") }
                v.calibrationIds.forEach { add("  CALID: $it") }
                v.cvns.forEach { add("  CVN: $it") }
                v.inUsePerformance.forEach { add("  IPT ${it.first}: ${it.second}") }
            }
            st.testResults.forEach { t ->
                add("-- Prueba ${t.title}: ${t.verdict.label} --")
                add("  ${t.summary} (${t.frames} rondas, %.2f Hz)".format(t.effectiveHz))
                t.metrics.forEach { m -> add("  ${m.name}: ${m.value?.let { "%.2f".format(it) } ?: "—"} ${m.unit} [${m.status.label}] ${m.note}") }
                t.events.forEach { e -> add("  EVENTO ${e.kind}: ${e.detail}") }
            }
        }
    }

    /** `BluetoothDevice.name` lanza SecurityException en Android 12+ sin BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

    private suspend fun connectClassic(device: BluetoothDevice): Boolean {
        repo.stopScan()
        val adapter = repo.adapter() ?: return false.also { explain("No hay adaptador Bluetooth.") }
        select(device.address)
        state.update { it.copy(busy = true, link = "Conectando", statusLine = "Abriendo RFCOMM hacia ${safeName(device)}.") }
        val classic = ClassicElmTransport(device, adapter, ::trace)
        return try {
            classic.connect()
            bindProductionTransport(classic)
            promote()
            openSession(classic.name)
            state.update { it.copy(linkPhase = LinkPhase.TRANSPORT_CONNECTED) }
            true
        } catch (error: Exception) {
            errors += "RFCOMM ${safeName(device)}: ${error.message}"
            explain(error.message ?: "Falló RFCOMM.")
            false
        }
    }

    private suspend fun inspectBle(device: BluetoothDevice): Boolean {
        repo.stopScan()
        select(device.address)
        val inspector = BleInspector(app, device, ::trace)
        ble = inspector
        state.update { it.copy(busy = true, statusLine = "Descubriendo servicios GATT de ${safeName(device)}. No se escribe todavía.") }
        return try {
            val profile = inspector.connectAndDiscover()
            state.update {
                it.copy(
                    gatt = profile,
                    busy = false,
                    link = "GATT descubierto",
                    error = null,
                    linkPhase = LinkPhase.TRANSPORT_CONNECTED,
                )
            }
            profile.forEach { row -> trace("GATT ${row.serviceUuid} ${row.uuid} ${row.properties.joinToString("|")}") }
            true
        } catch (error: Exception) {
            errors += "BLE ${safeName(device)}: ${error.message}"
            explain(error.message ?: "Falló el descubrimiento GATT.")
            false
        }
    }

    private suspend fun readSupportedInternal() {
        val current = session ?: return
        telemetry.pause()
        try {
            val walk = ObdCapabilityDiscoveryEngine.walkSupportPages { page ->
                current.readPid(page).takeIf { it.valid }?.raw
            }
            val supported = walk.supported
            val bitmapRead = walk.bitmapRead
            state.update {
                it.copy(
                    supported = supported,
                    bitmapRead = bitmapRead,
                    exchanges = current.exchanges.toList(),
                    statusLine = if (bitmapRead) "PIDs soportados en mapa: ${supported.size}." else "No se pudo leer el mapa de PIDs.",
                )
            }
            refreshCapabilities()
        } finally {
            telemetry.resume()
        }
    }

    fun refreshCapabilities() {
        val current = state.value
        val linked = current.link.contains("Conect", ignoreCase = true) || current.adapterId.isNotBlank() || current.transportKind.isNotBlank()
        val readings = linkedMapOf<Int, PidReading>()
        // Última muestra válida por PID se convierte en PidReading sintético mínimo para la matriz.
        current.samples.groupBy { it.pid }.forEach { (pid, list) ->
            val last = list.lastOrNull { it.value != null } ?: return@forEach
            readings[pid] = PidReading(
                pid = pid,
                name = last.name,
                raw = last.raw.split(' ').mapNotNull { it.toIntOrNull(16) },
                value = last.value,
                unit = last.unit,
                valid = last.valid,
                suspect = last.suspect,
                note = if (last.valid) "Sonda de sesión." else "Muestra no válida.",
            )
        }
        val snap = ObdCapabilityDiscoveryEngine.build(
            supportedBitmap = current.supported,
            bitmapRead = current.bitmapRead,
            ecuTalking = current.ecuTalking,
            adapterConnected = linked,
            sourceEcu = if (current.ecuTalking) "ECU Mode01" else null,
            readings = readings,
        )
        state.update { it.copy(capabilities = snap) }
        recomputeProfile()
    }

    private fun openSession(kind: String) {
        val current = transport ?: return
        session = ElmSession(current, log = { trace(it) }, onExchange = { exchange ->
            trace("CMD ${exchange.command} ${exchange.kind} ${exchange.elapsedMs}ms\n${exchange.raw}")
            state.update { it.copy(exchanges = session?.exchanges?.toList() ?: it.exchanges) }
        })
        state.update { it.copy(transportKind = kind, link = "Conectado", sessionId = sessionId, error = null, busy = false) }
    }

    private fun applyInit(report: InitReport) {
        state.update {
            it.copy(
                adapterId = report.adapterIdentity,
                protocolText = report.protocolText,
                protocolNumber = report.protocolNumber,
                voltageText = report.voltageText,
                ecuTalking = report.ecuTalking,
                supported = report.supportedPids,
                bitmapRead = report.supportedPids.isNotEmpty(),
                exchanges = report.exchanges,
                statusLine = report.stoppedBecause ?: if (report.ecuTalking) "La ECU respondió a 0100." else "El adaptador contestó, pero 0100 no trajo datos de la ECU.",
                error = report.stoppedBecause,
                recovery = report.stoppedBecause?.let { recoveryFor(it) },
                linkPhase = when {
                    report.ecuTalking && report.supportedPids.isNotEmpty() -> LinkPhase.DIAGNOSTIC_READY
                    report.ecuTalking -> LinkPhase.ECU_RESPONDING
                    report.adapterIdentity.isNotBlank() -> LinkPhase.ELM_READY
                    else -> LinkPhase.TRANSPORT_CONNECTED
                },
            )
        }
        if (report.stoppedBecause != null) errors += report.stoppedBecause
        refreshCapabilities()
        refreshSessionHistory()
        recompute()
    }

    private fun recompute() {
        val current = state.value
        val milFrame = current.exchanges.lastOrNull { it.command == "0101" && it.kind == ResponseKind.OBD_DATA }?.frames?.firstOrNull()
        val mil = milFrame?.payload?.let { milFromPayload(it) }?.first
        val rpm = current.samples.lastOrNull { it.pid == 0x0C && it.valid }?.value
        val voltage = parseAdapterVoltage(current.voltageText)
            ?: current.samples.lastOrNull { it.pid == -1 }?.value
        val dtcReads = dtcs.ifEmpty { current.dtcs }
        val report = JacAnalysis.evaluate(
            AnalysisInput(
                symptoms = current.symptoms,
                dtcReads = dtcReads,
                milOn = mil,
                adapterVoltage = voltage,
                rpm = rpm,
                throttleSupported = 0x11 in current.supported,
                commKinds = current.exchanges.map { it.kind }.toSet(),
                ecuAnswered0100 = current.ecuTalking,
            ),
        )
        val voltAssess = assessAdapterVoltage(current.voltageText)
        val opState = OperationalState.fromHints(rpm = rpm, speedKmh = current.samples.lastOrNull { it.pid == 0x0D && it.valid }?.value, declared = null)
        val timeMetrics = SessionTimeMetrics.fromTimestamps(current.samples.filter { it.pid > 0 }.map { it.atEpochMs })
        val stored = dtcReads
            .filter { it.category == DtcCategory.STORED }
            .flatMap { it.codes.map { code -> code.code } }
        val security = SecurityAssessment.build(
            bleValidated = current.bleValidated || current.transportKind.contains("BLE", true) || current.transportKind.contains("GATT", true),
            ecuTalking = current.ecuTalking,
            ati = current.adapterId,
            protocol = current.protocolText,
            protocolNumber = current.protocolNumber,
            atrv = current.voltageText,
            storedDtcs = stored,
            devices = current.devices,
            gatt = current.gatt,
            link = current.link,
            transportKind = current.transportKind,
            commands = current.exchanges.map { it.command },
        )
        state.update {
            it.copy(
                analysis = report,
                security = security,
                voltageAssessment = voltAssess,
                operationalState = opState,
                sampleWindowMs = timeMetrics.windowMs,
                sampleEffectiveHz = timeMetrics.effectiveHz,
            )
        }
    }

    private fun buildRecord(): SessionRecord {
        val current = state.value
        return SessionRecord(
            appVersion = current.appVersion,
            id = sessionId,
            startedAtEpochMs = startedAt,
            androidDevice = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            connectionType = current.transportKind.ifBlank { "sin conexión" },
            adapterName = current.devices.firstOrNull { it.address == current.selectedAddress }?.name ?: prefs.getString("name", "").orEmpty(),
            adapterAddress = current.selectedAddress.orEmpty(),
            adapterIdentity = current.adapterId,
            protocolText = current.protocolText,
            protocolNumber = current.protocolNumber,
            adapterVoltage = current.voltageText,
            exchanges = current.exchanges,
            dtcs = dtcs.ifEmpty { current.dtcs },
            freeze = freeze,
            samples = samples,
            errors = errors.toList(),
            connectionState = current.link,
            symptoms = current.symptoms,
            vin = current.vin,
            simulated = false,
            vehicleNote = vehicleNote,
            analysis = current.analysis,
            extended = extendedMap(),
            extendedText = extendedText(),
        )
    }

    private fun refusalFor(record: SessionRecord, format: String): String? = payloadHasContent(
        format,
        record.exchanges.size,
        record.dtcs.sumOf { it.codes.size },
        record.dtcs.any { it.confirmedEmpty },
        record.errors.size,
        record.samples.size,
        record.adapterIdentity,
    )

    private fun discard(uri: Uri, reason: String) {
        runCatching { app.contentResolver.delete(uri, null, null) }
        explain(reason)
    }

    private fun writeExport(uri: Uri, bytes: ByteArray, refusal: String?) {
        if (refusal != null || bytes.isEmpty()) {
            discard(uri, refusal ?: "El archivo no tenía contenido. Se descartó.")
            return
        }
        val stream = app.contentResolver.openOutputStream(uri)
        if (stream == null) {
            discard(uri, "Android no abrió el archivo elegido.")
            return
        }
        stream.use { it.write(bytes) }
    }

    private fun persist() {
        val record = buildRecord()
        if (refusalFor(record, "json") != null) return
        val dir = File(app.filesDir, "sessions").apply { mkdirs() }
        runCatching { File(dir, "$sessionId.json").writeText(toJson(record)) }
        runCatching { File(dir, "$sessionId.txt").writeText(toTxt(record)) }
        runCatching { File(app.filesDir, "latest.txt").writeText(toTxt(record)) }
        app.getExternalFilesDir(null)?.let { ext ->
            runCatching { File(ext, "$sessionId.json").writeText(toJson(record)) }
            runCatching { File(ext, "latest.txt").writeText(toTxt(record)) }
        }
    }

    private fun resetSession() {
        sessionId = newId()
        startedAt = System.currentTimeMillis()
        dtcs = emptyList()
        freeze = emptyList()
        samples = emptyList()
        errors.clear()
        File(app.filesDir, "live-transcript.txt").writeText("")
        state.update {
            it.copy(
                sessionId = sessionId,
                exchanges = emptyList(),
                dtcs = emptyList(),
                samples = emptyList(),
                freeze = emptyList(),
                freezeValues = emptyList(),
                adapterId = "",
                ecuTalking = false,
                error = null,
                statusLine = "Secuencia automática de solo lectura.",
            )
        }
    }

    private fun trace(line: String) {
        Log.i("JACDL", line)
        runCatching { File(app.filesDir, "live-transcript.txt").appendText(line + "\n") }
        app.getExternalFilesDir(null)?.let { ext ->
            runCatching { File(ext, "live-transcript.txt").appendText(line + "\n") }
        }
    }

    private fun explain(message: String) {
        errors += message
        val recovery = recoveryFor(message)
        trace("ERROR $message")
        trace("RECUPERACION $recovery")
        state.update { it.copy(error = message, recovery = recovery, busy = false, statusLine = "Error de conexión.") }
    }

    private fun recoveryFor(message: String): String {
        val text = message.lowercase()
        return when {
            "permiso" in text || "security" in text -> "En Ajustes, conceda dispositivos cercanos a JAC Diagnostic Lab y repita la búsqueda."
            "timeout" in text || "prompt" in text -> "Acerque el teléfono, confirme el contacto en ON y vuelva a conectar. Si OBDII falla, pruebe OBDBLE."
            "socket" in text || "rfcomm" in text || "busy" in text || "closed" in text -> "Cierre otras aplicaciones OBD, apague y encienda el Bluetooth del teléfono y reintente. No borre códigos."
            "133" in text -> "GATT 133: fallo de radio/enlace. Se reintentó con backoff. Cierre otras apps OBD, reinsertar ELM, o use OBDII clásico emparejado."
            "gatt" in text || "ble" in text -> "El perfil BLE no quedó validado. No se escribieron características desconocidas. Use OBDII clásico si está emparejado."
            "04" in text -> "El comando de borrado quedó bloqueado. No hace falta reintentarlo."
            else -> "Deje el vehículo estacionado con el contacto en ON, verifique que el adaptador esté insertado y repita la conexión."
        }
    }

    private fun selectedDevice(): BluetoothDevice? {
        val address = state.value.selectedAddress ?: return null.also { explain("Seleccione un dispositivo.") }
        return repo.device(address)
    }

    private fun missingPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).filter {
            ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun promote() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DiagnosticConnectionService::class.java))
        } catch (error: Exception) {
            trace("Servicio en primer plano no disponible: ${error.message}. La sesión sigue en la aplicación.")
        }
    }

    private fun bindProductionTransport(candidate: ObdTransport) {
        TransportIsolation.assertProductionOnly(candidate)
        transport = candidate
    }

    private suspend fun disconnectQuiet() {
        val current = transport
        transport = null
        session = null
        ble = null
        state.update { it.copy(linkPhase = LinkPhase.DISCONNECTED) }
        kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { current?.disconnect() } }
    }

    private fun merge(left: List<BtDevice>, right: List<BtDevice>): List<BtDevice> {
        val map = linkedMapOf<String, BtDevice>()
        (left + right).forEach { device -> map[device.address] = device }
        return map.values.sortedWith(compareByDescending<BtDevice> { it.primary }.thenBy { it.name })
    }

    private fun newId(): String = java.time.LocalDateTime.now().toString().replace(":", "").take(15) + "-" + (System.currentTimeMillis() % 10000)

    companion object {
        fun bleCandidates(rows: List<GattCharView>): List<Pair<String, String>> {
            val both = rows.filter { it.canWrite && it.canNotify }.map { it.uuid to it.uuid }
            val writes = rows.filter { it.canWrite }
            val notifies = rows.filter { it.canNotify }
            val same = writes.flatMap { write ->
                notifies.filter { it.serviceUuid == write.serviceUuid }.map { write.uuid to it.uuid }
            }
            val cross = writes.flatMap { write -> notifies.map { write.uuid to it.uuid } }
            val raw = (both + same + cross).distinct()
            return GattObdProfile.rankCandidates(raw)
        }
    }
}
