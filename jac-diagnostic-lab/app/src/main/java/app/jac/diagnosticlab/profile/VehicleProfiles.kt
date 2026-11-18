package app.jac.diagnosticlab.profile

/**
 * SPEC-031 / ADR-002. Huella del vehículo y perfil JAC T8 con procedencia.
 *
 * El perfil de ECU se resuelve solo con un identificador que una fuente concreta documenta y que
 * aparece en lo observado (Mode 09, etiqueta escrita por el usuario). El nombre comercial no basta:
 * la misma «T8 2.0 CTI» figura con Bosch EDC17C81 y con Delphi DCM6.2/6.2AP en fuentes públicas.
 */

enum class EvidenceLevel {
    OEM_EXACT_VARIANT,
    OEM_OTHER_VARIANT,
    ACADEMIC_VALIDATED,
    VENDOR_CATALOG,
    COMMUNITY_REPRODUCED,
    STANDARD_PUBLIC,
    UNVERIFIED,
}

/** Una entrada de `research/source-manifest.json`. El id es el mismo en ambos lados. */
data class SourceRef(
    val id: String,
    val title: String,
    val url: String,
    val retrievedAt: String,
    val sha256: String?,
    val page: String?,
    val level: EvidenceLevel,
    val appliesTo: String,
)

enum class FieldOrigin { DECLARED, VIN_ISO3779, ECU_MODE09, ECU_LABEL_TYPED, PROTOCOL_OBSERVED }

data class EcuCandidate(
    val id: String,
    val supplier: String,
    val family: String,
    val engineScope: String,
    /** Identificadores tal como los escribe la fuente. Se comparan sin puntos/espacios/guiones. */
    val identifiers: List<String>,
    val sourceIds: List<String>,
    val note: String,
)

enum class SignalEncoding { J1979_MODE01, UNKNOWN_ENCODING }

data class ProfileSignal(
    val key: String,
    val name: String,
    val unit: String?,
    val service: Int?,
    val pid: Int?,
    /** null = genérico, válido para cualquier ECU. Si no, id de [EcuCandidate] o "UNKNOWN". */
    val ecuScope: String?,
    val encoding: SignalEncoding,
    val formula: String?,
    val readAllowlisted: Boolean,
    val sourceIds: List<String>,
    val note: String,
)

data class ProfileCatalog(
    val version: String,
    val decoderVersion: String,
    val sources: List<SourceRef>,
    val candidates: List<EcuCandidate>,
    val signals: List<ProfileSignal>,
) {
    fun source(id: String): SourceRef? = sources.firstOrNull { it.id == id }
}

/* ─────────────────────────────── Huella ─────────────────────────────── */

data class Traced<T>(val value: T, val origin: FieldOrigin, val ecu: String? = null)

data class VehicleFingerprint(
    val declaredModel: Traced<String>?,
    val declaredYear: Traced<Int>?,
    /** Solo el año decodificado; el VIN completo no se guarda aquí. */
    val vinYear: Traced<Int>?,
    val calibrationIds: List<Traced<String>>,
    val ecuName: Traced<String>?,
    val ecuLabel: Traced<String>?,
    val protocolNumber: Traced<String>?,
    val responseHeaders: List<Traced<String>>,
) {
    companion object {
        fun from(
            declaredModel: String?,
            declaredYear: String?,
            vin: String?,
            /** (cabecera de ECU o null, CALID) — R11: no se mezclan ECUs. */
            calibrationIds: List<Pair<String?, String>>,
            ecuName: String?,
            ecuLabel: String?,
            protocolNumber: String?,
            responseHeaders: Collection<String>,
        ) = VehicleFingerprint(
            declaredModel = declaredModel?.trim()?.ifBlank { null }?.let { Traced(it, FieldOrigin.DECLARED) },
            declaredYear = declaredYear?.trim()?.toIntOrNull()?.let { Traced(it, FieldOrigin.DECLARED) },
            vinYear = vin?.let { VinYear.decode(it) }?.let { Traced(it, FieldOrigin.VIN_ISO3779) },
            calibrationIds = calibrationIds.filter { it.second.isNotBlank() }.map { (ecu, id) -> Traced(id.trim(), FieldOrigin.ECU_MODE09, ecu) },
            ecuName = ecuName?.trim()?.ifBlank { null }?.let { Traced(it, FieldOrigin.ECU_MODE09) },
            ecuLabel = ecuLabel?.trim()?.ifBlank { null }?.let { Traced(it, FieldOrigin.ECU_LABEL_TYPED) },
            protocolNumber = protocolNumber?.trim()?.ifBlank { null }?.let { Traced(it, FieldOrigin.PROTOCOL_OBSERVED) },
            responseHeaders = responseHeaders.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().sorted()
                .map { Traced(it, FieldOrigin.PROTOCOL_OBSERVED) },
        )
    }
}

/**
 * ISO 3779: el carácter 10 codifica el año modelo en ciclos de 30 años (sin I, O, Q, U, Z, 0).
 * Se usa el ciclo 2010–2039, el único compatible con una JAC T8.
 */
object VinYear {
    private const val CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun decode(vin: String): Int? {
        val clean = vin.trim().uppercase()
        if (clean.length != 17) return null
        val index = CODES.indexOf(clean[9])
        return if (index < 0) null else 2010 + index
    }
}

/* ─────────────────────────────── Resolución ─────────────────────────────── */

enum class ResolutionStatus { IDENTIFIED, SUPPLIER_ONLY, AMBIGUOUS, PARTIAL }

enum class YearCheck { MATCH, MISMATCH, DECLARED_ONLY, UNKNOWN }

data class CandidateMatch(
    val candidate: EcuCandidate,
    val identifier: String,
    val origin: FieldOrigin,
    val observedText: String,
    val ecu: String? = null,
)

data class ProfileResolution(
    val catalogVersion: String,
    val status: ResolutionStatus,
    val matches: List<CandidateMatch>,
    val yearCheck: YearCheck,
    val summary: String,
) {
    val matchedCandidateIds: Set<String> get() = matches.map { it.candidate.id }.toSet()
    val suppliers: Set<String> get() = matches.map { it.candidate.supplier }.toSet()
}

enum class CoverageStatus { SUPPORTED_VERIFIED, SUPPORTED_UNVALIDATED, UNSUPPORTED, UNKNOWN }

data class SignalCoverage(val signal: ProfileSignal, val status: CoverageStatus, val reason: String)

object ProfileResolver {

    fun resolve(fp: VehicleFingerprint, catalog: ProfileCatalog = JacT8Catalog.v1): ProfileResolution {
        val observed = buildList {
            fp.ecuLabel?.let { add(it) }
            addAll(fp.calibrationIds)
            fp.ecuName?.let { add(it) }
        }
        val matches = observed.flatMap { text ->
            val hits = catalog.candidates.flatMap { candidate ->
                candidate.identifiers.flatMap { id -> spans(text.value, id).map { Triple(candidate, id, it) } }
            }
            // R6: si un identificador más largo contiene a otro en el mismo texto, solo cuenta el largo.
            hits.filter { (_, _, span) ->
                hits.none { (_, _, other) -> other != span && other.first <= span.first && other.last >= span.last }
            }.map { (candidate, id, _) -> CandidateMatch(candidate, id, text.origin, text.value, text.ecu) }
        }.distinctBy { it.candidate.id to it.origin }

        val suppliers = matches.map { it.candidate.supplier }.toSet()
        val candidateIds = matches.map { it.candidate.id }.toSet()
        val status = when {
            matches.isEmpty() -> ResolutionStatus.PARTIAL
            candidateIds.size == 1 -> ResolutionStatus.IDENTIFIED
            suppliers.size == 1 -> ResolutionStatus.SUPPLIER_ONLY
            else -> ResolutionStatus.AMBIGUOUS
        }
        val yearCheck = when {
            fp.vinYear != null && fp.declaredYear != null ->
                if (fp.vinYear.value == fp.declaredYear.value) YearCheck.MATCH else YearCheck.MISMATCH
            fp.declaredYear != null -> YearCheck.DECLARED_ONLY
            else -> YearCheck.UNKNOWN
        }
        val summary = when (status) {
            ResolutionStatus.PARTIAL ->
                "Perfil parcial: solo lecturas genéricas J1979. Ningún identificador de ECU documentado apareció en Mode 09 ni en la etiqueta."
            ResolutionStatus.IDENTIFIED -> {
                val m = matches.first()
                "ECU ${m.candidate.supplier} ${m.candidate.family}: «${m.identifier}» apareció en ${originText(m.origin)}. " +
                    "Fuente: ${m.candidate.sourceIds.joinToString()}."
            }
            ResolutionStatus.SUPPLIER_ONLY ->
                "Proveedor ${suppliers.first()} coincide, pero apuntan a variantes distintas (" +
                    matches.joinToString { it.candidate.family } + "). No se elige modelo de ECU."
            ResolutionStatus.AMBIGUOUS ->
                "Identificadores de proveedores distintos (${suppliers.sorted().joinToString(" y ")}). No se elige perfil de ECU; revise la etiqueta física."
        }
        return ProfileResolution(catalog.version, status, matches, yearCheck, summary)
    }

    /**
     * R7: una señal se consulta solo si su codificación es conocida, está en lista de lectura,
     * todas sus fuentes existen y ninguna es UNVERIFIED, y su ECU coincide con el perfil resuelto.
     */
    fun activeSignals(
        resolution: ProfileResolution,
        catalog: ProfileCatalog = JacT8Catalog.v1,
        signals: List<ProfileSignal> = catalog.signals,
    ): List<ProfileSignal> = signals.filter { s ->
        if (s.encoding == SignalEncoding.UNKNOWN_ENCODING || !s.readAllowlisted) return@filter false
        val sources = s.sourceIds.map { catalog.source(it) }
        if (sources.isEmpty() || sources.any { it == null || it.level == EvidenceLevel.UNVERIFIED }) return@filter false
        when (s.ecuScope) {
            null -> true
            else -> resolution.status == ResolutionStatus.IDENTIFIED && s.ecuScope in resolution.matchedCandidateIds
        }
    }

    /** R8: compara el perfil con lo que declaró y respondió la ECU en esta sesión. */
    fun coverage(
        bitmapRead: Boolean,
        supported: Set<Int>,
        validPids: Set<Int>,
        catalog: ProfileCatalog = JacT8Catalog.v1,
    ): List<SignalCoverage> = catalog.signals.map { s ->
        when {
            s.encoding == SignalEncoding.UNKNOWN_ENCODING || s.pid == null ->
                SignalCoverage(s, CoverageStatus.UNKNOWN, "Codificación no publicada por la fuente. No se consulta.")
            !bitmapRead -> SignalCoverage(s, CoverageStatus.UNKNOWN, "Mapa Mode 01 aún no leído.")
            s.pid !in supported -> SignalCoverage(s, CoverageStatus.UNSUPPORTED, "La ECU no la declara en el bitmap.")
            s.pid in validPids -> SignalCoverage(s, CoverageStatus.SUPPORTED_VERIFIED, "Declarada y con valor válido en esta sesión.")
            else -> SignalCoverage(s, CoverageStatus.SUPPORTED_UNVALIDATED, "Declarada; aún sin valor válido.")
        }
    }

    /**
     * Coincidencia insensible a mayúsculas y a separadores (. - _ espacio) dentro del identificador,
     * con borde alfanumérico a ambos lados: «EDC17C810» no contiene «EDC17C81».
     */
    fun containsIdentifier(haystack: String, identifier: String): Boolean = spans(haystack, identifier).isNotEmpty()

    private fun spans(haystack: String, identifier: String): List<IntRange> {
        val chars = identifier.uppercase().filter { it.isLetterOrDigit() }
        if (chars.isEmpty()) return emptyList()
        val body = chars.map { Regex.escape(it.toString()) }.joinToString("[._\\- ]?")
        return Regex("(?<![A-Z0-9])$body(?![A-Z0-9])").findAll(haystack.uppercase()).map { it.range }.toList()
    }

    fun originText(origin: FieldOrigin) = when (origin) {
        FieldOrigin.DECLARED -> "lo declarado por el dueño"
        FieldOrigin.VIN_ISO3779 -> "el VIN"
        FieldOrigin.ECU_MODE09 -> "Mode 09 de la ECU"
        FieldOrigin.ECU_LABEL_TYPED -> "la etiqueta de la ECU escrita por el usuario"
        FieldOrigin.PROTOCOL_OBSERVED -> "el protocolo observado"
    }
}

/* ─────────────────────────────── Catálogo v1 ─────────────────────────────── */

object JacT8Catalog {
    private const val RETRIEVED = "2026-11-18"

    private fun mode01(pid: Int, key: String, name: String, unit: String, formula: String, sources: List<String>, note: String = "") =
        ProfileSignal(key, name, unit, 0x01, pid, null, SignalEncoding.J1979_MODE01, formula, true, sources, note)

    private fun oem(key: String, name: String, unit: String?) =
        ProfileSignal(
            key, name, unit, null, null, "UNKNOWN", SignalEncoding.UNKNOWN_ENCODING, null, false, listOf("S12"),
            "Listada por un tercero para «T8 2.0 CTI HFC4DB2-2E». Sin request ni fórmula publicadas; licencia personal. No se consulta.",
        )

    val v1 = ProfileCatalog(
        version = "jac-t8-catalog-v1",
        decoderVersion = "j1979-decoders-1.9.0",
        sources = listOf(
            SourceRef("J1979", "SAE J1979 / ISO 15031-5, servicios y PIDs de emisiones", "https://saemobilus.sae.org/standards/j1979", RETRIEVED, null, null, EvidenceLevel.STANDARD_PUBLIC, "Cualquier vehículo OBD-II/EOBD"),
            SourceRef("S03", "JAC Chile — ficha Pickup T8", "https://www.jacautoschile.cl/modelos/pickup/t8/", RETRIEVED, "1e4a2733476cb454723f068c9b24616a24e2203cc19cee30135cc664ddf7ad14", null, EvidenceLevel.OEM_OTHER_VARIANT, "Gama T8 Chile 2024 (2.0 CTI 147 HP / 360 Nm). No identifica la unidad 2023."),
            SourceRef("S09", "Tesis UPS 2024 TTS1919 — panel CAN JAC T8 (LabVIEW, NI 9862)", "https://dspace.ups.edu.ec/bitstream/123456789/28396/1/TTS1919.pdf", RETRIEVED, "c9ef2bdcadf004cf2a8318d6df77231c759ffc6aadb645e61f2146f0862fad68", "17, 28, 47, 54", EvidenceLevel.ACADEMIC_VALIDATED, "Una JAC T8 diésel en Quito, EURO 3. Otra unidad y otra norma."),
            SourceRef("S11", "Alientech — BOOT DCM6.2AP en JAC T8 Pro 2.0D", "https://www.alientech-tools.com/en/upgrade-2-27/", RETRIEVED, "8a3c5958dcb70e6132aba208a942861bba9d357d0b0e955c22642e39171cc495", null, EvidenceLevel.VENDOR_CATALOG, "T8 Pro 4x4 2.0 diésel desde 2023. No T8 estándar."),
            SourceRef("S12", "OBD Lens — perfil T8 2.0 CTI (HFC4DB2-2E)", "https://obdlens.com/vehicles/profiles/legacy-1153-jac-5a69bb1e45/", RETRIEVED, "6526b3f2463305ab02aca36c7ab3d1d2196847802d1c09bb62209b0dc3bfcb04", null, EvidenceLevel.COMMUNITY_REPRODUCED, "Años no especificados; «library metadata is not a road-test claim»."),
            SourceRef("S18", "XTOOL — lista de funciones JAC V21.18 (CN)", "https://www.xtooltech.com/official/pack_pdf/PD_JAC/PD_JAC_V21_18_CN.pdf", RETRIEVED, "b46d51de13a973037e66bca1a3498f7823c9873a08be5a052cd23b57fb0c1b42", "1–2", EvidenceLevel.VENDOR_CATALOG, "Familias de ECU diésel JAC por motor; no por VIN."),
            SourceRef("S19", "JAC China — ficha 江淮T8", "https://pickup.jac.com.cn/jhT8/", RETRIEVED, "5e3fb625eb8f672a6eb4e8dcd751424dbff5f8c2f20159332115b4ccfdcddefd", null, EvidenceLevel.OEM_OTHER_VARIANT, "Mercado China: HFC4DB2-2E2, 110 kW, 400 Nm, 国VIb."),
            SourceRef("S22", "ecutools BitBox — lista de ECUs (Delphi DCM6.2AP JAC; DCM6.2AP-6D SsangYong FID 89)", "https://ecutools.eu/chip-tuning/bitbox/", RETRIEVED, "a9bae147299a5aa73a7a483f388147a283507e886aa7df29b6116f806f106e1b", null, EvidenceLevel.VENDOR_CATALOG, "DCM6.2AP-6D figura para SsangYong, no para JAC."),
            SourceRef("S32", "Dimsport — aplicaciones JAC T8", "https://www.dimsport.it/en/applications-list/unique/auto/jac/t8-2/", RETRIEVED, "94a1efae69543cad7f2a7507021ee9bdd5b40a8a5536b91dfd6ced34250733a2", null, EvidenceLevel.VENDOR_CATALOG, "T8 2.0 TD 100 kW DCM6.2AP; T8 PRO 2.0 TD 107 kW MD1CS089; T8 2.0 Turbo 130 kW Delphi MT80."),
            SourceRef("S33", "AutoTuner — blog Delphi DCM3.7AP / DCM6.2AP", "https://www.autotuner.com/blogs/news/deplhi-dcm3-7ap-dcm6-2ap", RETRIEVED, "3113351ebff98cda799d55f1345aa525349f784cf6d394b5880988faa34eea74", null, EvidenceLevel.VENDOR_CATALOG, "JAC T8 (2020+) 2.0 diésel con DCM6.2AP (SPC5674)."),
            SourceRef("S34", "iMotriz — ECU JAC JT8 1026400FD020B054", "https://www.imotriz.com/producto/603dbb1046412a58df2e8faebc11f735/1026400fd020b054/ecu-jac-jt8", RETRIEVED, "513daf2a35c73b1afa9203233374de4e4254b4cdba882b41bde6e8d6b587e543", null, EvidenceLevel.VENDOR_CATALOG, "Repuesto; no nombra proveedor ni año."),
            SourceRef("S35", "Inversiones Diesel — Computadora JAC T6 T8 Delphi 1026400FD020", "https://www.invdieselpzo.com/product-page/computadora-jac-hfc1037-t6-t8-delphi-1026400fd020", RETRIEVED, "329ab3de09244ecc7a02283f00345b5804b7fd8bc36fb2f0eb018d1c80f6116e", null, EvidenceLevel.VENDOR_CATALOG, "Repuesto; Delphi; T6/T8 sin año ni variante DCM."),
            SourceRef("S36", "MDC Repuestos (Chile) — ECU computador de motor JAC T8-T6", "https://mdcrepuestos.cl/?s=1026400FD020&post_type=product", RETRIEVED, "d23fbedda748ef5b7d10d9d2790dc053a126cd491dfbc48b3027c9e1678ca18f", null, EvidenceLevel.VENDOR_CATALOG, "«JAC Delphi DCM6.2 AP, código OEM 1026400FD020B-007». Repuesto en Chile; no prueba la unidad."),
            SourceRef("S38", "Desarmaduría IPAR — 1026400FD020 T8 2023 HFC4DB2 (aportada por el usuario)", "https://www.desarmaduriaipar.cl/", RETRIEVED, null, null, EvidenceLevel.UNVERIFIED, "La búsqueda en el sitio no mostró la pieza; no verificada."),
            SourceRef("S21", "ecufiles.io — tipos Delphi DCM6.2 (fila JAC T8 2.0TDI 2024)", "https://ecufiles.io/ecu-types/delphi-dcm6-2", RETRIEVED, "58db070f9f13ad61045fcd03d9b8df3b3ccaae8edb62cf02449a4324a74b4518", null, EvidenceLevel.VENDOR_CATALOG, "T8 2.0TDI 2024 137 hp / 320 Nm. Variante distinta de la ficha Chile."),
        ),
        candidates = listOf(
            EcuCandidate(
                "BOSCH_EDC17C81", "Bosch", "EDC17C81", "HFC4DE / HFC4DA / HFC4DB (国四/国五, con o sin DPF)",
                listOf("EDC17C81"), listOf("S18"),
                "Catálogo de herramienta: la familia de motor usa esta ECU en alguna variante.",
            ),
            EcuCandidate(
                "BOSCH_MD1CS089", "Bosch", "MD1CS089", "JAC T8 PRO 2.0L 16V TD 107 kW / 144 HP (Dimsport)",
                listOf("MD1CS089"), listOf("S32"),
                "Una fuente de herramienta, solo para T8 PRO. No se mezcla con EDC17C81.",
            ),
            EcuCandidate(
                "DELPHI_DCM62AP", "Delphi", "DCM6.2AP", "HFC4DB common-rail 国五 (XTOOL); T8 2.0 TD 100 kW (Dimsport); T8 2020+ (AutoTuner); T8 Pro 4x4 desde 2023 (Alientech)",
                listOf("DCM6.2AP", "1026400FD020B-007"), listOf("S18", "S11", "S32", "S33", "S36"),
                "Cinco fuentes de herramienta/repuesto; MDC (Chile) asocia el código OEM 1026400FD020B-007. Ninguna es la ficha de esta unidad.",
            ),
            EcuCandidate(
                "DELPHI_DCM62AP_6D", "Delphi", "DCM6.2AP-6D", "Documentada para SsangYong (BitBox FID 89); para JAC T8 solo aportada por el usuario",
                listOf("DCM6.2AP-6D"), listOf("S22"),
                "Variante distinta de DCM6.2AP. Aplicabilidad a JAC sin fuente descargada.",
            ),
            EcuCandidate(
                "DELPHI_DCM62_PN", "Delphi", "DCM6.2 (JAC P/N 1026400FD020, variante no determinada)", "JAC T6/T8; T8 2.0TDI 2024 137 hp (ecufiles)",
                listOf("1026400FD020", "1026400FD020B054", "1MJACLSA_A010D11"), listOf("S21", "S35", "S34"),
                "Número de pieza JAC y software listados por catálogo de tuning y repuestos. No dice si es AP o AP-6D.",
            ),
            EcuCandidate(
                "DELPHI_MT80", "Delphi", "MT80", "JAC T8 2.0L Turbo gasolina 130 kW (Dimsport)",
                listOf("MT80"), listOf("S32"),
                "Motor de gasolina. Si aparece en un T8 diésel, revisar la etiqueta.",
            ),
        ),
        signals = listOf(
            mode01(0x04, "01-04", "Calculated Load Value", "%", "A*100/255", listOf("J1979")),
            mode01(0x05, "01-05", "Engine Coolant Temperature", "°C", "A-40", listOf("J1979", "S09")),
            mode01(0x0B, "01-0B", "Intake Manifold Absolute Pressure", "kPa", "A", listOf("J1979", "S09")),
            mode01(0x0C, "01-0C", "Engine RPM", "rpm", "(256*A+B)/4", listOf("J1979", "S09")),
            mode01(0x0D, "01-0D", "Vehicle Speed", "km/h", "A", listOf("J1979", "S09")),
            mode01(0x0F, "01-0F", "Intake Air Temperature", "°C", "A-40", listOf("J1979", "S09"),
                "S09 p.47: con perfil de norma equivocado (EURO 5 sobre EURO 3) un escáner mostró IAT fuera de rango."),
            mode01(0x10, "01-10", "MAF Air Flow Rate", "g/s", "(256*A+B)/100", listOf("J1979", "S09"),
                "S09 p.17 escribe /32; J1979 define /100. Se usa J1979. S09 p.47 reporta MAF inusual con perfil equivocado."),
            mode01(0x11, "01-11", "Absolute Throttle Position", "%", "A*100/255", listOf("J1979"), "Mariposa, no pedal (REQ-DIAG-001)."),
            mode01(0x23, "01-23", "Fuel Rail Gauge Pressure", "kPa", "10*(256*A+B)", listOf("J1979"),
                "Diésel common-rail. S09 p.48 cita 26 300 kPa en ralentí leído con escáner; no es un rango de esta unidad."),
            mode01(0x33, "01-33", "Absolute Barometric Pressure", "kPa", "A", listOf("J1979")),
            mode01(0x42, "01-42", "Control Module Voltage", "V", "(256*A+B)/1000", listOf("J1979")),
            mode01(0x49, "01-49", "Accelerator Pedal Position D", "%", "A*100/255", listOf("J1979"),
                "P2299: comparar con freno solo con escáner de marca; la diferencia D/E no es falla por sí sola."),
            mode01(0x4A, "01-4A", "Accelerator Pedal Position E", "%", "A*100/255", listOf("J1979")),
            oem("OEM-DPF-SOOT", "[ECM] DPF Soot mass", "g"),
            oem("OEM-DPF-DP", "[ECM] DPF pressure drop", "hPa"),
            oem("OEM-DPF-REGEN-N", "[ECM] Number of DPF regenerations", null),
            oem("OEM-FUEL-TEMP", "[ECM] Fuel temperature", "°C"),
            oem("OEM-BOOST-SP", "[ECM] Boost pressure setpoint", "hPa"),
            oem("OEM-INJ-QTY", "[ECM] Actual fuel injection quantity", "mg/stroke"),
            oem("OEM-GLOW-V", "[ECM] Glow plug voltage", "mV"),
            oem("OEM-WSS-FL", "[ESP] Front left wheel speed", "km/h"),
        ),
    )

    /** R9: forma serializable estable del catálogo (la escribe el repositorio en jac-t8-profiles/). */
    fun toMap(c: ProfileCatalog = v1): Map<String, Any?> = linkedMapOf(
        "version" to c.version,
        "decoder_version" to c.decoderVersion,
        "generated_from" to "app/src/main/java/app/jac/diagnosticlab/profile/VehicleProfiles.kt",
        "rule" to "Un perfil de ECU se resuelve solo con un identificador documentado observado en Mode 09 o en la etiqueta. Ver ADR-002.",
        "sources" to c.sources.map {
            linkedMapOf(
                "id" to it.id, "title" to it.title, "url" to it.url, "retrieved_at" to it.retrievedAt,
                "sha256" to it.sha256, "source_page" to it.page, "level" to it.level.name, "applies_to" to it.appliesTo,
            )
        },
        "ecu_candidates" to c.candidates.map {
            linkedMapOf(
                "id" to it.id, "supplier" to it.supplier, "family" to it.family, "engine_scope" to it.engineScope,
                "identifiers" to it.identifiers, "sources" to it.sourceIds, "note" to it.note,
            )
        },
        "signals" to c.signals.map {
            linkedMapOf(
                "key" to it.key, "name" to it.name, "unit" to it.unit,
                "service" to it.service?.let { s -> "%02X".format(s) }, "pid_or_did" to it.pid?.let { p -> "%02X".format(p) },
                "ecu" to (it.ecuScope ?: "ANY"), "encoding" to it.encoding.name, "formula" to it.formula,
                "read_allowlisted" to it.readAllowlisted, "sources" to it.sourceIds, "note" to it.note,
            )
        },
    )
}
