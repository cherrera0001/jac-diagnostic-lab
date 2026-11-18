package app.jac.diagnosticlab

import android.content.Context
import android.os.Build

/** Versión instalada, leída del paquete (no escrita a mano): la misma en pantalla, PDF, JSON y TXT. */
object AppVersion {
    fun label(versionName: String?, versionCode: Long?): String {
        val name = versionName?.trim().orEmpty().ifBlank { "?" }
        return if (versionCode == null) "v$name" else "v$name ($versionCode)"
    }

    fun of(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        label(info.versionName, code)
    }.getOrDefault(label(null, null))
}
