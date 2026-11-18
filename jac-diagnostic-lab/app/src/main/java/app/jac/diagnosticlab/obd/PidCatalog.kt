package app.jac.diagnosticlab.obd

/**
 * Catálogo SAE J1979 / J1979-DA para Mode 01, con PIDs de varias señales.
 *
 * Cada PID devuelve 0..n [Signal]. Los PIDs con byte de soporte (0x66–0x8B) solo
 * entregan las señales cuyo bit de soporte vino en 1: no se inventa un sensor ausente.
 * Fórmulas genéricas; no son especificación JAC.
 */
data class Signal(
    val key: String,
    val pid: Int,
    val name: String,
    val value: Double?,
    val unit: String,
    val text: String? = null,
)

data class PidInfo(
    val pid: Int,
    val name: String,
    /** Bytes de datos tras `41 PID`. Necesario para separar respuestas multi-PID. */
    val size: Int,
    val decode: (List<Int>) -> List<Signal>,
)

object PidCatalog {
    private fun u16(d: List<Int>, i: Int) = d[i] * 256 + d[i + 1]
    private fun s16(d: List<Int>, i: Int): Int { val v = u16(d, i); return if (v >= 0x8000) v - 0x10000 else v }
    private fun u32(d: List<Int>, i: Int) = (d[i].toLong() shl 24) or (d[i + 1].toLong() shl 16) or (d[i + 2].toLong() shl 8) or d[i + 3].toLong()
    private fun pct(x: Int) = x * 100.0 / 255.0
    private fun trim(x: Int) = (x - 128) * 100.0 / 128.0
    private fun bit(a: Int, n: Int) = (a shr n) and 1 == 1

    private fun one(pid: Int, name: String, unit: String, size: Int, f: (List<Int>) -> Double) =
        PidInfo(pid, name, size) { d -> listOf(Signal("%02X".format(pid), pid, name, f(d), unit)) }

    private fun enumOne(pid: Int, name: String, size: Int, f: (List<Int>) -> String) =
        PidInfo(pid, name, size) { d -> listOf(Signal("%02X".format(pid), pid, name, d[0].toDouble(), "", f(d))) }

    private fun sig(pid: Int, sub: String, name: String, value: Double?, unit: String) =
        Signal("%02X.%s".format(pid, sub), pid, name, value, unit)

    private val fuelSystem = mapOf(
        0 to "sin dato", 1 to "lazo abierto (motor frío)", 2 to "lazo cerrado", 4 to "lazo abierto (carga/corte)",
        8 to "lazo abierto (falla del sistema)", 16 to "lazo cerrado con falla de sensor",
    )
    private val fuelType = mapOf(
        1 to "Gasolina", 2 to "Metanol", 3 to "Etanol", 4 to "Diésel", 5 to "GLP", 6 to "GNC", 7 to "Propano",
        8 to "Eléctrico", 9 to "Bi-fuel gasolina", 0x0C to "Bi-fuel GLP", 0x11 to "Híbrido gasolina", 0x13 to "Híbrido diésel",
        0x14 to "Híbrido eléctrico",
    )
    private val obdStandard = mapOf(
        1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD y OBD-II", 4 to "OBD-I", 5 to "No OBD", 6 to "EOBD (Europa)",
        7 to "EOBD y OBD-II", 8 to "EOBD y OBD", 9 to "EOBD, OBD y OBD-II", 0x0A to "JOBD", 0x0D to "JOBD y EOBD",
        0x11 to "Brasil OBD fase 1", 0x16 to "HD OBD", 0x1B to "China OBD", 0x1D to "Corea OBD", 0x21 to "WWH-OBD",
    )

    private val entries: List<PidInfo> = buildList {
        add(PidInfo(0x03, "Estado del sistema de combustible", 2) { d ->
            listOf(
                Signal("03.A", 0x03, "Sistema combustible 1", d[0].toDouble(), "", fuelSystem[d[0]] ?: "código ${d[0]}"),
                Signal("03.B", 0x03, "Sistema combustible 2", d[1].toDouble(), "", fuelSystem[d[1]] ?: "código ${d[1]}"),
            )
        })
        add(one(0x04, "Carga calculada", "%", 1) { pct(it[0]) })
        add(one(0x05, "Refrigerante", "°C", 1) { it[0] - 40.0 })
        add(one(0x06, "STFT banco 1", "%", 1) { trim(it[0]) })
        add(one(0x07, "LTFT banco 1", "%", 1) { trim(it[0]) })
        add(one(0x08, "STFT banco 2", "%", 1) { trim(it[0]) })
        add(one(0x09, "LTFT banco 2", "%", 1) { trim(it[0]) })
        add(one(0x0A, "Presión de combustible", "kPa", 1) { it[0] * 3.0 })
        add(one(0x0B, "Presión colector (MAP)", "kPa", 1) { it[0].toDouble() })
        add(one(0x0C, "RPM", "rpm", 2) { u16(it, 0) / 4.0 })
        add(one(0x0D, "Velocidad", "km/h", 1) { it[0].toDouble() })
        add(one(0x0E, "Avance de encendido", "°", 1) { it[0] / 2.0 - 64.0 })
        add(one(0x0F, "Temperatura admisión (IAT)", "°C", 1) { it[0] - 40.0 })
        add(one(0x10, "Flujo de aire (MAF)", "g/s", 2) { u16(it, 0) / 100.0 })
        add(one(0x11, "Mariposa absoluta", "%", 1) { pct(it[0]) })
        add(PidInfo(0x13, "Sondas O2 presentes", 1) { d -> listOf(Signal("13", 0x13, "Sondas O2 presentes", d[0].toDouble(), "", "mapa 0x%02X".format(d[0]))) })
        for (n in 0..7) {
            val pid = 0x14 + n
            add(PidInfo(pid, "Sonda O2 #${n + 1}", 2) { d ->
                listOf(
                    sig(pid, "V", "O2 #${n + 1} tensión", d[0] / 200.0, "V"),
                    sig(pid, "T", "O2 #${n + 1} STFT", if (d[1] == 0xFF) null else trim(d[1]), "%"),
                )
            })
        }
        add(enumOne(0x1C, "Norma OBD", 1) { obdStandard[it[0]] ?: "código ${it[0]}" })
        add(PidInfo(0x1E, "Entrada auxiliar", 1) { d -> listOf(Signal("1E", 0x1E, "Toma de fuerza (PTO)", d[0].toDouble() % 2, "", if (bit(d[0], 0)) "activa" else "inactiva")) })
        add(one(0x1F, "Tiempo desde arranque", "s", 2) { u16(it, 0).toDouble() })
        add(one(0x21, "Distancia con MIL encendida", "km", 2) { u16(it, 0).toDouble() })
        add(one(0x22, "Presión riel (relativa a vacío)", "kPa", 2) { u16(it, 0) * 0.079 })
        add(one(0x23, "Presión riel combustible", "kPa", 2) { u16(it, 0) * 10.0 })
        for (n in 0..7) {
            val pid = 0x24 + n
            add(PidInfo(pid, "Sonda O2 #${n + 1} (lambda/V)", 4) { d ->
                listOf(sig(pid, "L", "O2 #${n + 1} lambda", u16(d, 0) * 2.0 / 65536.0, "λ"), sig(pid, "V", "O2 #${n + 1} tensión", u16(d, 2) * 8.0 / 65536.0, "V"))
            })
        }
        add(one(0x2C, "EGR comandada", "%", 1) { pct(it[0]) })
        add(one(0x2D, "Error de EGR", "%", 1) { trim(it[0]) })
        add(one(0x2E, "Purga EVAP comandada", "%", 1) { pct(it[0]) })
        add(one(0x2F, "Nivel de combustible", "%", 1) { pct(it[0]) })
        add(one(0x30, "Calentamientos desde borrado", "", 1) { it[0].toDouble() })
        add(one(0x31, "Distancia desde borrado", "km", 2) { u16(it, 0).toDouble() })
        add(one(0x32, "Presión vapor EVAP", "Pa", 2) { s16(it, 0) / 4.0 })
        add(one(0x33, "Presión barométrica", "kPa", 1) { it[0].toDouble() })
        for (n in 0..7) {
            val pid = 0x34 + n
            add(PidInfo(pid, "Sonda O2 banda ancha #${n + 1}", 4) { d ->
                // Todo en cero = sonda sin dato (calefactor/escape frío), no lambda 0.
                if (d.all { it == 0 }) return@PidInfo listOf(sig(pid, "L", "O2 ancha #${n + 1} lambda", null, "λ"))
                listOf(
                    sig(pid, "L", "O2 ancha #${n + 1} lambda", u16(d, 0) * 2.0 / 65536.0, "λ"),
                    sig(pid, "I", "O2 ancha #${n + 1} corriente", u16(d, 2) / 256.0 - 128.0, "mA"),
                )
            })
        }
        val catNames = listOf("B1S1", "B2S1", "B1S2", "B2S2")
        for (n in 0..3) add(one(0x3C + n, "Temp. catalizador ${catNames[n]}", "°C", 2) { u16(it, 0) / 10.0 - 40.0 })
        add(one(0x42, "Tensión módulo de control", "V", 2) { u16(it, 0) / 1000.0 })
        add(one(0x43, "Carga absoluta", "%", 2) { u16(it, 0) * 100.0 / 255.0 })
        add(one(0x44, "Lambda comandada", "λ", 2) { u16(it, 0) * 2.0 / 65536.0 })
        add(one(0x45, "Mariposa relativa", "%", 1) { pct(it[0]) })
        add(one(0x46, "Temperatura ambiente", "°C", 1) { it[0] - 40.0 })
        add(one(0x47, "Mariposa B", "%", 1) { pct(it[0]) })
        add(one(0x48, "Mariposa C", "%", 1) { pct(it[0]) })
        add(one(0x49, "Pedal acelerador D", "%", 1) { pct(it[0]) })
        add(one(0x4A, "Pedal acelerador E", "%", 1) { pct(it[0]) })
        add(one(0x4B, "Pedal acelerador F", "%", 1) { pct(it[0]) })
        add(one(0x4C, "Actuador mariposa comandado", "%", 1) { pct(it[0]) })
        add(one(0x4D, "Tiempo con MIL encendida", "min", 2) { u16(it, 0).toDouble() })
        add(one(0x4E, "Tiempo desde borrado", "min", 2) { u16(it, 0).toDouble() })
        add(one(0x50, "MAF máximo", "g/s", 4) { it[0] * 10.0 })
        add(enumOne(0x51, "Tipo de combustible", 1) { fuelType[it[0]] ?: "código ${it[0]}" })
        add(one(0x52, "Etanol", "%", 1) { pct(it[0]) })
        add(one(0x59, "Presión riel absoluta", "kPa", 2) { u16(it, 0) * 10.0 })
        add(one(0x5A, "Pedal relativo", "%", 1) { pct(it[0]) })
        add(one(0x5B, "Batería híbrida", "%", 1) { pct(it[0]) })
        add(one(0x5C, "Temperatura de aceite", "°C", 1) { it[0] - 40.0 })
        add(one(0x5D, "Avance de inyección", "°", 2) { u16(it, 0) / 128.0 - 210.0 })
        add(one(0x5E, "Consumo de combustible", "L/h", 2) { u16(it, 0) / 20.0 })
        add(one(0x61, "Torque demandado por conductor", "%", 1) { it[0] - 125.0 })
        add(one(0x62, "Torque real", "%", 1) { it[0] - 125.0 })
        add(one(0x63, "Torque de referencia", "Nm", 2) { u16(it, 0).toDouble() })
        add(PidInfo(0x64, "Puntos de torque", 5) { d ->
            listOf("ralentí", "punto 1", "punto 2", "punto 3", "punto 4").mapIndexed { i, n -> sig(0x64, "$i", "Torque $n", d[i] - 125.0, "%") }
        })
        add(PidInfo(0x65, "E/S auxiliares", 2) { d ->
            buildList {
                if (bit(d[0], 0)) add(Signal("65.PTO", 0x65, "PTO", if (bit(d[1], 0)) 1.0 else 0.0, "", if (bit(d[1], 0)) "activa" else "inactiva"))
                if (bit(d[0], 1)) add(Signal("65.AT", 0x65, "Caja automática", if (bit(d[1], 1)) 1.0 else 0.0, "", if (bit(d[1], 1)) "en marcha" else "P/N"))
                if (bit(d[0], 2)) add(Signal("65.MT", 0x65, "Caja manual", if (bit(d[1], 2)) 1.0 else 0.0, "", if (bit(d[1], 2)) "embragada/marcha" else "neutro/embrague"))
                if (bit(d[0], 3)) add(Signal("65.GP", 0x65, "Testigo bujías incandescentes", if (bit(d[1], 3)) 1.0 else 0.0, "", if (bit(d[1], 3)) "encendido" else "apagado"))
            }
        })
        add(PidInfo(0x66, "Sensor MAF A/B", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x66, "A", "MAF sensor A", u16(d, 1) / 32.0, "g/s"))
                if (bit(d[0], 1)) add(sig(0x66, "B", "MAF sensor B", u16(d, 3) / 32.0, "g/s"))
            }
        })
        add(PidInfo(0x67, "Sensores de refrigerante", 3) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x67, "1", "Refrigerante sensor 1", d[1] - 40.0, "°C"))
                if (bit(d[0], 1)) add(sig(0x67, "2", "Refrigerante sensor 2", d[2] - 40.0, "°C"))
            }
        })
        add(PidInfo(0x68, "Sensores de admisión", 3) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x68, "1", "IAT banco 1 sensor 1", d[1] - 40.0, "°C"))
                if (bit(d[0], 1)) add(sig(0x68, "2", "IAT banco 1 sensor 2", d[2] - 40.0, "°C"))
            }
        })
        add(PidInfo(0x69, "EGR comandada y real", 7) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x69, "CA", "EGR A comandada", pct(d[1]), "%"))
                if (bit(d[0], 1)) add(sig(0x69, "RA", "EGR A real", pct(d[2]), "%"))
                if (bit(d[0], 2)) add(sig(0x69, "EA", "EGR A error", trim(d[3]), "%"))
                if (bit(d[0], 3)) add(sig(0x69, "CB", "EGR B comandada", pct(d[4]), "%"))
                if (bit(d[0], 4)) add(sig(0x69, "RB", "EGR B real", pct(d[5]), "%"))
                if (bit(d[0], 5)) add(sig(0x69, "EB", "EGR B error", trim(d[6]), "%"))
            }
        })
        add(PidInfo(0x6A, "Control de aire de admisión diésel", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x6A, "CA", "Válvula admisión A comandada", pct(d[1]), "%"))
                if (bit(d[0], 1)) add(sig(0x6A, "RA", "Válvula admisión A posición", pct(d[2]), "%"))
                if (bit(d[0], 2)) add(sig(0x6A, "CB", "Válvula admisión B comandada", pct(d[3]), "%"))
                if (bit(d[0], 3)) add(sig(0x6A, "RB", "Válvula admisión B posición", pct(d[4]), "%"))
            }
        })
        add(PidInfo(0x6B, "Temperatura de EGR", 5) { d ->
            val names = listOf("B1S1", "B1S2", "B2S1", "B2S2")
            buildList {
                for (i in 0..3) if (bit(d[0], i)) {
                    val wide = bit(d[0], i + 4)
                    add(sig(0x6B, names[i], "Temp. EGR ${names[i]}", if (wide) d[i + 1] * 4.0 - 40.0 else d[i + 1] - 40.0, "°C"))
                }
            }
        })
        add(PidInfo(0x6C, "Actuador de mariposa", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x6C, "CA", "Mariposa A comandada", pct(d[1]), "%"))
                if (bit(d[0], 1)) add(sig(0x6C, "RA", "Mariposa A relativa", pct(d[2]), "%"))
                if (bit(d[0], 2)) add(sig(0x6C, "CB", "Mariposa B comandada", pct(d[3]), "%"))
                if (bit(d[0], 3)) add(sig(0x6C, "RB", "Mariposa B relativa", pct(d[4]), "%"))
            }
        })
        add(PidInfo(0x6D, "Control de presión de combustible", 11) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x6D, "CA", "Riel A comandada", u16(d, 1) * 10.0, "kPa"))
                if (bit(d[0], 1)) add(sig(0x6D, "RA", "Riel A real", u16(d, 3) * 10.0, "kPa"))
                if (bit(d[0], 2)) add(sig(0x6D, "TA", "Temp. combustible riel A", d[5] - 40.0, "°C"))
                if (bit(d[0], 3)) add(sig(0x6D, "CB", "Riel B comandada", u16(d, 6) * 10.0, "kPa"))
                if (bit(d[0], 4)) add(sig(0x6D, "RB", "Riel B real", u16(d, 8) * 10.0, "kPa"))
                if (bit(d[0], 5)) add(sig(0x6D, "TB", "Temp. combustible riel B", d[10] - 40.0, "°C"))
            }
        })
        add(PidInfo(0x6E, "Control de presión de inyección", 9) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x6E, "CA", "Inyección A comandada", u16(d, 1) * 10.0, "kPa"))
                if (bit(d[0], 1)) add(sig(0x6E, "RA", "Inyección A real", u16(d, 3) * 10.0, "kPa"))
                if (bit(d[0], 2)) add(sig(0x6E, "CB", "Inyección B comandada", u16(d, 5) * 10.0, "kPa"))
                if (bit(d[0], 3)) add(sig(0x6E, "RB", "Inyección B real", u16(d, 7) * 10.0, "kPa"))
            }
        })
        add(PidInfo(0x6F, "Presión entrada compresor turbo", 3) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x6F, "A", "Entrada compresor A", d[1].toDouble(), "kPa"))
                if (bit(d[0], 1)) add(sig(0x6F, "B", "Entrada compresor B", d[2].toDouble(), "kPa"))
            }
        })
        add(PidInfo(0x70, "Control de presión de turbo", 10) { d ->
            val status = mapOf(0 to "sin dato", 1 to "lazo abierto", 2 to "lazo cerrado", 3 to "falla presente")
            buildList {
                if (bit(d[0], 0)) add(sig(0x70, "CA", "Turbo A comandado", u16(d, 1) * 0.03125, "kPa"))
                if (bit(d[0], 1)) add(sig(0x70, "RA", "Turbo A real", u16(d, 3) * 0.03125, "kPa"))
                if (bit(d[0], 2)) add(sig(0x70, "CB", "Turbo B comandado", u16(d, 5) * 0.03125, "kPa"))
                if (bit(d[0], 3)) add(sig(0x70, "RB", "Turbo B real", u16(d, 7) * 0.03125, "kPa"))
                if (bit(d[0], 4)) add(Signal("70.SA", 0x70, "Turbo A control", (d[9] and 3).toDouble(), "", status[d[9] and 3]))
                if (bit(d[0], 5)) add(Signal("70.SB", 0x70, "Turbo B control", ((d[9] shr 2) and 3).toDouble(), "", status[(d[9] shr 2) and 3]))
            }
        })
        add(PidInfo(0x71, "Control de turbo geométrico (VGT)", 6) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x71, "CA", "VGT A comandado", pct(d[1]), "%"))
                if (bit(d[0], 1)) add(sig(0x71, "RA", "VGT A posición", pct(d[2]), "%"))
                if (bit(d[0], 2)) add(sig(0x71, "CB", "VGT B comandado", pct(d[3]), "%"))
                if (bit(d[0], 3)) add(sig(0x71, "RB", "VGT B posición", pct(d[4]), "%"))
            }
        })
        add(PidInfo(0x72, "Control de wastegate", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x72, "CA", "Wastegate A comandada", pct(d[1]), "%"))
                if (bit(d[0], 1)) add(sig(0x72, "RA", "Wastegate A posición", pct(d[2]), "%"))
                if (bit(d[0], 2)) add(sig(0x72, "CB", "Wastegate B comandada", pct(d[3]), "%"))
                if (bit(d[0], 3)) add(sig(0x72, "RB", "Wastegate B posición", pct(d[4]), "%"))
            }
        })
        add(PidInfo(0x73, "Presión de escape", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x73, "B1", "Presión escape banco 1", u16(d, 1) * 0.01, "kPa"))
                if (bit(d[0], 1)) add(sig(0x73, "B2", "Presión escape banco 2", u16(d, 3) * 0.01, "kPa"))
            }
        })
        add(PidInfo(0x74, "RPM del turbo", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x74, "A", "Turbo A", u16(d, 1) * 10.0, "rpm"))
                if (bit(d[0], 1)) add(sig(0x74, "B", "Turbo B", u16(d, 3) * 10.0, "rpm"))
            }
        })
        for ((pid, t) in listOf(0x75 to "A", 0x76 to "B")) {
            add(PidInfo(pid, "Temperaturas turbo $t", 7) { d ->
                buildList {
                    if (bit(d[0], 0)) add(sig(pid, "CI", "Turbo $t entrada compresor", d[1] - 40.0, "°C"))
                    if (bit(d[0], 1)) add(sig(pid, "CO", "Turbo $t salida compresor", d[2] - 40.0, "°C"))
                    if (bit(d[0], 2)) add(sig(pid, "TI", "Turbo $t entrada turbina", u16(d, 3) / 10.0 - 40.0, "°C"))
                    if (bit(d[0], 3)) add(sig(pid, "TO", "Turbo $t salida turbina", u16(d, 5) / 10.0 - 40.0, "°C"))
                }
            })
        }
        add(PidInfo(0x77, "Temperatura intercooler", 5) { d ->
            val names = listOf("B1S1", "B1S2", "B2S1", "B2S2")
            buildList { for (i in 0..3) if (bit(d[0], i)) add(sig(0x77, names[i], "Intercooler ${names[i]}", d[i + 1] - 40.0, "°C")) }
        })
        for ((pid, bank) in listOf(0x78 to 1, 0x79 to 2)) {
            add(PidInfo(pid, "Temperatura de escape (EGT) banco $bank", 9) { d ->
                buildList { for (i in 0..3) if (bit(d[0], i)) add(sig(pid, "S${i + 1}", "EGT B${bank} sensor ${i + 1}", u16(d, 1 + i * 2) / 10.0 - 40.0, "°C")) }
            })
        }
        for ((pid, bank) in listOf(0x7A to 1, 0x7B to 2)) {
            add(PidInfo(pid, "Filtro de partículas (DPF) banco $bank", 7) { d ->
                buildList {
                    if (bit(d[0], 0)) add(sig(pid, "DP", "DPF B$bank presión diferencial", s16(d, 1) * 0.01, "kPa"))
                    if (bit(d[0], 1)) add(sig(pid, "IN", "DPF B$bank presión entrada", u16(d, 3) * 0.01, "kPa"))
                    if (bit(d[0], 2)) add(sig(pid, "OUT", "DPF B$bank presión salida", u16(d, 5) * 0.01, "kPa"))
                }
            })
        }
        add(PidInfo(0x7C, "Temperatura del DPF", 9) { d ->
            val names = listOf("B1 entrada", "B1 salida", "B2 entrada", "B2 salida")
            val keys = listOf("B1I", "B1O", "B2I", "B2O")
            buildList { for (i in 0..3) if (bit(d[0], i)) add(sig(0x7C, keys[i], "DPF ${names[i]}", u16(d, 1 + i * 2) / 10.0 - 40.0, "°C")) }
        })
        add(PidInfo(0x7F, "Tiempo de motor", 13) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x7F, "TOT", "Motor en marcha total", u32(d, 1).toDouble(), "s"))
                if (bit(d[0], 1)) add(sig(0x7F, "IDLE", "Motor en ralentí total", u32(d, 5).toDouble(), "s"))
                if (bit(d[0], 2)) add(sig(0x7F, "PTO", "Motor con PTO total", u32(d, 9).toDouble(), "s"))
            }
        })
        add(PidInfo(0x83, "Sensor NOx", 9) { d ->
            val names = listOf("B1S1", "B1S2", "B2S1", "B2S2")
            buildList {
                for (i in 0..3) if (bit(d[0], i)) {
                    val raw = u16(d, 1 + i * 2)
                    add(sig(0x83, names[i], "NOx ${names[i]}", if (raw == 0xFFFF) null else raw.toDouble(), "ppm"))
                }
            }
        })
        add(one(0x84, "Temperatura superficie colector", "°C", 1) { it[0] - 40.0 })
        add(PidInfo(0x87, "Presión colector (alta resolución)", 5) { d ->
            buildList {
                if (bit(d[0], 0)) add(sig(0x87, "A", "MAP sensor A", u16(d, 1) * 0.03125, "kPa"))
                if (bit(d[0], 1)) add(sig(0x87, "B", "MAP sensor B", u16(d, 3) * 0.03125, "kPa"))
            }
        })
        add(PidInfo(0x8B, "Postratamiento diésel (regeneración DPF)", 7) { d ->
            buildList {
                if (bit(d[0], 0)) add(Signal("8B.REGEN", 0x8B, "Regeneración DPF", if (bit(d[1], 0)) 1.0 else 0.0, "", if (bit(d[1], 0)) "EN CURSO" else "no"))
                if (bit(d[0], 1)) add(Signal("8B.TYPE", 0x8B, "Tipo de regeneración", if (bit(d[1], 1)) 1.0 else 0.0, "", if (bit(d[1], 1)) "activa" else "pasiva"))
                if (bit(d[0], 2)) add(Signal("8B.NOXR", 0x8B, "Regeneración NOx", if (bit(d[1], 2)) 1.0 else 0.0, "", if (bit(d[1], 2)) "en curso" else "no"))
                if (bit(d[0], 4)) add(sig(0x8B, "TRIG", "Carga hacia próxima regeneración", pct(d[2]), "%"))
                if (bit(d[0], 5)) add(sig(0x8B, "AVGT", "Tiempo medio entre regeneraciones", u16(d, 3).toDouble(), "min"))
                if (bit(d[0], 6)) add(sig(0x8B, "AVGD", "Distancia media entre regeneraciones", u16(d, 5).toDouble(), "km"))
            }
        })
        add(one(0x8D, "Mariposa G", "%", 1) { pct(it[0]) })
        add(PidInfo(0x8E, "Torque de fricción del motor", 1) { d -> listOf(Signal("8E", 0x8E, "Torque de fricción del motor", if (d[0] == 0xFF) null else d[0] - 125.0, "%")) })
        add(one(0x9E, "Flujo de escape", "kg/h", 2) { u16(it, 0) * 0.2 })
        add(one(0xA2, "Combustible por cilindro", "mg/carrera", 2) { u16(it, 0) / 32.0 })
        add(one(0xA6, "Odómetro", "km", 4) { u32(it, 0) / 10.0 })
    }

    private val byPid: Map<Int, PidInfo> = entries.associateBy { it.pid }

    /** Bitmaps y PIDs cuyo contenido ya decodifica otro módulo (Readiness, freeze). */
    private val sizeOnly: Map<Int, Int> = mapOf(0x01 to 4, 0x02 to 2, 0x41 to 4, 0x1D to 1, 0x4F to 4, 0x5F to 1, 0x7D to 1, 0x7E to 1, 0x85 to 10, 0x86 to 5, 0x88 to 13, 0x8C to 17, 0x8F to 7, 0x90 to 3, 0x91 to 5, 0x92 to 2, 0x93 to 3)

    fun info(pid: Int): PidInfo? = byPid[pid]

    fun name(pid: Int): String = when {
        isBitmap(pid) -> "PIDs soportados %02X".format(pid)
        pid == 0x01 -> "Estado de monitores (desde borrado)"
        pid == 0x41 -> "Estado de monitores (este ciclo)"
        else -> byPid[pid]?.name ?: "PID %02X".format(pid)
    }

    fun isBitmap(pid: Int) = pid % 0x20 == 0

    /** Bytes de datos esperados o null si no se conoce (no se puede usar en multi-PID). */
    fun size(pid: Int): Int? = if (isBitmap(pid)) 4 else byPid[pid]?.size ?: sizeOnly[pid]

    fun decode(pid: Int, data: List<Int>): List<Signal> {
        val info = byPid[pid] ?: return emptyList()
        if (data.size < info.size) return emptyList()
        return runCatching { info.decode(data.take(info.size)) }.getOrDefault(emptyList())
    }

    /** Separa una respuesta `41 PIDa datos PIDb datos …` (petición multi-PID). */
    fun splitMulti(payload: List<Int>, requested: List<Int>): Map<Int, List<Int>> {
        if (payload.isEmpty() || payload[0] != 0x41) return emptyMap()
        val out = linkedMapOf<Int, List<Int>>()
        var i = 1
        while (i < payload.size) {
            val pid = payload[i]
            if (pid !in requested) break
            val n = size(pid) ?: break
            if (i + 1 + n > payload.size) break
            out[pid] = payload.subList(i + 1, i + 1 + n).toList()
            i += 1 + n
        }
        return out
    }

    fun allKnown(): List<Int> = entries.map { it.pid }
}
