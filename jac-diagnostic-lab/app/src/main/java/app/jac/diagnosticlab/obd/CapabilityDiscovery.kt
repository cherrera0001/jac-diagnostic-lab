package app.jac.diagnosticlab.obd

/**
 * Inventario real de qué puede / no puede leer la app vía OBD genérico.
 * No inventa soporte OEM. No asume que «sin valor» = falla del auto.
 */
enum class SupportStatus {
    NOT_EVALUATED,
    DECLARED_SUPPORTED,
    DECLARED_UNSUPPORTED,
    READ_OK,
    NO_DATA,
    MALFORMED,
    FORMULA_UNKNOWN,
    REQUIRES_DIFFERENT_HARDWARE,
}

enum class AbsenceReason {
    NONE,
    BITMAP_NOT_READ,
    ECU_NOT_RESPONDING,
    ADAPTER_NOT_CONNECTED,
    PID_NOT_IN_BITMAP,
    RESPONSE_NO_DATA,
    RESPONSE_MALFORMED,
    FORMULA_UNKNOWN,
    REQUIRES_DIFFERENT_HARDWARE,
    NOT_IN_INTEREST_CATALOG,
}

enum class SignalQuality {
    NOT_MEASURED,
    STRUCTURAL_OK,
    VALUE_OK,
    PLAUSIBILITY_NOTE,
    STALE,
}

data class PidCapability(
    val pid: Int?,
    val mode: Int,
    val key: String,
    val standardName: String,
    val displayName: String,
    val supportStatus: SupportStatus,
    val absenceReason: AbsenceReason,
    val sourceEcu: String?,
    val lastRaw: String?,
    val lastValue: Double?,
    val unit: String?,
    val quality: SignalQuality,
    val note: String,
)

data class CapabilitySnapshot(
    val ecuTalking: Boolean,
    val bitmapRead: Boolean,
    val supportedCount: Int,
    val entries: List<PidCapability>,
)

object ObdCapabilityDiscoveryEngine {

    data class Interest(
        val pid: Int?,
        val key: String,
        val standardName: String,
        val displayName: String,
        val hardwareOnly: Boolean = false,
    )

    /** Catálogo de interés diagnóstico (estándar + huecos conocidos). No es el mapa de la ECU. */
    val interestCatalog: List<Interest> = listOf(
        Interest(0x0C, "01-0C", "Engine RPM", "RPM"),
        Interest(0x0D, "01-0D", "Vehicle Speed", "Velocidad"),
        Interest(0x05, "01-05", "Engine Coolant Temperature", "Refrigerante"),
        Interest(0x04, "01-04", "Calculated Load Value", "Carga"),
        Interest(0x0B, "01-0B", "Intake Manifold Absolute Pressure", "MAP"),
        Interest(0x11, "01-11", "Absolute Throttle Position", "Mariposa absoluta"),
        Interest(0x45, "01-45", "Relative Throttle Position", "Mariposa relativa"),
        Interest(0x47, "01-47", "Absolute Throttle Position B", "Mariposa B"),
        Interest(0x49, "01-49", "Accelerator Pedal Position D", "Pedal APP D"),
        Interest(0x4A, "01-4A", "Accelerator Pedal Position E", "Pedal APP E"),
        Interest(0x4B, "01-4B", "Accelerator Pedal Position F", "Pedal APP F"),
        Interest(0x42, "01-42", "Control Module Voltage", "Voltaje módulo"),
        Interest(0x46, "01-46", "Ambient Air Temperature", "Ambiente"),
        Interest(null, "BRAKE-SW", "Brake switch (OEM / hardwire)", "Interruptor de freno", hardwareOnly = true),
    )

    fun build(
        supportedBitmap: Set<Int>,
        bitmapRead: Boolean,
        ecuTalking: Boolean,
        adapterConnected: Boolean,
        sourceEcu: String? = null,
        readings: Map<Int, PidReading> = emptyMap(),
    ): CapabilitySnapshot {
        val entries = interestCatalog.map { interest ->
            when {
                interest.hardwareOnly -> PidCapability(
                    pid = null,
                    mode = 0x01,
                    key = interest.key,
                    standardName = interest.standardName,
                    displayName = interest.displayName,
                    supportStatus = SupportStatus.REQUIRES_DIFFERENT_HARDWARE,
                    absenceReason = AbsenceReason.REQUIRES_DIFFERENT_HARDWARE,
                    sourceEcu = null,
                    lastRaw = null,
                    lastValue = null,
                    unit = null,
                    quality = SignalQuality.NOT_MEASURED,
                    note = "No hay PID OBD-II genérico. Multímetro / escáner JAC / cableado.",
                )
                !adapterConnected -> entry(interest, SupportStatus.NOT_EVALUATED, AbsenceReason.ADAPTER_NOT_CONNECTED, sourceEcu, null,
                    "Sin enlace al adaptador. No se puede afirmar soporte.")
                !ecuTalking && !bitmapRead -> entry(interest, SupportStatus.NOT_EVALUATED, AbsenceReason.ECU_NOT_RESPONDING, sourceEcu, null,
                    "El adaptador puede estar vivo, pero la ECU no respondió a Mode 01. No es «cero fallas».")
                !bitmapRead -> entry(interest, SupportStatus.NOT_EVALUATED, AbsenceReason.BITMAP_NOT_READ, sourceEcu, null,
                    "Aún no se leyó el mapa de PIDs soportados (0100/0120/…).")
                interest.pid !in supportedBitmap -> entry(
                    interest,
                    SupportStatus.DECLARED_UNSUPPORTED,
                    AbsenceReason.PID_NOT_IN_BITMAP,
                    sourceEcu,
                    null,
                    "La ECU no declaró este PID en el bitmap Mode 01 leído.",
                )
                else -> {
                    val reading = interest.pid?.let { readings[it] }
                    when {
                        reading == null -> entry(
                            interest,
                            SupportStatus.DECLARED_SUPPORTED,
                            AbsenceReason.NONE,
                            sourceEcu,
                            null,
                            "Declarado en bitmap. Aún no hay sonda de valor en esta sesión.",
                        )
                        !reading.valid && reading.note.contains("NO DATA", ignoreCase = true) ->
                            entry(interest, SupportStatus.NO_DATA, AbsenceReason.RESPONSE_NO_DATA, sourceEcu, reading,
                                "Bitmap lo declara, pero la sonda devolvió NO DATA.")
                        !reading.valid ->
                            entry(interest, SupportStatus.MALFORMED, AbsenceReason.RESPONSE_MALFORMED, sourceEcu, reading,
                                reading.note)
                        reading.interpretationStatus == InterpretationStatus.UNKNOWN && reading.value == null ->
                            entry(interest, SupportStatus.FORMULA_UNKNOWN, AbsenceReason.FORMULA_UNKNOWN, sourceEcu, reading,
                                "Bytes presentes; fórmula local no catalogada.")
                        else -> PidCapability(
                            pid = interest.pid,
                            mode = 0x01,
                            key = interest.key,
                            standardName = interest.standardName,
                            displayName = interest.displayName,
                            supportStatus = SupportStatus.READ_OK,
                            absenceReason = AbsenceReason.NONE,
                            sourceEcu = sourceEcu,
                            lastRaw = reading.raw.joinToString(" ") { "%02X".format(it) },
                            lastValue = reading.value,
                            unit = reading.unit,
                            quality = if (reading.suspect) SignalQuality.PLAUSIBILITY_NOTE else SignalQuality.VALUE_OK,
                            note = reading.note,
                        )
                    }
                }
            }
        }
        return CapabilitySnapshot(
            ecuTalking = ecuTalking,
            bitmapRead = bitmapRead,
            supportedCount = supportedBitmap.size,
            entries = entries,
        )
    }

    /** Páginas Mode 01 a consultar en cadena si el bit «next» está set. */
    val supportPages: List<Int> = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    private fun entry(
        interest: Interest,
        status: SupportStatus,
        reason: AbsenceReason,
        ecu: String?,
        reading: PidReading?,
        note: String,
    ) = PidCapability(
        pid = interest.pid,
        mode = 0x01,
        key = interest.key,
        standardName = interest.standardName,
        displayName = interest.displayName,
        supportStatus = status,
        absenceReason = reason,
        sourceEcu = ecu,
        lastRaw = reading?.raw?.joinToString(" ") { "%02X".format(it) },
        lastValue = reading?.value,
        unit = reading?.unit,
        quality = SignalQuality.NOT_MEASURED,
        note = note,
    )
}
