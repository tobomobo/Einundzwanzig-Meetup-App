package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

/** Spec 08 §2.2 (password rules), §2.4 step 8 (file name), §2.5 step 3 (strict UTF-8). */
class BackupFilesTest {

    private val now = LocalDateTime.of(2026, 3, 7, 9, 5, 59)

    @Test
    fun `file name uses local time and the trimmed nickname`() {
        assertEquals("Backup_2026-03-07_0905_21MeetupApp_Ada.21bkp", BackupFileName.of("  Ada ", now))
    }

    @Test
    fun `empty nickname becomes Anon`() {
        assertEquals("Backup_2026-03-07_0905_21MeetupApp_Anon.21bkp", BackupFileName.of("   ", now))
    }

    @Test
    fun `every unsafe UTF-16 code unit becomes one underscore`() {
        assertEquals(
            "Backup_2026-03-07_0905_21MeetupApp_J_rg_M-x__.21bkp",
            BackupFileName.of("Jörg M-x😀", now),
        )
        assertEquals("Backup_2026-03-07_0905_21MeetupApp_a_b.21bkp", BackupFileName.of("a b", now))
    }

    @Test
    fun `password rules`() {
        assertFalse(BackupPasswordRules.longEnough("1234567"))
        assertTrue(BackupPasswordRules.longEnough("12345678"))
        // UTF-16 code units, like Dart String.length: 4 emoji = 8 units.
        assertTrue(BackupPasswordRules.longEnough("😀😀😀😀"))
        assertFalse(BackupPasswordRules.matches("", ""))
        assertTrue(BackupPasswordRules.matches("abcdefgh", "abcdefgh"))
        assertFalse(BackupPasswordRules.canSubmit(isExport = true, password = "abcdefgh", confirm = "abcdefgX"))
        assertTrue(BackupPasswordRules.canSubmit(isExport = true, password = "abcdefgh", confirm = "abcdefgh"))
        assertTrue(BackupPasswordRules.canSubmit(isExport = false, password = "x"))
        assertFalse(BackupPasswordRules.canSubmit(isExport = true, password = " abcdef ", confirm = " abcdef"))
    }

    @Test
    fun `write then read round trips the text`() = runTest {
        val out = ByteArrayOutputStream()
        BackupFiles.write(out, "enc_v2:äöü", Dispatchers.Unconfined)
        assertArrayEquals("enc_v2:äöü".toByteArray(Charsets.UTF_8), out.toByteArray())
        assertEquals("enc_v2:äöü", BackupFiles.read(ByteArrayInputStream(out.toByteArray()), Dispatchers.Unconfined))
    }

    @Test
    fun `leading BOM is dropped like Dart utf8 decode`() = runTest {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray()
        assertEquals("{}", BackupFiles.read(ByteArrayInputStream(bytes), Dispatchers.Unconfined))
    }

    @Test
    fun `malformed UTF-8 is a read error`() = runTest {
        try {
            BackupFiles.read(ByteArrayInputStream(byteArrayOf(0x7B, 0xC3.toByte(), 0x7D)), Dispatchers.Unconfined)
            fail("expected BackupReadException")
        } catch (_: BackupReadException) {
        }
    }

    @Test
    fun `read with an opener wraps null, IO and security errors`() = runTest {
        assertEquals("{}", BackupFiles.read({ ByteArrayInputStream("{}".toByteArray()) }, Dispatchers.Unconfined))
        val failures = listOf<() -> java.io.InputStream?>(
            { null },
            { throw java.io.FileNotFoundException("weg") },
            { throw SecurityException("verboten") },
        )
        for (open in failures) {
            try {
                BackupFiles.read(open, Dispatchers.Unconfined)
                fail("expected BackupReadException")
            } catch (_: BackupReadException) {
            }
        }
    }

    @Test
    fun `unchecked stream errors while reading become read errors`() = runTest {
        val errors = listOf<RuntimeException>(
            SecurityException("Grant entzogen"),
            IllegalStateException("Provider weg"),
            java.io.UncheckedIOException(java.io.IOException("kaputt")),
        )
        for (error in errors) {
            val input = object : java.io.InputStream() {
                override fun read(): Int = throw error
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw error
            }
            try {
                BackupFiles.read(input, Dispatchers.Unconfined)
                fail("expected BackupReadException for $error")
            } catch (e: BackupReadException) {
                assertTrue(e.cause === error)
            }
        }
    }

    @Test
    fun `files over the cap are rejected`() = runTest {
        assertEquals(16 * 1024 * 1024, BackupFiles.MAX_FILE_BYTES)
        val big = object : java.io.InputStream() {
            var left = BackupFiles.MAX_FILE_BYTES.toLong() + 1
            override fun read(): Int = if (left-- > 0) 'a'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                b.fill('a'.code.toByte(), off, off + n)
                left -= n
                return n
            }
        }
        try {
            BackupFiles.read(big, Dispatchers.Unconfined)
            fail("expected BackupReadException")
        } catch (_: BackupReadException) {
        }
    }
}
