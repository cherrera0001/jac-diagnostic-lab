package app.jac.diagnosticlab

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.evidence.ReadingFacts
import app.jac.diagnosticlab.evidence.StreetGuide
import app.jac.diagnosticlab.evidence.readingReport
import app.jac.diagnosticlab.evidence.sha256Hex
import android.Manifest
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class JacApplication : Application() {
    lateinit var lab: LabController
        private set

    override fun onCreate() {
        super.onCreate()
        lab = LabController(this)
    }
}

class DiagnosticViewModel(app: Application) : AndroidViewModel(app) {
    private val lab = (app as JacApplication).lab
    val state = lab.state
    fun refresh() = lab.refresh()
    fun scan() = lab.scan()
    fun stopScan() = lab.stopScan()
    fun select(address: String) = lab.select(address)
    fun connectClassic() = lab.connectSelectedClassic()
    fun inspectBle() = lab.inspectSelectedBle()
    fun validateBle(write: String, notify: String) = lab.validateBle(write, notify)
    fun initialize() = lab.initialize()
    fun readDtcs() = lab.readAllDtcs()
    fun readSupported() = lab.readSupported()
    fun startPoll(pids: Set<Int>) = lab.startPoll(pids)
    fun stopPoll() = lab.stopPoll()
    fun setSymptom(symptom: Symptom, enabled: Boolean) = lab.setSymptom(symptom, enabled)
    fun setProtocol(code: String) = lab.setProtocol(code)
    fun setInterval(ms: Long) = lab.setInterval(ms)
    fun authorizeVin(enabled: Boolean) = lab.authorizeVin(enabled)
    fun readVin() = lab.readVin()
    fun readFreeze() = lab.readFreeze()
    fun export(uri: Uri, format: String, anonymize: Boolean) = lab.export(uri, format, anonymize)
    fun disconnect() = lab.disconnect()
    fun startAutodiag() = lab.startAutodiag()
}

class MainActivity : ComponentActivity() {
    private val model: DiagnosticViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JacTheme { JacApp(model) } }
        if (intent.getBooleanExtra("autodiag", false)) model.startAutodiag()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("autodiag", false)) model.startAutodiag()
    }
}

@Composable
private fun JacTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF5C16C),
            background = Color(0xFF12171C),
            surface = Color(0xFF1C242E),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JacApp(model: DiagnosticViewModel = viewModel()) {
    val state by model.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherPermissions()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Hoy", "Conexión", "Avisos", "Copia", "Revisión").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Solo lectura, con la camioneta detenida. Esta aplicación no borra avisos y no arregla nada. Que logre conectar no significa que puedas seguir circulando.",
                    Modifier.padding(12.dp),
                    color = Color(0xFFF5C16C),
                )
            }
            if (tab != 0) Text(state.statusLine, style = MaterialTheme.typography.titleMedium)
            if (state.error != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Qué ocurrió: ${state.error}")
                        Text("Qué hacer: ${state.recovery.orEmpty()}")
                    }
                }
            }
            when (tab) {
                0 -> TodayScreen(state, model, permissions)
                1 -> LinkScreen(state, model, permissions)
                2 -> CodeScreen(state, model)
                3 -> EvidenceScreen(state, model)
                else -> {
                    AnalysisScreen(state, model)
                    SecurityScreen(state)
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(state: LabState, model: DiagnosticViewModel, requestPermissions: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val liveCodes = state.dtcs.flatMap { it.codes.map { code -> code.code } }
    val saved = remember(state.sessionId, liveCodes, state.ecuTalking) {
        if (state.ecuTalking || liveCodes.isNotEmpty()) null else latestSavedFacts(context)
    }
    val codes = liveCodes.ifEmpty { saved?.storedCodes.orEmpty() }
    val ecuTalking = state.ecuTalking || saved?.ecuTalking == true
    val adapterId = state.adapterId.ifBlank { saved?.adapterId.orEmpty() }
    val hasReading = ecuTalking || codes.isNotEmpty()
    var doneThrough by rememberSaveable { mutableIntStateOf(0) }
    var pdfHash by rememberSaveable { mutableStateOf("") }
    var pdfWhere by rememberSaveable { mutableStateOf("") }
    var pdfError by rememberSaveable { mutableStateOf("") }
    val step = StreetGuide.current(doneThrough, state.busy, hasReading, pdfHash.isNotBlank())
    val current = StreetGuide.steps.first { it.number == step }
    val reading = PlainReadingWriter.explain(state.busy, ecuTalking, codes, adapterId, if (hasReading) null else state.error)
    Text("Paso $step de 8", style = MaterialTheme.typography.labelLarge, color = Color(0xFFF5C16C))
    Text(current.title, style = MaterialTheme.typography.headlineSmall)
    Text("Lo hace: ${current.role}", color = Color(0xFFF5C16C))
    Text(if (step == 6) reading.title else current.detail)
    if (step == 6) reading.lines.forEach { Text(it) }
    if (saved != null && liveCodes.isEmpty() && step >= 6) {
        Text("Esta es la lectura ya guardada en el teléfono, sesión ${saved.sessionId}. No hace falta enchufar de nuevo para armar el informe.")
    }
    if (pdfError.isNotBlank()) Text(pdfError)
    if (step == 8 && pdfHash.isNotBlank()) {
        Text("Informe guardado en $pdfWhere")
        Text("Huella del PDF", style = MaterialTheme.typography.titleMedium)
        Text(pdfHash.chunked(8).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
    val action: Pair<String, () -> Unit> = when (step) {
        1, 2, 3 -> "Ya está" to { doneThrough = step }
        4 -> "4. Permitir Bluetooth" to {
            if (bluetoothReady(context)) doneThrough = 4 else requestPermissions()
        }
        5 -> "5. Leer la camioneta" to { model.startAutodiag() }
        6 -> "Ya lo leí" to { doneThrough = 6 }
        7 -> "7. Guardar informe PDF" to {
            runCatching {
                val facts = ReadingFacts(
                    sessionId = state.sessionId.ifBlank { saved?.sessionId.orEmpty() },
                    adapterId = adapterId,
                    protocolNumber = state.protocolNumber.ifBlank { saved?.protocolNumber.orEmpty() },
                    protocolText = state.protocolText.ifBlank { saved?.protocolText.orEmpty() },
                    voltageText = state.voltageText.ifBlank { saved?.voltageText.orEmpty() },
                    storedCodes = codes,
                    ecuTalking = ecuTalking,
                )
                val document = readingReport(facts)
                val pdf = renderReadingPdf(document.title, document.lines)
                val stamp = facts.sessionId.ifBlank { "sin-sesion" }.replace(Regex("[^A-Za-z0-9-]"), "")
                val pdfName = "JAC-lectura-$stamp.pdf"
                val hash = sha256Hex(pdf)
                val where = saveDownload(context, pdfName, pdf, "application/pdf")
                saveDownload(context, "$pdfName.sha256", "$hash  $pdfName\n".toByteArray(Charsets.UTF_8), "text/plain")
                pdfHash = hash
                pdfWhere = where
                pdfError = ""
            }.onFailure { pdfError = "No se pudo guardar el informe. ${it.message.orEmpty()}" }
        }
        else -> "8. Ya saqué el aparato" to { doneThrough = 8 }
    }
    if (!(step == 8 && doneThrough >= 8)) {
        Button(onClick = action.second, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(action.first) }
    } else {
        Text("Listo. Lleva el PDF al servicio JAC. No borres los avisos antes de que los lean ellos.")
    }
    if (state.busy) Text("Leyendo. No cierres la aplicación y no pises el acelerador.")
    StreetGuide.steps.forEach { item ->
        val status = when {
            item.number < step -> "Hecho"
            item.number == step -> "Ahora"
            else -> "Después"
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(if (item.number == step) Color(0xFFF5C16C) else Color(0xFF2A3440), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${item.number}", color = if (item.number == step) Color(0xFF12171C) else Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("$status · ${item.number}. ${item.title}")
                Text("${item.role}. ${item.detail}", fontSize = 13.sp, color = Color(0xFFC5CDD6))
            }
        }
    }
    Text("Versión 0.4.0. Conexión, Avisos y Copia son el detalle. Esta pantalla es la que se usa.")
}

@Composable
private fun rememberLauncherPermissions(): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    val enable = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    return {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
        else enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
}

@Composable
private fun LinkScreen(state: LabState, model: DiagnosticViewModel, requestPermissions: () -> Unit) {
    Text("Bluetooth ${if (state.bluetoothOn) "encendido" else "apagado"}. Enlace: ${state.link}.")
    Text("Adaptador: ${state.adapterId.ifBlank { "sin ATI todavía" }}")
    Text("Protocolo: ${state.protocolNumber.ifBlank { "sin ATDPN" }} ${state.protocolText}")
    Text("Tensión estimada por el adaptador: ${state.voltageText.ifBlank { "sin ATRV" }}")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = requestPermissions, enabled = !state.busy) { Text("Permisos") }
        Button(onClick = { model.scan() }, enabled = !state.busy) { Text("Buscar") }
        OutlinedButton(onClick = { model.stopScan() }) { Text("Detener") }
    }
    state.devices.forEach { device ->
        val mark = if (device.primary) "Candidato principal" else if (device.candidate) "Posible OBD" else "Otro"
        Text(
            "${device.name}\n${device.address} · ${device.typeLabel} · $mark",
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = state.selectedAddress == device.address) { model.select(device.address) }
                .padding(8.dp),
            color = if (state.selectedAddress == device.address) Color(0xFFF5C16C) else Color.White,
        )
    }
    Text("Protocolo manual, solo opciones del datasheet ELM327. 0 es automático.")
    listOf(
        "0" to "0 Automático",
        "6" to "6 CAN 11 bit / 500 kbaud",
        "7" to "7 CAN 29 bit / 500 kbaud",
        "8" to "8 CAN 11 bit / 250 kbaud",
        "9" to "9 CAN 29 bit / 250 kbaud",
        "3" to "3 ISO 9141-2",
        "4" to "4 KWP 5 baud",
        "5" to "5 KWP rápido",
        "1" to "1 SAE J1850 PWM",
        "2" to "2 SAE J1850 VPW",
    ).forEach { (code, label) ->
        Row(Modifier.selectable(state.protocol == code) { model.setProtocol(code) }) {
            Checkbox(checked = state.protocol == code, onCheckedChange = { model.setProtocol(code) })
            Text(label, Modifier.padding(top = 12.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.connectClassic() }, enabled = !state.busy) { Text("Conectar clásico") }
        Button(onClick = { model.inspectBle() }, enabled = !state.busy) { Text("Inspeccionar BLE") }
    }
    Button(onClick = { model.initialize() }, enabled = !state.busy) { Text("Inicializar y leer 0100") }
    OutlinedButton(onClick = { model.disconnect() }) { Text("Desconectar") }
    if (state.gatt.isNotEmpty()) {
        Text("Servicios GATT. No se escribe una característica hasta elegir una con WRITE y otra con NOTIFY.")
        var write by remember(state.gatt) { mutableStateOf(state.gatt.firstOrNull { it.canWrite }?.uuid.orEmpty()) }
        var notify by remember(state.gatt) { mutableStateOf(state.gatt.firstOrNull { it.canNotify }?.uuid.orEmpty()) }
        state.gatt.forEach { row ->
            Text("${row.uuid}\n${row.properties.joinToString()} · servicio ${row.serviceUuid}", fontSize = 12.sp)
            Row {
                if (row.canWrite) {
                    OutlinedButton(onClick = { write = row.uuid }) { Text(if (write == row.uuid) "Escritura elegida" else "Elegir escritura") }
                }
                if (row.canNotify) {
                    OutlinedButton(onClick = { notify = row.uuid }) { Text(if (notify == row.uuid) "Aviso elegido" else "Elegir aviso") }
                }
            }
        }
        Button(onClick = { model.validateBle(write, notify) }, enabled = write.isNotBlank() && notify.isNotBlank() && !state.busy) {
            Text("Validar BLE con ATI")
        }
        Text(if (state.bleValidated) "BLE validado." else "BLE aún no validado.")
    }
    Transcript(state)
}

@Composable
private fun CodeScreen(state: LabState, model: DiagnosticViewModel) {
    Text("Los códigos salen de los bytes recibidos. Si no hay una definición del SAE J2012-DA, no se inventa un texto de falla.")
    Button(onClick = { model.readDtcs() }, enabled = !state.busy) { Text("Leer almacenados, pendientes y permanentes") }
    Button(onClick = { model.readFreeze() }, enabled = !state.busy) { Text("Leer freeze frame") }
    Row {
        Checkbox(checked = state.authorizeVin, onCheckedChange = model::authorizeVin)
        Text("Autorizo consultar el VIN. Puede anonimizarse al exportar.", Modifier.padding(top = 12.dp))
    }
    Button(onClick = { model.readVin() }, enabled = state.authorizeVin && !state.busy) { Text("Consultar VIN") }
    Text("VIN: ${state.vin ?: "no consultado"}")
    if (state.dtcs.isEmpty()) Text("Todavía no hay una lectura de códigos en esta sesión.")
    state.dtcs.forEach { read ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${read.category} · ${read.kind}")
                Text(read.note)
                if (read.confirmedEmpty) Text("La ECU informó cero códigos de esta categoría.")
                if (read.kind == app.jac.diagnosticlab.elm.ResponseKind.NO_DATA) {
                    Text("NO DATA no significa que el vehículo no tenga códigos.")
                }
                read.codes.forEach { code ->
                    Text("${code.code}  ECU ${code.ecu ?: "sin encabezado"}")
                    Text(code.scopeNote, fontSize = 12.sp)
                }
            }
        }
    }
    state.freeze.forEach { Text("${it.note} ${it.dtc ?: ""}") }
}

@Composable
private fun SensorScreen(state: LabState, model: DiagnosticViewModel) {
    Text("Primero se consultan los PID soportados. El pedal de freno no se asume en un PID genérico. ATRV es la estimación del adaptador.")
    Button(onClick = { model.readSupported() }, enabled = !state.busy) { Text("Consultar PID soportados") }
    val names = mapOf(0x0C to "RPM", 0x05 to "Refrigerante", 0x04 to "Carga", 0x0D to "Velocidad", 0x11 to "Acelerador", 0x0B to "Colector")
    Text(if (state.supported.isEmpty()) "Aún no hay mapa de soporte." else "Soportados en el rango leído: ${state.supported.map { "%02X".format(it) }.joinToString()}")
    names.forEach { (pid, label) ->
        Text("$label: ${if (pid in state.supported) "el mapa lo incluye" else "no aparece en el mapa leído"}")
    }
    Row {
        listOf(1000L to "1 s", 2000L to "2 s", 5000L to "5 s").forEach { (ms, label) ->
            OutlinedButton(onClick = { model.setInterval(ms) }) { Text(if (state.intervalMs == ms) "[$label]" else label) }
        }
    }
    val chosen = names.keys.filter { it in state.supported }.toSet()
    Button(onClick = { model.startPoll(chosen) }, enabled = !state.polling && chosen.isNotEmpty()) { Text("Iniciar registro") }
    OutlinedButton(onClick = { model.stopPoll() }, enabled = state.polling) { Text("Detener registro") }
    state.samples.takeLast(8).forEach { sample ->
        Text("${sample.name}: ${sample.value?.let { "%.2f".format(it) } ?: "sin valor"} ${sample.unit.orEmpty()} ${if (sample.suspect) "(fuera de rango habitual)" else ""} ${if (!sample.valid) "(no válido)" else ""}")
    }
    val series = state.samples.filter { it.pid == 0x0C && it.value != null }.takeLast(40).map { it.value!! }
    if (series.size >= 2) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(8.dp)) {
            val min = series.min()
            val max = series.max().coerceAtLeast(min + 1)
            val path = Path()
            series.forEachIndexed { index, value ->
                val x = index * size.width / (series.lastIndex)
                val y = size.height - ((value - min) / (max - min) * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFFF5C16C), style = Stroke(3f))
            drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height))
        }
        Text("Gráfico de RPM de esta sesión. No es una prueba de conducción.")
    }
}

@Composable
private fun EvidenceScreen(state: LabState, model: DiagnosticViewModel) {
    var anonymize by remember { mutableStateOf(true) }
    Text("Sesión ${state.sessionId.ifBlank { "(aún sin secuencia)" }}. Nada se sube a un servidor.")
    Row {
        Checkbox(checked = anonymize, onCheckedChange = { anonymize = it })
        Text("Anonimizar VIN y dirección Bluetooth al exportar", Modifier.padding(top = 12.dp))
    }
    val json = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri, "json", anonymize)
    }
    val csv = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) model.export(uri, "csv", anonymize)
    }
    val txt = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.export(uri, "txt", anonymize)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { json.launch("jac-sesion.json") }, enabled = state.exchanges.isNotEmpty()) { Text("JSON") }
        Button(onClick = { csv.launch("jac-sensores.csv") }, enabled = state.samples.isNotEmpty()) { Text("CSV") }
        Button(onClick = { txt.launch("jac-diagnostico.txt") }, enabled = state.exchanges.isNotEmpty() || state.error != null) { Text("TXT") }
    }
    Text("La aplicación no tiene botón para borrar sesiones ni códigos.")
    Transcript(state)
}

@Composable
private fun AnalysisScreen(state: LabState, model: DiagnosticViewModel) {
    Text("Síntomas informados por el conductor. Desmárquelos si no corresponden a esta salida. No son mediciones.")
    listOf(
        Symptom.EPC_LAMP to "Se ilumina el indicador EPC",
        Symptom.ENGINE_WARNING to "Advertencia de falla de motor",
        Symptom.BRAKE_POWER_LOSS to "Al frenar, a veces baja la potencia",
        Symptom.CLEARS_AFTER_RESTART to "Puede desaparecer al rearrancar",
        Symptom.RETURNS_WITH_BRAKE to "Puede volver al usar el freno",
    ).forEach { (symptom, label) ->
        Row {
            Checkbox(checked = symptom in state.symptoms, onCheckedChange = { model.setSymptom(symptom, it) })
            Text(label, Modifier.padding(top = 12.dp))
        }
    }
    val report = state.analysis
    if (report == null) {
        Text("Aún no hay análisis.")
        return
    }
    Text(report.methodology, fontSize = 13.sp)
    Text("Hallazgos leídos", style = MaterialTheme.typography.titleMedium)
    if (report.confirmed.isEmpty()) Text("Todavía no hay un byte de la ECU que confirme un hallazgo.")
    report.confirmed.forEach { Text("• $it") }
    report.hypotheses.forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${item.id}. ${item.title}")
                Text(
                    when (item.status) {
                        app.jac.diagnosticlab.analysis.HypothesisStatus.INSUFFICIENT_DATA -> "Datos insuficientes"
                        app.jac.diagnosticlab.analysis.HypothesisStatus.SUPPORTING_EVIDENCE -> "Hay evidencia a favor, no una pieza condenada"
                        app.jac.diagnosticlab.analysis.HypothesisStatus.CONTRARY_EVIDENCE -> "Hay evidencia en contra"
                    },
                )
                item.supporting.forEach { Text("A favor: $it", fontSize = 13.sp) }
                item.contrary.forEach { Text("En contra: $it", fontSize = 13.sp) }
                item.missing.forEach { Text("Falta: $it", fontSize = 13.sp) }
                item.nextChecks.forEach { Text("Siguiente verificación: $it", fontSize = 13.sp) }
                if (item.needsJacScanner) Text("Esto pide escáner JAC o inspección de taller.")
            }
        }
    }
    Text("No se recomienda sustituir piezas solo por estos síntomas.")
}

@Composable
private fun SecurityScreen(state: LabState) {
    Text(
        "C4A Automotive Security. Solo lectura Nivel A. Sin porcentaje de seguridad arbitrario. " +
            "Hallazgos solo con evidencia. El laboratorio adversarial no corre contra el vehículo.",
        fontSize = 13.sp,
    )
    val dash = state.security
    if (dash == null) {
        Text("Aún no hay evaluación. Conecte o espere el recompute de sesión.")
        return
    }
    Text(
        "Controles evaluados: ${dash.controlsEvaluated}. Conocidos/activos en APK u operación: ${dash.controlsKnown}. " +
            "Hallazgos verificados: ${dash.findings.count { it.status == app.jac.diagnosticlab.security.FindingStatus.VERIFIED }}.",
        style = MaterialTheme.typography.titleMedium,
    )
    Text("Inventario", style = MaterialTheme.typography.titleMedium)
    dash.inventory.forEach { row ->
        Text("${row.label}: ${row.value ?: "DESCONOCIDO"} [${row.status}] conf=${row.confidence ?: "-"}", fontSize = 12.sp)
    }
    Text("Hallazgos", style = MaterialTheme.typography.titleMedium)
    dash.findings.forEach { f ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${f.findingId} · ${f.status}")
                Text(f.title)
                Text("Componente: ${f.component}", fontSize = 12.sp)
                Text("Impacto: ${f.impact}", fontSize = 12.sp)
                Text("Recomendación: ${f.recommendation}", fontSize = 12.sp)
                Text("Riesgo residual: ${f.residualRisk}", fontSize = 12.sp)
                if (f.evidenceIds.isNotEmpty()) Text("Evidencia: ${f.evidenceIds.joinToString()}", fontSize = 12.sp)
            }
        }
    }
    Text("Amenazas TARA", style = MaterialTheme.typography.titleMedium)
    dash.threats.forEach { t ->
        Text("${t.id} ${t.title}", fontSize = 13.sp)
        Text("Factibilidad: ${t.feasibility}", fontSize = 12.sp)
        Text("Residual: ${t.residualRisk}", fontSize = 12.sp)
        Text("Medida: ${t.proposedMeasure}", fontSize = 12.sp)
    }
    Text("Hardening", style = MaterialTheme.typography.titleMedium)
    dash.hardening.forEach { h ->
        Text("[${h.layer}] ${h.action} · ${h.status}", fontSize = 12.sp)
    }
    dash.notes.forEach { Text("• $it", fontSize = 12.sp) }
    Text("Diagnóstico EPC/DTC y ciberseguridad son líneas separadas. Los DTC no demuestran un ataque.")
}

@Composable
private fun Transcript(state: LabState) {
    Text("Transcripción original", style = MaterialTheme.typography.titleMedium)
    if (state.exchanges.isEmpty()) {
        Text("Sin comandos en esta sesión.")
        return
    }
    state.exchanges.takeLast(20).forEach { exchange ->
        Text(
            "> ${exchange.command} [${exchange.kind}]\n${exchange.raw.ifBlank { "(sin bytes)" }}\n${exchange.detail}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

private fun bluetoothReady(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
    val granted = ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return granted && BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
}

private fun latestSavedFacts(context: Context): ReadingFacts? {
    val file = File(context.filesDir, "sessions").listFiles()
        ?.filter { it.extension.equals("json", true) }
        ?.maxByOrNull { it.lastModified() }
        ?: return null
    return runCatching {
        val root = JSONObject(file.readText())
        val codes = buildList {
            val reads = root.optJSONArray("dtcs") ?: return@buildList
            for (index in 0 until reads.length()) {
                val list = reads.optJSONObject(index)?.optJSONArray("codes") ?: continue
                for (codeIndex in 0 until list.length()) {
                    val code = list.optJSONObject(codeIndex)?.optString("code").orEmpty()
                    if (code.isNotBlank()) add(code)
                }
            }
        }
        val protocolNumber = root.optString("protocolNumber")
        if (codes.isEmpty() && protocolNumber.isBlank()) return@runCatching null
        ReadingFacts(
            sessionId = root.optString("id"),
            adapterId = root.optString("adapterIdentity"),
            protocolNumber = protocolNumber,
            protocolText = root.optString("protocolText"),
            voltageText = root.optString("adapterVoltage"),
            storedCodes = codes,
            ecuTalking = true,
        )
    }.getOrNull()
}

private fun saveDownload(context: Context, name: String, bytes: ByteArray, mime: String): String {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JAC-lectura")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android no creó el archivo en Descargas")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Android no abrió el archivo")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return "Descargas/JAC-lectura/$name"
    }
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    File(dir, name).writeBytes(bytes)
    return dir.absolutePath + "/" + name
}

private fun renderReadingPdf(title: String, lines: List<String>): ByteArray {
    val document = PdfDocument()
    val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF1A1A1A.toInt()
    }
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        color = 0xFF1A1A1A.toInt()
    }
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
        color = 0xFF5C6570.toInt()
    }
    val pageWidth = 595
    val pageHeight = 842
    val margin = 48f
    val maxWidth = pageWidth - margin * 2
    fun wrap(paint: Paint, text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val rows = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val count = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
            var cut = count.coerceAtMost(rest.length)
            if (cut < rest.length) {
                val space = rest.lastIndexOf(' ', cut - 1)
                if (space > 0) cut = space
            }
            rows += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        return rows
    }
    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = page.canvas
    var y = margin
    fun finishPage() {
        canvas.drawText("JAC Diagnostic Lab 0.4.0  ·  solo lectura  ·  no autoriza a circular", margin, pageHeight - 28f, footer)
        canvas.drawText(pageNumber.toString(), pageWidth - margin - 10f, pageHeight - 28f, footer)
        document.finishPage(page)
    }
    fun ensure(height: Float) {
        if (y + height <= pageHeight - 48f) return
        finishPage()
        pageNumber += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page.canvas
        y = margin
    }
    wrap(heading, title).forEach { row ->
        ensure(22f)
        canvas.drawText(row, margin, y + 16f, heading)
        y += 22f
    }
    y += 8f
    lines.forEach { line ->
        wrap(body, line).forEach { row ->
            val height = if (row.isEmpty()) 8f else 16f
            ensure(height)
            if (row.isNotEmpty()) canvas.drawText(row, margin, y + 11f, body)
            y += height
        }
    }
    finishPage()
    val output = ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}

data class PlainReading(
    val title: String,
    val lines: List<String>,
    val next: String,
)

object PlainReadingWriter {
    private val gloss = mapOf(
        "P0579" to "El crucero, o el interruptor que lo corta cuando frenas, no responde como la computadora espera.",
        "P2299" to "La computadora vio el freno y el acelerador en desacuerdo. No dice qué pieza cambiar.",
    )

    fun explain(
        busy: Boolean,
        ecuTalking: Boolean,
        codes: List<String>,
        adapterId: String,
        error: String?,
    ): PlainReading {
        if (busy) {
            return PlainReading(
                title = "Estoy leyendo. No cierres la aplicación.",
                lines = listOf(
                    "La camioneta está detenida y el contacto sigue en ON.",
                    "No pises el acelerador para probar. Esta lectura no se hace en movimiento.",
                ),
                next = "Espera a que el título cambie. Puede tardar un minuto.",
            )
        }
        if (!error.isNullOrBlank() && !ecuTalking && codes.isEmpty()) {
            return PlainReading(
                title = "No pude completar la lectura.",
                lines = listOf("Qué ocurrió: $error"),
                next = "Revisa la lista de la pantalla y pulsa Leer la camioneta otra vez. No borres nada en otra aplicación.",
            )
        }
        if (!ecuTalking && codes.isEmpty()) {
            return PlainReading(
                title = "Todavía no hay una lectura de esta salida.",
                lines = listOf(
                    "1. Camioneta detenida, freno de mano puesto.",
                    "2. El aparato pequeño va en el enchufe de diagnóstico, debajo del volante.",
                    "3. Contacto en ON. El motor puede estar en marcha, pero no muevas el vehículo.",
                    "4. Cierra otras aplicaciones de diagnóstico.",
                    "5. Bluetooth del teléfono encendido. El aparato ya está emparejado.",
                    "6. Pulsa el botón de abajo y deja el teléfono cerca del aparato.",
                ),
                next = "Cuando termine, esta pantalla dice en palabras qué contestó la computadora.",
            )
        }
        val unique = codes.distinct()
        val lines = buildList {
            if (ecuTalking) add("La computadora del motor contestó.")
            if (adapterId.isNotBlank()) {
                add("El aparato se identificó como «$adapterId». Ese nombre se puede copiar: no prueba que sea original.")
            }
            if (unique.isEmpty()) {
                add("En esta lectura no quedó ningún aviso guardado. Eso no significa que la camioneta esté bien.")
            } else {
                add("Dejó ${unique.size} aviso(s) guardado(s). No los borré.")
                unique.forEach { code ->
                    val text = gloss[code] ?: "Número anotado por la computadora. No traduzco la pieza."
                    add("$code. $text")
                }
            }
            add("La luz EPC del tablero no es la misma luz que esta lectura llama falla genérica.")
            add("JAC no ha confirmado, en las fuentes abiertas revisadas, el texto de estos números para tu T8. El escáner de la marca manda.")
        }
        return PlainReading(
            title = if (unique.isEmpty()) "Hubo comunicación. No hay un aviso guardado en esta lectura." else "Hay avisos guardados. No cambies piezas por esta pantalla.",
            lines = lines,
            next = "Siguiente: copia la lectura en la pestaña Copia y llévala a un servicio JAC. Pide que lean los mismos números sin borrarlos, y que midan el pedal de freno y el del acelerador con la camioneta quieta.",
        )
    }
}
