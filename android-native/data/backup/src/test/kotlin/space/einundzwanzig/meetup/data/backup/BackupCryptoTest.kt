package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.BackupEnvelope
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.crypto.toHex

/**
 * `pbkdf2_equality_test.dart` (+ the 600k vector of `pbkdf2_web_equality_test.dart`) and the
 * `enc_v2`/`enc_v1` vectors of `spec/fixtures/08-backup-sync-vectors.json` (spec 08 §10.1, §10.2).
 */
class BackupCryptoTest {

    private val v2 = Fixtures["enc_v2"].jsonObject
    private val v1 = Fixtures["enc_v1"].jsonObject
    private fun str(o: kotlinx.serialization.json.JsonObject, k: String) = o.getValue(k).jsonPrimitive.content

    @Test
    fun `pbkdf2 matches every fixture vector`() {
        val vectors = Fixtures["pbkdf2"].jsonArray
        assertEquals(8, vectors.size)
        for (v in vectors) {
            val o = v.jsonObject
            val salt = DartUtf8.encode(str(o, "salt_utf8"))
            assertEquals(o.getValue("salt_len").jsonPrimitive.int, salt.size)
            val key = BackupCrypto.pbkdf2Sha256(
                DartUtf8.encode(str(o, "password_utf8")),
                salt,
                o.getValue("iterations").jsonPrimitive.int,
                o.getValue("key_bytes").jsonPrimitive.int,
            )
            assertEquals("${o["password_utf8"]} / ${o["iterations"]}", str(o, "expected_hex"), key.toHex())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pbkdf2 rejects zero iterations`() {
        BackupCrypto.pbkdf2Sha256(byteArrayOf(1), byteArrayOf(2), 0, 32)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pbkdf2 rejects zero key bytes`() {
        BackupCrypto.pbkdf2Sha256(byteArrayOf(1), byteArrayOf(2), 1, 0)
    }

    @Test
    fun `enc_v2 fixture decrypts to the exact plaintext`() = runTest {
        val container = BackupContainer.parse(str(v2, "file_content"))
        assertTrue(container is BackupContainer.V2)
        container as BackupContainer.V2
        assertEquals(str(v2, "salt_hex"), container.salt.toHex())
        assertEquals(str(v2, "iv_hex"), container.iv.toHex())
        assertEquals(str(v2, "derived_key_hex"), BackupEnvelope.deriveKeyV2(str(v2, "password"), container.salt).toHex())
        val result = BackupCrypto.decrypt(container, str(v2, "password"))
        assertEquals(OpenResult.Ok(str(v2, "plaintext")), result)
    }

    @Test
    fun `enc_v2 encryption with fixed salt and iv reproduces the fixture file`() = runTest {
        val content = BackupCrypto.encryptV2(
            str(v2, "plaintext"),
            str(v2, "password"),
            salt = Hex.decode(str(v2, "salt_hex")),
            iv = Hex.decode(str(v2, "iv_hex")),
        )
        assertEquals(str(v2, "file_content"), content)
    }

    @Test
    fun `random salt and iv round trip`() = runTest {
        val a = BackupCrypto.encryptV2("{\"x\":1}", "passwort1")
        val b = BackupCrypto.encryptV2("{\"x\":1}", "passwort1")
        assertTrue(a != b)
        val c = BackupContainer.parse(a) as BackupContainer.V2
        assertEquals(32, c.salt.size)
        assertEquals(16, c.iv.size)
        assertEquals(OpenResult.Ok("{\"x\":1}"), BackupCrypto.decrypt(c, "passwort1"))
    }

    @Test
    fun `enc_v1 fixture decrypts with the sha256 key`() = runTest {
        assertEquals(
            str(v1, "key_hex_sha256_of_password"),
            BackupEnvelope.deriveKeyV1(str(v1, "password")).toHex(),
        )
        val container = BackupContainer.parse(str(v1, "file_content"))
        assertTrue(container is BackupContainer.V1)
        assertArrayEquals(Hex.decode(str(v1, "iv_hex")), (container as BackupContainer.V1).iv)
        assertEquals(OpenResult.Ok(str(v1, "plaintext")), BackupCrypto.decrypt(container, str(v1, "password")))
    }

    @Test
    fun `wrong password is WrongPasswordOrCorrupt`() = runTest {
        val container = BackupContainer.parse(str(v1, "file_content"))
        assertEquals(OpenResult.WrongPasswordOrCorrupt, BackupCrypto.decrypt(container, "falsch"))
        val v2c = BackupContainer.parse(BackupCrypto.encryptV2("{}", "richtig123"))
        assertEquals(OpenResult.WrongPasswordOrCorrupt, BackupCrypto.decrypt(v2c, "falsch123", iterations = 600_000))
    }

    @Test
    fun `malformed containers are corrupt and still ask for the password`() = runTest {
        for (content in listOf("enc_v2:AAAA:BBBB", "enc_v2:a:b:c:d", "enc_v2:!!:AAAA:AAAA", "enc_v1:AAAA", "enc_v1:%%:AAAA")) {
            val c = BackupContainer.parse(content)
            assertTrue(content, c is BackupContainer.Corrupt)
            assertTrue(c.needsPassword)
            var asked = 0
            val r = BackupCrypto.open(content, password = { asked++; "pw" })
            assertEquals(1, asked)
            assertEquals(OpenResult.WrongPasswordOrCorrupt, r)
        }
    }

    @Test
    fun `tampered ciphertext is WrongPasswordOrCorrupt`() = runTest {
        val content = str(v1, "file_content")
        val tampered = content.dropLast(4) + "AAA="
        assertEquals(OpenResult.WrongPasswordOrCorrupt, BackupCrypto.open(tampered, password = { str(v1, "password") }))
    }

    @Test
    fun `plain json passes through without a password`() = runTest {
        val json = "{\"user\":{},\"badges\":[]}"
        val r = BackupCrypto.open(json, password = { error("must not ask") })
        assertEquals(OpenResult.Ok(json), r)
        assertEquals(BackupContainer.Plain(json), BackupContainer.parse(json))
    }

    @Test
    fun `open reports cancel and wraps key derivation in the overlay callback`() = runTest {
        assertEquals(OpenResult.Cancelled, BackupCrypto.open(str(v2, "file_content"), password = { null }))
        val events = mutableListOf<Boolean>()
        val r = BackupCrypto.open(str(v2, "file_content"), password = { str(v2, "password") }, onKeyDerivation = { events += it })
        assertEquals(OpenResult.Ok(str(v2, "plaintext")), r)
        assertEquals(listOf(true, false), events)
        // enc_v1 shows no overlay.
        events.clear()
        BackupCrypto.open(str(v1, "file_content"), password = { str(v1, "password") }, onKeyDerivation = { events += it })
        assertTrue(events.isEmpty())
    }

    @Test
    fun `url-safe base64 is accepted like Dart base64Decode`() = runTest {
        val content = str(v2, "file_content")
        val urlSafe = content.replace('+', '-').replace('/', '_')
        assertTrue(urlSafe != content)
        val r = BackupCrypto.open(urlSafe, password = { str(v2, "password") })
        assertEquals(OpenResult.Ok(str(v2, "plaintext")), r)
    }

    @Test
    fun `the overlay is closed even when cancelled during key derivation with a suspending callback`() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            BackupCrypto.open(str(v2, "file_content"), password = { str(v2, "password") }, onKeyDerivation = { running ->
                withContext(Dispatchers.IO) { events += running } // really suspends (like Main)
                if (running) started.complete(Unit)
            })
        }
        started.await()
        job.cancel()
        job.join()
        assertEquals(listOf(true, false), events.toList())
    }
}
