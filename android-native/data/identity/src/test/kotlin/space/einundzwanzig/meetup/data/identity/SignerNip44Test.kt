package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.signer.LocalNostrSigner
import space.einundzwanzig.meetup.data.identity.signer.Nip46NostrSigner

/** Port of `signer_nip44_test.dart` (service level; the NIP-07 case is web-only). */
class SignerNip44Test {

    private val kc = KeyPair.generate()
    private val other = KeyPair.generate()
    private lateinit var id: TestIdentity

    @Before
    fun setUp() = runBlocking {
        id = TestIdentity(mapOf(IdentityKeys.SECURE_MIGRATION_DONE to PrefValue.BoolValue(true)))
        id.keyStore.saveKeys(kc.nsec, kc.npub, kc.privateKeyHex)
    }

    private fun local() = LocalNostrSigner(id.keyStore, id.time, Dispatchers.Default)

    /** Dart `_ProbeSigner`: fixed identity, scripted failures, fake payloads. */
    private inner class ProbeSigner(private val failure: String) : LocalNostrSigner(id.keyStore, id.time, Dispatchers.Default) {
        override suspend fun pubkeyHex(): String = "public"
        override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String {
            if (failure == "encrypt") throw SigningException("Relay nicht erreichbar")
            return "PRIVATE-PAYLOAD"
        }
        override suspend fun nip44Decrypt(peerHex: String, payload: String): String {
            if (failure == "decrypt") throw SigningException("Freigabe abgelehnt")
            return "falscher Klartext"
        }
    }

    @Test
    fun localSignerRoundTrip() = runBlocking {
        val sealed = local().nip44Encrypt(other.publicKeyHex, "hallo sync")
        assertEquals("hallo sync", local().nip44Decrypt(other.publicKeyHex, sealed))
    }

    @Test
    fun localSignerUsesTheAppConversationKey() = runBlocking {
        val sealed = local().nip44Encrypt(other.publicKeyHex, "rundreise")
        val key = Nip44.conversationKey(kc.privateKeyHex, other.publicKeyHex)
        assertEquals("rundreise", Nip44.decrypt(sealed, key))
    }

    @Test
    fun nip46SignerCallsTheBunkerMethods() = runBlocking {
        val calls = mutableListOf<String>()
        val signer = Nip46NostrSigner(
            expectedPubkeyHex = kc.publicKeyHex,
            clientProvider = { throw IllegalStateException("kein echter Client im Test") },
            timeSource = id.time,
            nip44Fn = { method, pk, value ->
                calls += "$method:$pk:$value"
                if (method == "nip44_encrypt") "out:$value" else value.substring(4)
            },
        )
        val sealed = signer.nip44Encrypt(other.publicKeyHex, "chunk")
        assertEquals("out:chunk", sealed)
        assertEquals("chunk", signer.nip44Decrypt(other.publicKeyHex, sealed))
        assertEquals(
            listOf("nip44_encrypt:${other.publicKeyHex}:chunk", "nip44_decrypt:${other.publicKeyHex}:out:chunk"),
            calls,
        )
    }

    private fun logText(from: Int) = AppLogger.entries.value.drop(from).joinToString("\n") { "${it.tag}: ${it.message}" }

    @Test
    fun probeReportsStepAndReasonWithoutPayload() = runBlocking {
        for (step in listOf("encrypt", "decrypt")) {
            val before = AppLogger.entries.value.size
            assertFalse(id.signing.canNip44(ProbeSigner(step)))
            val log = logText(before)
            assertTrue(log, log.contains("nip44_$step"))
            assertTrue(log.contains(if (step == "encrypt") "Relay nicht erreichbar" else "Freigabe abgelehnt"))
            assertFalse(log.contains("PRIVATE-PAYLOAD"))
            assertFalse(log.contains("falscher Klartext"))
        }
    }

    @Test
    fun probeReportsAMismatch() = runBlocking {
        val before = AppLogger.entries.value.size
        assertFalse(id.signing.canNip44(ProbeSigner("mismatch")))
        assertTrue(logText(before).contains("stimmt nicht überein"))
    }

    @Test
    fun canNip44IsTrueInLocalModeWithKey() = runBlocking {
        id.prefs.setString(IdentityKeys.SIGNING_MODE, "local")
        assertTrue(id.signing.canNip44())
    }

    @Test
    fun serviceLevelNip44GoesThroughTheActiveSigner() = runBlocking {
        val sealed = id.signing.nip44Encrypt(other.publicKeyHex, "über den Dienst")
        assertEquals("über den Dienst", Nip44.decrypt(sealed, Nip44.conversationKey(other.privateKeyHex, kc.publicKeyHex)))
        assertEquals("über den Dienst", id.signing.nip44Decrypt(other.publicKeyHex, sealed))
    }

    @Test
    fun localSigningProducesAValidEvent() = runBlocking {
        val ev = id.signing.signEvent(21000, listOf(listOf("meetup", "x")), "Badge")
        assertTrue(ev.isValid())
        assertEquals(kc.publicKeyHex, ev.pubkey)
        assertEquals(id.time.nowSeconds(), ev.createdAt)
    }
}
