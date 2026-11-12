package app.jac.diagnosticlab

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
import kotlinx.coroutines.CancellationException
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
    val authorizeVin: Boolean = false,
    val error: String? = null,
    val recovery: String? = null,
    val sessionId: String = "",
    val freeze: List<FreezeNote> = emptyList(),
    val busy: Boolean = false,
    val statusLine: String = "Listo para buscar el adaptador.",
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
)

class LabController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = BluetoothRepository(app)
    private val prefs = app.getSharedPreferences("jaclab", Context.MODE_PRIVATE)
    val state = MutableStateFlow(LabState())
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
        if (!state.value.authorizeVin) {
            explain("El VIN no se consulta sin autorización.")
            return@launch
        }
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

    fun disconnect() {
        stopPoll()
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
        state.update { it.copy(link = "Desconectado", bleValidated = false, busy = false, statusLine = "Desconectado. La evidencia guardada no se borró.") }
    }

    fun startAutodiag() {
        if (!autodiagGate.compareAndSet(false, true)) return
        scope.launch {
            try {
                autodiagnose()
            } finally {
                autodiagGate.set(false)
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
        if (!autodiagGate.compareAndSet(false, true)) return
        scope.launch {
            try {
                reconnectKeepEvidence()
            } finally {
                autodiagGate.set(false)
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
            var supported = emptySet<Int>()
            var bitmapRead = false
            for (page in ObdCapabilityDiscoveryEngine.supportPages) {
                if (page != 0x00 && page !in supported) break
                val reading = current.readPid(page)
                if (!reading.valid) {
                    if (page == 0x00) break
                    continue
                }
                bitmapRead = true
                supported = supported + PidDecoder.supported(page, reading.raw)
            }
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
