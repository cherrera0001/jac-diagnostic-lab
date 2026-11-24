package app.jac.diagnosticlab.car

import app.jac.diagnosticlab.LabState
import app.jac.diagnosticlab.analysis.DrivingGuidanceEngine
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.analysis.ShiftCue
import app.jac.diagnosticlab.bt.LinkPhase
import app.jac.diagnosticlab.dynamic.GuidedTests
import app.jac.diagnosticlab.obd.DtcReadState
import java.util.Locale
import kotlin.math.roundToInt

enum class CarTone { OK, WARN, ALERT, ACCENT, MUTED }

enum class CarTileTarget { DETAIL, STATUS }

data class CarTile(
    val id: String,
    val title: String,
    val valueText: String,
    val unitText: String,
    val subtitle: String,
    val tone: CarTone,
    val progress: Float,
    val target: CarTileTarget,
)

data class CarRowItem(
    val title: String,
    val line1: String,
    val line2: String? = null,
)

data class CarDashboardSnapshot(
    val headerTitle: String,
    val connected: Boolean,
    val liveRunning: Boolean,
    val primaryActionLabel: String,
    val tiles: List<CarTile>,
    val detailRows: List<CarRowItem>,
    val statusRows: List<CarRowItem>,
)

/**
 * Modelo puro (JVM) del tablero horizontal para Android Auto.
 * Diseñado para lectura calmada en pantalla apaisada (grilla 3×2, 6 mosaicos):
 * - Nunca inventa ceros si falta un PID (muestra «—» y «Sin dato»).
 * - Respeta los límites de plantillas de Android Auto (6 mosaicos, <= 4 filas en Pane, <= 6 filas en List).
 * - Prioriza métricas clave para la JAC T8 diésel: RPM/velocidad, refrigerante real, voltaje ECU, turbo/riel, DPF y DTCs.
 */
object CarDashboardModel {
    private val voltRegex = Regex("""(\d+(?:[.,]\d+)?)""")

    private fun parseVoltageText(raw: String): Double? =
        voltRegex.find(raw)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()

    /**
     * Vista de una sola fila (3 mosaicos) para pantallas de poca altura: sin segunda fila cortada ni scroll.
     * Motor · Refrigerante (con la tensión ECU en su línea de estado) · Estado/DTC.
     * Si el DPF está en regeneración activa, ese aviso («no apagar») reemplaza al de refrigerante.
     * Turbo/riel, DPF y temperaturas siguen en la vista de detalle (clic en Motor o Refrigerante).
     */
    fun glanceTiles(tiles: List<CarTile>): List<CarTile> {
        fun byId(id: String) = tiles.firstOrNull { it.id == id }
        val motor = byId("RPM")
        val ect = byId("ECT")
        val volt = byId("VOLT")
        val dpf = byId("DPF")
        val status = byId("DTC")
        val middle = when {
            dpf != null && dpf.valueText == "REGEN" -> dpf.copy(title = "DPF regenera", subtitle = "No apagar")
            ect != null -> ect.copy(
                subtitle = volt?.valueText?.takeIf { it != "—" }?.let { "$it V" } ?: ect.subtitle,
            )
            else -> null
        }
        return listOfNotNull(motor, middle, status)
    }

    fun from(state: LabState): CarDashboardSnapshot {
        val scanMap = state.scanSignals.associate { it.key to it.value }
        val liveMap = state.testLive?.values.orEmpty()
        fun v(key: String): Double? = liveMap[key] ?: scanMap[key]

        val connected = state.ecuTalking && state.linkPhase != LinkPhase.DISCONNECTED
        val liveRunning = state.activeTest == "LIVE"

        val rpm = v(GuidedTests.RPM)
        val speed = v(GuidedTests.SPEED)
        val load = v(GuidedTests.LOAD)
        val ect = v(GuidedTests.ECT)
        val volt = v(GuidedTests.VOLT) ?: parseVoltageText(state.voltageText)
        val mapKpa = v(GuidedTests.MAP)
        val boostAct = v(GuidedTests.BOOST_ACT)
        val railKpa = v(GuidedTests.RAIL_ACT) ?: v(GuidedTests.RAIL)
        val maf = v(GuidedTests.MAF)
        val pedal = v(GuidedTests.PEDAL)
        val torque = v(GuidedTests.TORQUE)
        val demand = v(GuidedTests.DEMAND)
        val dpfDp = v(GuidedTests.DPF_DP)
        val regen = v(GuidedTests.REGEN)
        val egt = v(GuidedTests.EGT) ?: v(GuidedTests.DPF_IN)
        val oil = v(GuidedTests.OIL)
        val fuel = v(GuidedTests.FUEL)
        val fuelEstimate = state.fuelEstimate
            ?: v(GuidedTests.FUEL_LEVEL)?.let { lvl ->
                app.jac.diagnosticlab.analysis.FuelEstimator.estimate(
                    supportedPids = state.supported,
                    pid2fLevelPercent = lvl,
                    fullTankAnchor = state.fullTankAnchor,
                    tankCapacityLiters = state.tankCapacityLiters,
                )
            }

        // 1. Motor + guía de cambio calibrada JAC T8 (RPM + marcha inferida + sugerencia concreta «↑ 3ª» / «↓ 4ª»)
        val guidance = DrivingGuidanceEngine.evaluate(rpm, speed, load, ect)
        val rpmTile = if (rpm != null) {
            val rpmInt = rpm.roundToInt()
            val (unit, sub, tone) = when (guidance.cue) {
                ShiftCue.SHIFT_UP -> {
                    val target = guidance.recommendedGear
                    Triple(
                        if (target != null) "↑ ${target}ª" else "SUBIR",
                        if (target != null) "↑ Subir a ${target}ª · ${speed?.roundToInt() ?: 0} km/h" else "↑ Subir marcha",
                        CarTone.ACCENT,
                    )
                }
                ShiftCue.SHIFT_DOWN -> {
                    val target = guidance.recommendedGear
                    Triple(
                        if (target != null) "↓ ${target}ª" else "BAJAR",
                        if (target != null) "↓ Bajar a ${target}ª · Carga ${load?.roundToInt() ?: 0}%" else "↓ Bajar marcha",
                        if (guidance.isLugging) CarTone.ALERT else CarTone.WARN,
                    )
                }
                else -> {
                    val gearPart = guidance.gearInference.gear?.let { "${it}ª" }
                    val subTxt = buildList {
                        gearPart?.let { add(it) }
                        speed?.let { add("${it.roundToInt()} km/h") }
                        load?.let { add("Carga ${it.roundToInt()}%") }
                    }.joinToString(" · ").ifBlank { if (rpmInt < 400) "Motor detenido" else "En marcha" }
                    val t = when {
                        rpmInt >= 4000 -> CarTone.ALERT
                        rpmInt >= 3200 -> CarTone.WARN
                        rpmInt >= 500 -> CarTone.OK
                        else -> CarTone.MUTED
                    }
                    Triple(gearPart ?: "RPM", subTxt, t)
                }
            }
            CarTile("RPM", "Motor / Cambio", "$rpmInt", unit, sub, tone, (rpm / 4500.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
        } else {
            CarTile("RPM", "Motor / Cambio", "—", "RPM", if (connected) "Sin lectura" else "Desconectado", CarTone.MUTED, 0f, CarTileTarget.DETAIL)
        }

        // 2. Refrigerante real (las agujas del tablero suavizan entre 75 y 105 °C)
        val ectTile = if (ect != null) {
            val c = ect.roundToInt()
            val (tone, sub) = when {
                c >= 105 -> CarTone.ALERT to "¡Temperatura alta!"
                c >= 98 -> CarTone.WARN to "Elevada · vigilar"
                c >= 65 -> CarTone.OK to "Temperatura normal"
                else -> CarTone.ACCENT to "Motor frío"
            }
            CarTile("ECT", "Refrigerante", "$c°", "°C", sub, tone, ((ect - 20.0) / 100.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
        } else {
            CarTile("ECT", "Refrigerante", "—", "°C", "Sin dato", CarTone.MUTED, 0f, CarTileTarget.DETAIL)
        }

        // 3. Batería / Alternador (PID 0x42 real de ECU)
        val voltTile = if (volt != null) {
            val txt = String.format(Locale.US, "%.1f", volt)
            val (tone, sub) = when {
                volt < 11.8 -> CarTone.ALERT to "Batería baja"
                volt > 14.9 -> CarTone.ALERT to "Sobretensión"
                volt < 12.4 -> CarTone.WARN to "Revisar carga"
                volt >= 13.4 -> CarTone.OK to "Alternador OK"
                else -> CarTone.OK to "Tensión estable"
            }
            CarTile("VOLT", "Batería ECU", txt, "V", sub, tone, ((volt - 10.5) / 5.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
        } else {
            CarTile("VOLT", "Batería ECU", "—", "V", "Sin dato", CarTone.MUTED, 0f, CarTileTarget.DETAIL)
        }

        // 4. Turbo y Riel common-rail
        val boostVal = boostAct ?: mapKpa
        val railMpa = railKpa?.let { it / 1000.0 }
        val turboTile = when {
            boostVal != null -> {
                val kpa = boostVal.roundToInt()
                val sub = railMpa?.let { String.format(Locale.US, "Riel %.0f MPa", it) } ?: "Presión admisión"
                CarTile("TURBO", "Turbo / Riel", "$kpa", "kPa", sub, CarTone.ACCENT, ((boostVal - 80.0) / 180.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
            }
            railMpa != null -> {
                val txt = String.format(Locale.US, "%.0f", railMpa)
                CarTile("TURBO", "Riel diésel", txt, "MPa", "Common-rail", CarTone.ACCENT, (railMpa / 180.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
            }
            else -> CarTile("TURBO", "Turbo / Riel", "—", "kPa", "Sin dato", CarTone.MUTED, 0f, CarTileTarget.DETAIL)
        }

        // 5. DPF y temperatura de escape (crítico en diésel)
        val regenOn = regen != null && regen >= 0.5
        val dpfTile = when {
            regenOn -> {
                val sub = egt?.let { "No apagar · ${it.roundToInt()} °C" } ?: "No apagar el motor"
                CarTile("DPF", "DPF Escape", "REGEN", "ACTIVA", sub, CarTone.WARN, 0.9f, CarTileTarget.DETAIL)
            }
            dpfDp != null -> {
                val tone = when {
                    dpfDp < 0.0 -> CarTone.MUTED // P0.3 plausibilidad
                    dpfDp >= 25.0 -> CarTone.ALERT
                    dpfDp >= 12.0 -> CarTone.WARN
                    else -> CarTone.OK
                }
                val sub = when {
                    dpfDp < 0.0 -> "No verificado (<0)"
                    egt != null -> "EGT ${egt.roundToInt()} °C"
                    else -> "Presión diferencial"
                }
                val valTxt = String.format(Locale.US, "%.1f", dpfDp)
                CarTile("DPF", "DPF Escape", valTxt, "kPa", sub, tone, (dpfDp / 30.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
            }
            egt != null -> {
                CarTile("DPF", "Escape EGT", "${egt.roundToInt()}°", "°C", "Temperatura gas", CarTone.OK, (egt / 700.0).toFloat().coerceIn(0f, 1f), CarTileTarget.DETAIL)
            }
            else -> CarTile("DPF", "DPF Escape", "—", "DPF", "Sin dato", CarTone.MUTED, 0f, CarTileTarget.DETAIL)
        }

        // 6. Estado / Códigos DTC (semántica P0.2: no leído != OK)
        val allCodes = state.dtcs.flatMap { it.codes }.map { it.code }.distinct()
        val hasValidEmpty = state.dtcs.any { it.state == DtcReadState.VALID_EMPTY }
        val hasCheckHealth = state.health.any { it.status == HealthStatus.CHECK }
        val hasAttnHealth = state.health.any { it.status == HealthStatus.ATTENTION }
        val statusTile = when {
            allCodes.isNotEmpty() -> {
                val preview = allCodes.take(2).joinToString(", ") + if (allCodes.size > 2) "…" else ""
                CarTile("DTC", "Estado / DTC", "${allCodes.size}", "DTC", preview, CarTone.ALERT, 1f, CarTileTarget.STATUS)
            }
            hasCheckHealth -> CarTile("DTC", "Estado / DTC", "REV", "AVISO", "Revisar alertas", CarTone.ALERT, 0.85f, CarTileTarget.STATUS)
            hasAttnHealth -> CarTile("DTC", "Estado / DTC", "ATT", "AVISO", "Atención en salud", CarTone.WARN, 0.6f, CarTileTarget.STATUS)
            hasValidEmpty -> CarTile("DTC", "Estado / DTC", "OK", "0 DTC", "Sin códigos activos", CarTone.OK, 1f, CarTileTarget.STATUS)
            else -> CarTile("DTC", "Estado / DTC", "—", "DTC", if (connected) "Sin leer DTC" else "Sin conexión", CarTone.MUTED, 0f, CarTileTarget.STATUS)
        }

        val tiles = listOf(rpmTile, ectTile, voltTile, turboTile, dpfTile, statusTile)

        // Detalle calmado (máx 4 filas para PaneTemplate de Android Auto)
        val detailRows = listOf(
            CarRowItem(
                title = "Pedal y par motor",
                line1 = "Pedal: ${fmt(pedal, "%", 0)} · Demanda: ${fmt(demand, "%", 0)} · Par: ${fmt(torque, "%", 0)}",
                line2 = "RPM: ${fmt(rpm, "", 0)} · Velocidad: ${fmt(speed, " km/h", 0)} · Carga: ${fmt(load, "%", 0)}",
            ),
            CarRowItem(
                title = "Combustible y riel",
                line1 = "Riel: ${railMpa?.let { String.format(Locale.US, "%.1f MPa", it) } ?: "—"} · Flujo: ${fmt(fuel, " L/h", 1)}",
                line2 = fuelEstimate?.estimatedRemainingLiters?.let { estL ->
                    String.format(Locale.US, "Estanque: ~%.1f L (%s)", estL, fuelEstimate.classificationLabel)
                },
            ),
            CarRowItem(
                title = "Turbo y admisión",
                line1 = "Turbo: ${fmt(boostAct, " kPa", 0)} · MAP: ${fmt(mapKpa, " kPa", 0)} · MAF: ${fmt(maf, " g/s", 1)}",
            ),
            CarRowItem(
                title = "DPF y temperaturas",
                line1 = "Refrigerante: ${fmt(ect, " °C", 0)} · Aceite: ${fmt(oil, " °C", 0)} · EGT: ${fmt(egt, " °C", 0)}",
                line2 = "DPF ΔP: ${fmt(dpfDp, " kPa", 1)} · Regeneración: ${if (regenOn) "ACTIVA (no apagar)" else if (regen != null) "Inactiva" else "—"}",
            ),
        )

        // Estado y códigos (máx 6 filas para ListTemplate de Android Auto)
        val statusRows = buildList {
            val ecuName = state.vehicleInfo?.ecuName?.takeIf { it.isNotBlank() } ?: state.declaredModel.ifBlank { "JAC T8" }
            add(
                CarRowItem(
                    title = if (connected) "Conectado · $ecuName" else "Sin conexión OBD",
                    line1 = if (state.busy) state.statusLine else state.linkPhase.label,
                    line2 = if (liveRunning) String.format(Locale.US, "En vivo · %.1f Hz", state.testHz) else state.appVersion.ifBlank { null },
                ),
            )
            if (allCodes.isNotEmpty()) {
                allCodes.take(3).forEach { code ->
                    val cats = state.dtcs.filter { r -> r.codes.any { it.code == code } }.joinToString("/") { it.category.name }
                    add(CarRowItem(title = "Código $code", line1 = "Categoría: $cats"))
                }
            } else {
                add(
                    CarRowItem(
                        title = if (hasValidEmpty) "Sin códigos de falla (0 DTC)" else "Códigos DTC no leídos",
                        line1 = if (hasValidEmpty) "Mode 03 confirmó memoria vacía." else "Ejecute un escaneo completo desde el teléfono.",
                    ),
                )
            }
            state.health
                .filter { it.status == HealthStatus.CHECK || it.status == HealthStatus.ATTENTION }
                .take(2)
                .forEach { h ->
                    add(CarRowItem(title = "${h.system}: ${h.title}", line1 = h.detail))
                }
        }.take(6)

        val headerTitle = when {
            liveRunning -> "JAC T8 · En vivo"
            connected -> "JAC T8 · Pausado"
            state.busy -> "JAC T8 · Conectando…"
            else -> "JAC T8 · Sin conexión"
        }
        val primaryAction = when {
            !connected -> "Conectar"
            liveRunning -> "Pausar"
            else -> "En vivo"
        }

        return CarDashboardSnapshot(
            headerTitle = headerTitle,
            connected = connected,
            liveRunning = liveRunning,
            primaryActionLabel = primaryAction,
            tiles = tiles,
            detailRows = detailRows,
            statusRows = statusRows,
        )
    }

    private fun fmt(value: Double?, suffix: String, decimals: Int): String {
        if (value == null) return "—"
        return if (decimals == 0) "${value.roundToInt()}$suffix"
        else String.format(Locale.US, "%.${decimals}f%s", value, suffix)
    }
}
