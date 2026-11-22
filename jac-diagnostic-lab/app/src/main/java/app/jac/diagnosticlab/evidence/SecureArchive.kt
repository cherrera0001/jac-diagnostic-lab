package app.jac.diagnosticlab.evidence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exportación segura de evidencia: ZIP (con MANIFEST de SHA-256) cifrado con AES-256-GCM.
 * Clave = PBKDF2-HMAC-SHA256 (210 000 iteraciones, sal aleatoria de 16 bytes). Solo javax.crypto.
 * Formato: "JACENC1" | iter(4, BE) | sal(16) | iv(12) | texto cifrado + etiqueta GCM.
 * El encabezado entra como datos autenticados (AAD): modificarlo invalida el archivo.
 */
object SecureArchive {
    private val MAGIC = "JACENC1".toByteArray(Charsets.US_ASCII)
    const val ITERATIONS = 210_000
    const val EXTENSION = "jacenc"
    const val MIN_PASSWORD = 8

    class BadPassword(message: String) : Exception(message)

    fun manifest(files: Map<String, ByteArray>): String =
        files.entries.sortedBy { it.key }.joinToString("\n", postfix = "\n") { "${sha256Hex(it.value)}  ${it.key}" }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val all = files + ("MANIFEST.sha256.txt" to manifest(files).toByteArray(Charsets.UTF_8))
            all.forEach { (name, bytes) ->
                require(!name.contains("..") && !name.startsWith("/")) { "nombre no permitido: $name" }
                z.putNextEntry(ZipEntry(name).also { it.time = 0L }); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val res = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                require(!e.name.contains("..") && !e.name.startsWith("/")) { "entrada insegura: ${e.name}" }
                res[e.name] = z.readBytes(); e = z.nextEntry
            }
        }
        return res
    }

    private fun key(password: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iter, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun seal(files: Map<String, ByteArray>, password: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD) { "La contraseña debe tener al menos $MIN_PASSWORD caracteres." }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = MAGIC + java.nio.ByteBuffer.allocate(4).putInt(iterations).array() + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(zip(files))
    }

    fun open(sealed: ByteArray, password: CharArray): Map<String, ByteArray> {
        val hl = MAGIC.size + 4 + 16 + 12
        if (sealed.size <= hl + 16 || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BadPassword("No es un archivo .$EXTENSION válido.")
        val iter = java.nio.ByteBuffer.wrap(sealed, MAGIC.size, 4).int
        if (iter < 10_000 || iter > 5_000_000) throw BadPassword("Parámetros de clave inválidos.")
        val salt = sealed.copyOfRange(MAGIC.size + 4, MAGIC.size + 20)
        val iv = sealed.copyOfRange(MAGIC.size + 20, hl)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iter), GCMParameterSpec(128, iv))
        cipher.updateAAD(sealed.copyOfRange(0, hl))
        val plain = try { cipher.doFinal(sealed, hl, sealed.size - hl) } catch (e: Exception) { throw BadPassword("Contraseña incorrecta o archivo alterado.") }
        return unzip(plain)
    }
}

/**
 * Almacén privado de la app (`filesDir/vault/<sesión>/`): inaccesible a otras apps sin root.
 * Escritura atómica (archivo temporal + renombrado) y MANIFEST de SHA-256 que se verifica al leer.
 */
class ReportVault(private val root: File) {
    private fun dir(session: String) = File(root, session.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sin-sesion" }).also { it.mkdirs() }

    fun save(session: String, files: Map<String, ByteArray>): File {
        val d = dir(session)
        files.forEach { (name, bytes) ->
            require(!name.contains('/') && !name.contains("..")) { "nombre no permitido: $name" }
            val tmp = File(d, "$name.tmp"); tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, name))) { File(d, name).writeBytes(bytes); tmp.delete() }
        }
        val all = d.listFiles { f -> f.isFile && f.name != "MANIFEST.sha256.txt" && !f.name.endsWith(".tmp") }.orEmpty().associate { it.name to it.readBytes() }
        File(d, "MANIFEST.sha256.txt").writeText(SecureArchive.manifest(all))
        return d
    }

    fun sessions(): List<String> = root.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()

    /** Devuelve los archivos cuyo SHA-256 NO coincide con el manifiesto (vacío = íntegro). */
    fun verify(session: String): List<String> {
        val d = dir(session)
        val mf = File(d, "MANIFEST.sha256.txt").takeIf { it.exists() } ?: return listOf("MANIFEST.sha256.txt")
        return mf.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val hash = line.substringBefore("  "); val name = line.substringAfter("  ")
            val f = File(d, name)
            if (!f.exists() || sha256Hex(f.readBytes()) != hash) name else null
        }
    }

    fun load(session: String): Map<String, ByteArray> =
        dir(session).listFiles { f -> f.isFile }.orEmpty().associate { it.name to it.readBytes() }
}
