package app.jac.diagnosticlab.telemetry

import app.jac.diagnosticlab.evidence.Sample
import java.io.File

data class SessionSummary(
    val id: String,
    val startedAtEpochMs: Long,
    val measurementCount: Int,
    val path: String,
)

/**
 * Almacén local append-only (JSONL). Sin Room todavía; cumple AC-009/010.
 * Las lecturas originales no se reescriben: solo se añaden líneas.
 */
class TelemetryStore(private val root: File) {
    private val sessionsDir get() = File(root, "telemetry").apply { mkdirs() }

    fun appendMeasurement(sessionId: String, sample: Sample, latencyMs: Long? = null, band: String? = null) {
        if (sessionId.isBlank()) return
        val file = File(sessionsDir, "$sessionId.jsonl")
        val valueJson = sample.value?.toString() ?: "null"
        val unitJson = sample.unit?.let { jsonString(it) } ?: "null"
        val bandJson = band?.let { jsonString(it) } ?: "null"
        val latencyJson = latencyMs?.toString() ?: "null"
        val line = buildString {
            append("{")
            append("\"session_id\":").append(jsonString(sessionId)).append(',')
            append("\"pid\":").append(sample.pid).append(',')
            append("\"name\":").append(jsonString(sample.name)).append(',')
            append("\"value\":").append(valueJson).append(',')
            append("\"unit\":").append(unitJson).append(',')
            append("\"valid\":").append(sample.valid).append(',')
            append("\"suspect\":").append(sample.suspect).append(',')
            append("\"raw\":").append(jsonString(sample.raw)).append(',')
            append("\"timestamp_ms\":").append(sample.atEpochMs).append(',')
            append("\"latency_ms\":").append(latencyJson).append(',')
            append("\"band\":").append(bandJson).append(',')
            append("\"synthetic\":false")
            append("}")
        }
        file.appendText(line + "\n")
        touchIndex(sessionId, sample.atEpochMs)
    }

    fun measurementCount(sessionId: String): Int {
        val file = File(sessionsDir, "$sessionId.jsonl")
        if (!file.exists()) return 0
        return file.useLines { seq -> seq.count { it.isNotBlank() } }
    }

    fun listSessions(): List<SessionSummary> {
        val fromFiles = sessionsDir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") && it.name != "index.jsonl" }
            ?.map { f ->
                val id = f.name.removeSuffix(".jsonl")
                SessionSummary(id, f.lastModified(), measurementCount(id), f.absolutePath)
            }
            .orEmpty()
        return fromFiles.sortedByDescending { it.startedAtEpochMs }
    }

    private fun touchIndex(sessionId: String, atMs: Long) {
        val index = File(sessionsDir, "index.jsonl")
        val count = measurementCount(sessionId)
        val line = "{\"id\":${jsonString(sessionId)},\"started_at_ms\":$atMs,\"count\":$count}"
        index.appendText(line + "\n")
    }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
}
