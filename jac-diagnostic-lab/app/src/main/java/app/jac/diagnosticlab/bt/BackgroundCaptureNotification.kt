package app.jac.diagnosticlab.bt

import app.jac.diagnosticlab.analysis.DashboardLinkStatus

/**
 * SPEC-034 R8 — Contrato puro (JVM) para la notificación persistente del ForegroundService
 * que mantiene viva la captura OBD-II y el registro de evidencia en segundo plano.
 */
data class CaptureNotificationSpec(
    val channelId: String = CHANNEL_ID,
    val title: String,
    val text: String,
    val ongoing: Boolean = true,
) {
    init {
        require(ongoing) {
            "SPEC-034 R8: la notificación del ForegroundService de captura OBD debe ser persistente (ongoing = true)."
        }
        require(title.isNotBlank() && text.isNotBlank()) {
            "SPEC-034 R8: la notificación persistente debe informar título y estado real de la captura."
        }
    }

    companion object {
        const val CHANNEL_ID = "jac.diagnostic.session"
        const val NOTIFICATION_ID = 42
    }
}

object BackgroundCaptureNotification {
    fun defaultSpec(): CaptureNotificationSpec = CaptureNotificationSpec(
        title = "JAC Diagnostic Lab · Captura OBD-II",
        text = "Sesión de solo lectura activa en segundo plano. La captura y evidencia siguen con pantalla apagada.",
        ongoing = true,
    )

    fun build(
        linkPhase: LinkPhase,
        activeTest: String?,
        polling: Boolean,
        sampleCount: Int,
        liveStatus: DashboardLinkStatus = DashboardLinkStatus.PAUSED,
        fuelHint: String? = null,
    ): CaptureNotificationSpec {
        val title = when {
            liveStatus == DashboardLinkStatus.RECONNECTING -> "JAC Diagnostic Lab · Reconectando OBD"
            activeTest == "LIVE" -> "JAC Diagnostic Lab · En vivo (2º plano)"
            activeTest == "FAST_FAULT" -> "JAC Diagnostic Lab · Captura rápida (8 PIDs)"
            activeTest == "ROAD" -> "JAC Diagnostic Lab · Registro en ruta"
            activeTest != null -> "JAC Diagnostic Lab · Prueba $activeTest"
            polling -> "JAC Diagnostic Lab · Monitor continuo"
            linkPhase != LinkPhase.DISCONNECTED -> "JAC Diagnostic Lab · Enlace OBD activo"
            else -> "JAC Diagnostic Lab · Captura OBD-II"
        }

        val parts = buildList {
            when {
                liveStatus == DashboardLinkStatus.RECONNECTING ->
                    add("Reconectando enlace OBD-II (sesión y evidencia conservadas)")
                activeTest == "LIVE" || activeTest == "ROAD" || activeTest != null ->
                    add("Captura OBD-II y registro de evidencia activos ($sampleCount rondas)")
                polling ->
                    add("Monitor OBD-II en segundo plano ($sampleCount muestras)")
                else ->
                    add("Solo lectura activa (${linkPhase.label})")
            }
            fuelHint?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        return CaptureNotificationSpec(
            title = title,
            text = parts.joinToString(" · "),
            ongoing = true,
        )
    }
}
