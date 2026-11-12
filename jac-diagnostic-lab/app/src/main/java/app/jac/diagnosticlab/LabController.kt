package app.jac.diagnosticlab

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
import app.jac.diagnosticlab.elm.ElmSession
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ObdTransport
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.evidence.Sample
import app.jac.diagnosticlab.evidence.SessionRecord
import app.jac.diagnosticlab.evidence.anonymize
import app.jac.diagnosticlab.evidence.payloadHasContent
import app.jac.diagnosticlab.evidence.toCsv
import app.jac.diagnosticlab.evidence.toJson
import app.jac.diagnosticlab.evidence.toTxt
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.decodeFreeze
import app.jac.diagnosticlab.obd.decodeVin
import app.jac.diagnosticlab.obd.milFromPayload
import app.jac.diagnosticlab.elm.InitReport
import app.jac.diagnosticlab.obd.PidDecoder
import app.jac.diagnosticlab.obd.parseAdapterVoltage
import app.jac.diagnosticlab.security.SecurityAssessment
import app.jac.diagnosticlab.security.SecurityDashboard
import app.jac.diagnosticlab.obd.OperationalState
import app.jac.diagnosticlab.obd.VoltageAssessment
import app.jac.diagnosticlab.obd.assessAdapterVoltage
import app.jac.diagnosticlab.obd.SessionTimeMetrics
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
        try {
            state.update { it.copy(busy = true, statusLine = "Validando BLE con ATI…") }
            val raw = inspector.selectAndValidate(writeUuid, notifyUuid)
            transport = inspector
            openSession(inspector.name)
            state.update { it.copy(bleValidated = true, busy = false, statusLine = "BLE validado.", error = null) }
            trace("ATI de validación BLE:\n$raw")
        } catch (error: Exception) {
            state.update { it.copy(bleValidated = false, busy = false) }
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
        state.update { it.copy(busy = true, statusLine = "Leyendo DTC 03, 07 y 0A.") }
        dtcs = listOf(
            current.readDtc(DtcCategory.STORED),
            current.readDtc(DtcCategory.PENDING),
            current.readDtc(DtcCategory.PERMANENT),
        )
        state.update { it.copy(dtcs = dtcs, busy = false, exchanges = current.exchanges.toList()) }
        recompute()
        persist()
    }

    fun readSupported() = scope.launch { readSupportedInternal() }

    fun startPoll(pids: Set<Int>) {
        pollJob?.cancel()
        pollJob = scope.launch {
            state.update { it.copy(polling = true, statusLine = "Registrando sensores, una consulta a la vez.") }
            val allowed = pids.filter { it in state.value.supported && it != 0x00 }
            while (true) {
                val started = System.currentTimeMillis()
                val current = session ?: break
                for (pid in allowed) {
                    val reading = current.readPid(pid)
                    val sample = Sample(
                        System.currentTimeMillis(), pid, reading.name, reading.value, reading.unit,
                        reading.valid, reading.suspect, reading.raw.joinToString(" ") { "%02X".format(it) },
                    )
                    samples = (samples + sample).takeLast(240)
                    state.update { it.copy(samples = samples, exchanges = current.exchanges.toList()) }
                }
                val voltage = current.exchange("ATRV", 2500)
                val parsed = parseAdapterVoltage(voltage.raw)
                if (parsed != null) {
                    samples = (samples + Sample(System.currentTimeMillis(), -1, "Tensión estimada del adaptador", parsed, "V", true, parsed < 11.6 || parsed > 15.2, voltage.raw.trim())).takeLast(240)
                }
                recompute()
                persist()
                val wait = state.value.intervalMs - (System.currentTimeMillis() - started)
                if (wait > 0) delay(wait)
            }
        }
    }

    fun stopPoll() {
        pollJob?.cancel()
        state.update { it.copy(polling = false, statusLine = "Registro de sensores detenido.") }
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
        state.update { it.copy(intervalMs = ms.coerceIn(500L, 5000L)) }
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
                trace("Pares GATT candidatos ${pairs.size}")
                for ((write, notify) in pairs) {
                    val inspector = ble ?: break
                    try {
                        val raw = inspector.selectAndValidate(write, notify)
                        transport = inspector
                        openSession(inspector.name)
                        state.update { it.copy(bleValidated = true, error = null, statusLine = "BLE validado con ATI.") }
                        trace("ATI de validación BLE:\n$raw")
                        break
                    } catch (error: Exception) {
                        errors += "Validación BLE $write: ${error.message}"
                        trace("VALIDACION BLE falló: ${error.message}")
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
        } else if (lowEnergy != null && classicOk) {
            trace("OBDBLE también está visible (${lowEnergy.address}). No se abre una segunda conexión porque el clásico ya respondió.")
        } else if (lowEnergy == null) {
            trace("No se vio un dispositivo llamado OBDBLE.")
        }
        recompute()
        persist()
        state.update { it.copy(busy = false, statusLine = "Secuencia terminada. La transcripción quedó guardada.") }
        trace("FIN autodiagnóstico")
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
                val reading = current.readPid(pid)
                samples = samples + Sample(
                    System.currentTimeMillis(), pid, reading.name, reading.value, reading.unit,
                    reading.valid, reading.suspect, reading.raw.joinToString(" ") { "%02X".format(it) },
                )
            }
            if (interesting.isEmpty()) break
            delay(400)
        }
        samples = samples.takeLast(400)
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

    private suspend fun connectClassic(device: BluetoothDevice): Boolean {
        repo.stopScan()
        val adapter = repo.adapter() ?: return false.also { explain("No hay adaptador Bluetooth.") }
        select(device.address)
        state.update { it.copy(busy = true, link = "Conectando", statusLine = "Abriendo RFCOMM hacia ${device.name}.") }
        val classic = ClassicElmTransport(device, adapter, ::trace)
        return try {
            classic.connect()
            transport = classic
            promote()
            openSession(classic.name)
            true
        } catch (error: Exception) {
            errors += "RFCOMM ${device.name}: ${error.message}"
            explain(error.message ?: "Falló RFCOMM.")
            false
        }
    }

    private suspend fun inspectBle(device: BluetoothDevice): Boolean {
        repo.stopScan()
        select(device.address)
        val inspector = BleInspector(app, device, ::trace)
        ble = inspector
        state.update { it.copy(busy = true, statusLine = "Descubriendo servicios GATT de ${device.name}. No se escribe todavía.") }
        return try {
            val profile = inspector.connectAndDiscover()
            state.update { it.copy(gatt = profile, busy = false, link = "GATT descubierto", error = null) }
            profile.forEach { row -> trace("GATT ${row.serviceUuid} ${row.uuid} ${row.properties.joinToString("|")}") }
            true
        } catch (error: Exception) {
            errors += "BLE ${device.name}: ${error.message}"
            explain(error.message ?: "Falló el descubrimiento GATT.")
            false
        }
    }

    private suspend fun readSupportedInternal() {
        val current = session ?: return
        val first = current.readPid(0x00)
        var supported = if (first.valid) PidDecoder.supported(0x00, first.raw) else emptySet()
        if (0x20 in supported) {
            val next = current.readPid(0x20)
            if (next.valid) supported = supported + PidDecoder.supported(0x20, next.raw)
        }
        if (0x40 in supported) {
            val next = current.readPid(0x40)
            if (next.valid) supported = supported + PidDecoder.supported(0x40, next.raw)
        }
        state.update { it.copy(supported = supported, exchanges = current.exchanges.toList(), statusLine = "PIDs soportados: ${supported.size}.") }
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
                exchanges = report.exchanges,
                statusLine = report.stoppedBecause ?: if (report.ecuTalking) "La ECU respondió a 0100." else "El adaptador contestó, pero 0100 no trajo datos de la ECU.",
                error = report.stoppedBecause,
                recovery = report.stoppedBecause?.let { recoveryFor(it) },
            )
        }
        if (report.stoppedBecause != null) errors += report.stoppedBecause
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

    private suspend fun disconnectQuiet() {
        val current = transport
        transport = null
        session = null
        ble = null
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
            if (both.isNotEmpty()) return both
            val writes = rows.filter { it.canWrite }
            val notifies = rows.filter { it.canNotify }
            val same = writes.flatMap { write ->
                notifies.filter { it.serviceUuid == write.serviceUuid }.map { write.uuid to it.uuid }
            }
            return same.ifEmpty { writes.flatMap { write -> notifies.map { write.uuid to it.uuid } } }.distinct()
        }
    }
}
