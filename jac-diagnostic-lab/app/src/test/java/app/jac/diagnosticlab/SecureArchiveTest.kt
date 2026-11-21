package app.jac.diagnosticlab

import app.jac.diagnosticlab.evidence.ReportVault
import app.jac.diagnosticlab.evidence.SecureArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecureArchiveTest {
    private val files = mapOf("a.pdf" to "PDF-bytes".toByteArray(), "b.json" to "{\"x\":1}".toByteArray())
    private val fast = 10_000

    @Test fun roundTripRestoresFilesAndManifest() {
        val sealed = SecureArchive.seal(files, "clave-segura-1".toCharArray(), iterations = fast)
        val open = SecureArchive.open(sealed, "clave-segura-1".toCharArray())
        assertArrayEquals(files["a.pdf"], open["a.pdf"])
        assertTrue(String(open["MANIFEST.sha256.txt"]!!).contains("a.pdf"))
    }

    @Test fun ciphertextDoesNotLeakPlaintext() {
        val sealed = SecureArchive.seal(files, "clave-segura-1".toCharArray(), iterations = fast)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("PDF-bytes"))
    }

    @Test fun wrongPasswordIsRejected() {
        val sealed = SecureArchive.seal(files, "clave-segura-1".toCharArray(), iterations = fast)
        try { SecureArchive.open(sealed, "otra-clave-xx".toCharArray()); fail() } catch (_: SecureArchive.BadPassword) {}
    }

    @Test fun tamperedHeaderOrBodyIsRejected() {
        val sealed = SecureArchive.seal(files, "clave-segura-1".toCharArray(), iterations = fast)
        for (i in listOf(9, 20, sealed.size - 3)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try { SecureArchive.open(bad, "clave-segura-1".toCharArray()); fail("offset $i") } catch (_: SecureArchive.BadPassword) {}
        }
    }

    @Test fun shortPasswordRefused() {
        try { SecureArchive.seal(files, "corta".toCharArray(), iterations = fast); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun twoSealsDiffer() {
        val a = SecureArchive.seal(files, "clave-segura-1".toCharArray(), iterations = fast)
        val b = SecureArchive.seal(files, "clave-segura-1".toCharArray(), iterations = fast)
        assertFalse(a.contentEquals(b))
    }

    @Test fun vaultSavesVerifiesAndDetectsTamper() {
        val root = Files.createTempDirectory("vault").toFile()
        val v = ReportVault(root)
        v.save("2026-11-21T10-00", files)
        assertEquals(listOf("2026-11-21T10-00"), v.sessions())
        assertTrue(v.verify("2026-11-21T10-00").isEmpty())
        File(root, "2026-11-21T10-00/a.pdf").writeBytes("alterado".toByteArray())
        assertEquals(listOf("a.pdf"), v.verify("2026-11-21T10-00"))
        root.deleteRecursively()
    }

    @Test fun vaultRejectsPathTraversal() {
        val root = Files.createTempDirectory("vault").toFile()
        try { ReportVault(root).save("s", mapOf("../x" to ByteArray(1))); fail() } catch (_: IllegalArgumentException) {}
        root.deleteRecursively()
    }
}
