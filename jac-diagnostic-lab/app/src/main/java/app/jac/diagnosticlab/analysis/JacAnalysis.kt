package app.jac.diagnosticlab.analysis

import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.DtcScope

enum class Symptom {
    EPC_LAMP,
    ENGINE_WARNING,
    BRAKE_POWER_LOSS,
    CLEARS_AFTER_RESTART,
    RETURNS_WITH_BRAKE,
}

enum class HypothesisStatus { INSUFFICIENT_DATA, SUPPORTING_EVIDENCE, CONTRARY_EVIDENCE }

data class Hypothesis(
    val id: String,
    val title: String,
    val status: HypothesisStatus,
    val supporting: List<String>,
    val contrary: List<String>,
    val missing: List<String>,
    val nextChecks: List<String>,
    val needsJacScanner: Boolean,
)

data class AnalysisReport(
    val confirmed: List<String>,
    val hypotheses: List<Hypothesis>,
    val methodology: String,
)

data class AnalysisInput(
    val symptoms: Set<Symptom>,
    val dtcReads: List<DtcReadResult>,
    val milOn: Boolean?,
    val adapterVoltage: Double?,
    val rpm: Double?,
    val throttleSupported: Boolean,
    val commKinds: Set<ResponseKind>,
    val ecuAnswered0100: Boolean,
)

object JacAnalysis {
    private val commFail = setOf(
        ResponseKind.CAN_ERROR,
        ResponseKind.UNABLE_TO_CONNECT,
        ResponseKind.BUS_INIT_ERROR,
        ResponseKind.DISCONNECTED,
    )

    fun evaluate(input: AnalysisInput): AnalysisReport {
        val codes = input.dtcReads.flatMap { it.codes }
        val confirmed = mutableListOf<String>()
        if (input.ecuAnswered0100) {
            confirmed += "La ECU respondió al PID 0100. Hay comunicación OBD-II en esta sesión."
        }
        input.milOn?.let { on ->
            confirmed += if (on) {
                "El MIL genérico está encendido según el PID 0101. Ese bit no identifica el testigo EPC ni un componente."
            } else {
                "El PID 0101 reportó el MIL genérico apagado. Eso no apaga ni explica un testigo EPC."
            }
        }
        codes.forEach { code ->
            val scope = if (code.scope == DtcScope.GENERIC) "genérico" else "específico del fabricante"
            confirmed += "Hay un código ${code.category.name.lowercase()} $scope ${code.code} (bytes %02X %02X). La causa no está identificada.".format(code.hi, code.lo)
        }
        input.adapterVoltage?.let {
            confirmed += "El adaptador estimó %.1f V con ATRV. No es una medición independiente de batería o tierras.".format(it)
        }

        val uCodes = codes.filter { it.code.startsWith("U") }
        val storedEmpty = input.dtcReads.any { it.confirmedEmpty && it.category.name == "STORED" }
        val anyNoData = input.dtcReads.any { it.kind == ResponseKind.NO_DATA }
        val brakeSymptom = Symptom.BRAKE_POWER_LOSS in input.symptoms || Symptom.RETURNS_WITH_BRAKE in input.symptoms
        val powerSymptom = brakeSymptom || Symptom.ENGINE_WARNING in input.symptoms || Symptom.EPC_LAMP in input.symptoms

        val h1 = hypothesis(
            id = "H1",
            title = "Señales inconsistentes del interruptor de freno",
            supporting = listOfNotNull(if (brakeSymptom) "El conductor observa pérdida de potencia al usar el freno, a veces al volver a frenar." else null),
            contrary = listOfNotNull(if (!brakeSymptom) "En esta sesión no se marcó ese síntoma." else null),
            missing = listOf(
                "El OBD-II genérico no define un PID del interruptor de freno ni de su señal redundante.",
                "Esta aplicación no hace pruebas de frenado.",
            ),
            next = listOf("Con el vehículo estacionado y el contacto en ON, medir el interruptor y la señal redundante con multímetro o con un escáner JAC."),
            scanner = true,
            status = HypothesisStatus.INSUFFICIENT_DATA,
        )

        val h2 = hypothesis(
            id = "H2",
            title = "Relación entre pedal de freno y acelerador electrónico",
            supporting = listOfNotNull(if (brakeSymptom && input.throttleSupported) "Hay síntoma de freno y el PID 0x11 (posición absoluta de mariposa) está soportado; no es el pedal de acelerador ni se midió junto al freno." else null),
            contrary = emptyList(),
            missing = listOf(
                "No existe un PID genérico que cruce el freno con el pedal del acelerador.",
                if (!input.throttleSupported) "El PID 0x11 (mariposa) no se confirmó como soportado." else "Una lectura de mariposa (0x11) con el vehículo quieto no demuestra el pedal ni el freno.",
            ),
            next = listOf("Comparar, en taller y sin una prueba de conducción lanzada por esta app, la señal de freno y la del acelerador con herramienta del fabricante."),
            scanner = true,
            status = HypothesisStatus.INSUFFICIENT_DATA,
        )

        val h3Support = mutableListOf<String>()
        val h3Against = mutableListOf<String>()
        if (powerSymptom) h3Support += "Observación del conductor: EPC, advertencia de motor o pérdida de potencia. No es una medición de la ECU."
        if (input.milOn == true) h3Support += "Medición: el MIL genérico está encendido en el PID 0101. No identifica el testigo EPC ni una estrategia de limitación."
        if (input.milOn == false && storedEmpty && !powerSymptom) h3Against += "MIL apagado, la ECU informó cero códigos almacenados y no se marcó pérdida de potencia."
        val h3 = hypothesis(
            id = "H3",
            title = "Potencia reducida por estrategia de la ECU",
            supporting = h3Support,
            contrary = h3Against,
            missing = listOf("El modo de potencia reducida y el testigo EPC no tienen un PID genérico. Un MIL encendido no nombra la estrategia ni la pieza."),
            next = listOf("Leer con escáner JAC el estado de limitación de par, sin sustituir piezas solo por el testigo."),
            scanner = true,
            status = when {
                input.milOn == true && h3Against.isEmpty() -> HypothesisStatus.SUPPORTING_EVIDENCE
                h3Against.isNotEmpty() && input.milOn != true -> HypothesisStatus.CONTRARY_EVIDENCE
                else -> HypothesisStatus.INSUFFICIENT_DATA
            },
        )

        val volts = input.adapterVoltage
        val rpm = input.rpm
        val h4Support = mutableListOf<String>()
        val h4Against = mutableListOf<String>()
        if (volts != null && rpm != null && rpm > 400 && volts < 13.0) {
            h4Support += "Con el motor en marcha, ATRV estimó %.1f V. Es bajo para carga del alternador, pero sigue siendo la estimación del adaptador.".format(volts)
        } else if (volts != null && (rpm == null || rpm <= 400) && volts < 11.6) {
            h4Support += "Con el motor detenido o sin RPM, ATRV estimó %.1f V. Sugiere alimentación baja, no la pieza que falla.".format(volts)
        }
        if (volts != null && rpm != null && rpm > 400 && volts in 13.2..15.0) {
            h4Against += "Con el motor en marcha, ATRV quedó en %.1f V, dentro de carga típica. No descarta una tierra intermitente.".format(volts)
        }
        val h4 = hypothesis(
            id = "H4",
            title = "Alimentación, conexiones o tierras",
            supporting = h4Support,
            contrary = h4Against,
            missing = listOf("Falta una medición de batería, bornes y tierras con un instrumento independiente. ATRV no la reemplaza."),
            next = listOf("Medir tensión en batería y caídas en tierras con el contacto en ON y, si el taller lo decide, con el motor en ralentí. No se pide una prueba de ruta."),
            scanner = false,
            status = when {
                h4Support.isNotEmpty() -> HypothesisStatus.SUPPORTING_EVIDENCE
                h4Against.isNotEmpty() && h4Support.isEmpty() -> HypothesisStatus.CONTRARY_EVIDENCE
                else -> HypothesisStatus.INSUFFICIENT_DATA
            },
        )

        val h5Support = mutableListOf<String>()
        if (input.commKinds.any { it in commFail }) h5Support += "La sesión registró un fallo de enlace o de bus: ${input.commKinds.filter { it in commFail }}."
        uCodes.forEach { h5Support += "Apareció el código de red ${it.code}. Confirma que ese código fue reportado, no qué módulo está dañado." }
        val h5Against = mutableListOf<String>()
        if (input.ecuAnswered0100 && input.commKinds.none { it in commFail } && uCodes.isEmpty()) {
            h5Against += "0100 respondió y esta sesión no acumuló CAN ERROR, BUS INIT ERROR ni UNABLE TO CONNECT."
        }
        val h5 = hypothesis(
            id = "H5",
            title = "Comunicación entre módulos",
            supporting = h5Support,
            contrary = h5Against,
            missing = listOf("No se interrogaron buses propietarios ni módulos de ABS, BCM o transmisión. Que el OBD genérico responda no demuestra que todos los módulos se hablen."),
            next = listOf("Si el fallo vuelve, registrar la hora y repetir 0100 y los DTC sin borrar nada. Un escáner JAC puede ver redes que este adaptador no muestra."),
            scanner = true,
            status = when {
                h5Support.isNotEmpty() -> HypothesisStatus.SUPPORTING_EVIDENCE
                h5Against.isNotEmpty() -> HypothesisStatus.CONTRARY_EVIDENCE
                else -> HypothesisStatus.INSUFFICIENT_DATA
            },
        )

        val h6Support = codes.map { "Código presente: ${it.code}." }
        val h6Against = mutableListOf<String>()
        if (input.dtcReads.isNotEmpty() && input.dtcReads.all { it.confirmedEmpty }) {
            h6Against += "Las categorías consultadas respondieron con cero códigos. No cubre fallas fuera del OBD genérico ni fallas que no dejan código."
        }
        val h6 = hypothesis(
            id = "H6",
            title = "Otras fallas indicadas por los DTC",
            supporting = h6Support,
            contrary = h6Against,
            missing = listOfNotNull(
                if (anyNoData) "Al menos una categoría respondió NO DATA. Esa respuesta no se cuenta como cero códigos." else null,
                "Sin catálogo J2012-DA verificado no se traduce el código a un texto de falla.",
            ),
            next = listOf("Anotar los bytes y el código. No borrar la memoria. Contrastar el código con documentación del fabricante antes de cambiar piezas."),
            scanner = codes.any { it.scope == DtcScope.MANUFACTURER },
            status = when {
                h6Support.isNotEmpty() -> HypothesisStatus.SUPPORTING_EVIDENCE
                h6Against.isNotEmpty() && !anyNoData -> HypothesisStatus.CONTRARY_EVIDENCE
                else -> HypothesisStatus.INSUFFICIENT_DATA
            },
        )

        return AnalysisReport(
            confirmed = confirmed,
            hypotheses = listOf(h1, h2, h3, h4, h5, h6),
            methodology = "No hay puntaje numérico. Un hallazgo confirmado es un byte o un bit leído. Una hipótesis solo puede tener evidencia a favor, evidencia en contra o datos insuficientes. El síntoma del conductor no confirma una pieza. NO DATA no significa ausencia de códigos. ATRV es la estimación del ELM327.",
        )
    }

    private fun hypothesis(
        id: String,
        title: String,
        supporting: List<String>,
        contrary: List<String>,
        missing: List<String>,
        next: List<String>,
        scanner: Boolean,
        status: HypothesisStatus,
    ) = Hypothesis(id, title, status, supporting, contrary, missing, next, scanner)
}
