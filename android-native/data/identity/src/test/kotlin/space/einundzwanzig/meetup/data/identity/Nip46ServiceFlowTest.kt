package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.nip46.BunkerPointer
import space.einundzwanzig.meetup.data.identity.nip46.FakeSigner
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Timeouts
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Native-only wiring of [SigningService] around NIP-46 and Amber: persistence, session adoption,
 * cleanup on failure/cancellation, `auth_url` routing. Uses the real NIP-44 fake signer.
 */
class Nip46ServiceFlowTest {

    private val user = KeyPair.generate()
    private val signer = FakeSigner().apply {
        respond = { r ->
            val id = r["id"]
            when (r["method"]!!.jsonPrimitive.content) {
                "get_public_key" -> jsonObjectOf("id" to id, "result" to user.publicKeyHex)
                "sign_event" -> {
                    val u = DartJson.decode(r["params"]!!.jsonArray[0].jsonPrimitive.content).jsonObject
                    val signed = EventIds.sign(
                        user,
                        u["created_at"]!!.jsonPrimitive.content.toLong(),
                        u["kind"]!!.jsonPrimitive.content.toInt(),
                        u["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } },
                        u["content"]!!.jsonPrimitive.content,
                    )
                    jsonObjectOf("id" to id, "result" to signed.toJson())
                }
                else -> jsonObjectOf("id" to id, "result" to "ack")
            }
        }
    }

    private val relay = "wss://relay-a.test"

    private fun identity(limit: kotlin.time.Duration = 2.seconds) =
        TestIdentity(opener = signer.opener, timeouts = Nip46Timeouts.uniform(limit))

    private fun collectReady(id: TestIdentity, into: MutableList<Unit>) =
        CoroutineScope(Dispatchers.Unconfined).launch { id.signing.signerReady.collect { into += it } }

    @Test
    fun bunkerConnectPersistsTheSessionWithoutSecretAndReusesTheClient() = runBlocking {
        val id = identity()
        val ready = CopyOnWriteArrayList<Unit>()
        val job = collectReady(id, ready)
        val uri = "bunker://${signer.pubkey}?relay=${relay}&secret=einmalig"

        val result = id.signing.connectNip46Bunker(uri)

        assertEquals(Nip46ConnectResult.Success(user.publicKeyHex, Nip19.encodeNpub(user.publicKeyHex)), result)
        // connect carried the one-time secret ...
        val connect = signer.requests.first { it["method"]!!.jsonPrimitive.content == "connect" }
        assertEquals("einmalig", connect["params"]!!.jsonArray[1].jsonPrimitive.content)
        // ... but the persisted address does not.
        val stored = id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI)!!
        assertEquals("bunker://${signer.pubkey}?relay=wss%3A%2F%2Frelay-a.test", stored)
        assertFalse(stored.contains("secret"))
        assertEquals("nip46", id.prefs.getString(IdentityKeys.SIGNING_MODE))
        assertEquals(user.publicKeyHex, id.prefs.getString(IdentityKeys.NIP46_PUBKEY_HEX))
        assertEquals(Nip19.encodeNpub(user.publicKeyHex), id.prefs.getString(IdentityKeys.NIP46_NPUB))
        assertNotNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        assertEquals(1, ready.size)

        // The next signature goes through the adopted client (no second client, no reconnect).
        val event = id.signing.signEvent(21000, listOf(listOf("d", "x")), "Badge-Signatur")
        assertEquals(user.publicKeyHex, event.pubkey)
        assertEquals(1, id.createdClients.size)
        assertEquals(1, signer.links.size)
        job.cancel()
        id.signing.disconnectNip46()
        assertTrue(signer.links.all { it.closed })
    }

    @Test
    fun pairingEchoIsStoredAsBunkerAddress() = runBlocking {
        val id = identity()
        val pairing = id.signing.startNip46Pairing()
        val result = async { id.signing.completeNip46Pairing(pairing) }
        val clientPub = pairing.client.clientPubkeyHex
        while (signer.links.size < pairing.relays.size || signer.links.any { it.sentFrames.isEmpty() }) delay(5)
        for (link in signer.links) link.push(signer.wrap(jsonObjectOf("id" to "p", "result" to pairing.secret), clientPub))

        assertTrue(result.await() is Nip46ConnectResult.Success)
        assertEquals(BunkerPointer(signer.pubkey, pairing.relays).toBunkerUri(), id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI))
        assertEquals(SigningMode.NIP46, id.signing.getMode())
        // The pairing client is the live session.
        id.signing.signEvent(1, emptyList(), "x")
        assertEquals(1, id.createdClients.size)
        id.signing.disconnectNip46()
    }

    @Test
    fun cancelAfterASuccessfulPairingKeepsTheLiveSession() = runBlocking {
        val id = identity()
        val pairing = id.signing.startNip46Pairing()
        val result = async { id.signing.completeNip46Pairing(pairing) }
        val clientPub = pairing.client.clientPubkeyHex
        while (signer.links.size < pairing.relays.size || signer.links.any { it.sentFrames.isEmpty() }) delay(5)
        for (link in signer.links) link.push(signer.wrap(jsonObjectOf("id" to "p", "result" to pairing.secret), clientPub))
        assertTrue(result.await() is Nip46ConnectResult.Success)

        // The sheet's onCleared/dispose cancels unconditionally: the adopted session survives.
        pairing.cancel()
        val event = id.signing.signEvent(1, emptyList(), "nach dem Schließen")
        assertEquals(user.publicKeyHex, event.pubkey)
        assertEquals(1, id.createdClients.size)
        assertFalse(signer.links.all { it.closed })
        id.signing.disconnectNip46()
    }

    @Test
    fun modeWriteFailureAfterConnectLeavesNoOrphanSession() = runBlocking {
        val id = identity()
        id.prefs.failingKeys = setOf(IdentityKeys.SIGNING_MODE)
        val result = id.signing.connectNip46Bunker("bunker://${signer.pubkey}?relay=$relay")

        assertTrue(result is Nip46ConnectResult.Error)
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.NIP46_PUBKEY_HEX))
        assertNull(id.prefs.getString(IdentityKeys.NIP46_NPUB))
        assertNull(id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI))
        assertNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        assertTrue(signer.links.all { it.closed })
    }

    @Test
    fun authUrlWithoutCollectorWaitsUntilAcknowledged() = runBlocking {
        val id = identity()
        val pairing = id.signing.startNip46Pairing()
        val onAuthUrl = id.authCallbacks.single()
        // The root is not collecting (user is in the signer app).
        onAuthUrl("https://a.test/approve")

        val seen = CopyOnWriteArrayList<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            id.signing.authUrlEvents.collect { seen += it }
        }
        assertEquals(listOf("https://a.test/approve"), seen)
        job.cancel()

        // Not acknowledged yet: a re-subscribing root sees it again ...
        val again = CopyOnWriteArrayList<String>()
        val job2 = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            id.signing.authUrlEvents.collect { again += it }
        }
        assertEquals(listOf("https://a.test/approve"), again)
        // ... acknowledging an older URL keeps a newer one pending.
        onAuthUrl("https://a.test/newer")
        id.signing.acknowledgeAuthUrl("https://a.test/approve")
        job2.cancel()
        val third = CopyOnWriteArrayList<String>()
        val job3 = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            id.signing.authUrlEvents.collect { third += it }
        }
        assertEquals(listOf("https://a.test/newer"), third)
        id.signing.acknowledgeAuthUrl("https://a.test/newer")
        job3.cancel()
        val fourth = CopyOnWriteArrayList<String>()
        val job4 = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            id.signing.authUrlEvents.collect { fourth += it }
        }
        assertTrue(fourth.isEmpty())
        job4.cancel()
        pairing.cancel()
    }

    @Test
    fun silentSignerTimesOutAndClosesTheClient() = runBlocking {
        signer.respond = { null }
        val id = identity(limit = 300.milliseconds)
        val result = id.signing.connectNip46Bunker("bunker://${signer.pubkey}?relay=$relay")
        assertEquals(Nip46ConnectResult.Timeout, result)
        assertTrue(signer.links.isNotEmpty())
        assertTrue(signer.links.all { it.closed })
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI))
        assertNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
    }

    @Test
    fun remoteErrorIsAnErrorResultAndClosesTheClient() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "error" to "user denied") }
        val id = identity()
        val result = id.signing.connectNip46Bunker("bunker://${signer.pubkey}?relay=$relay")
        assertEquals(Nip46ConnectResult.Error("user denied"), result)
        assertTrue(signer.links.all { it.closed })
    }

    @Test
    fun cancelledConnectClosesTheClient() = runBlocking {
        signer.respond = { null }
        val id = identity(limit = 10.seconds)
        val job = async(Dispatchers.Default) { id.signing.connectNip46Bunker("bunker://${signer.pubkey}?relay=$relay") }
        while (signer.requests.isEmpty()) delay(5)
        job.cancel()
        runCatching { job.await() }
        assertTrue(signer.links.all { it.closed })
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
    }

    @Test
    fun cancelledPairingClosesItsClient() = runBlocking {
        val id = identity(limit = 10.seconds)
        val pairing = id.signing.startNip46Pairing()
        val job = async(Dispatchers.Default) { id.signing.completeNip46Pairing(pairing) }
        while (signer.links.size < pairing.relays.size) delay(5)
        job.cancel()
        runCatching { job.await() }
        assertTrue(signer.links.all { it.closed })
    }

    @Test
    fun switchingAwayFromBunkerClosesTheLiveSessionAndNothingRehydratesIt() = runBlocking {
        val id = identity()
        assertTrue(id.signing.connectNip46Bunker("bunker://${signer.pubkey}?relay=$relay") is Nip46ConnectResult.Success)
        id.signing.useLocalMode()
        assertTrue(signer.links.all { it.closed })
        assertNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        // Signing now uses the local signer; no NIP-46 client is created again.
        val error = runCatching { id.signing.signEvent(1, emptyList(), "x") }.exceptionOrNull() as SigningException
        assertEquals(SigningError.NoPrivateKey, error.error)
        assertEquals(1, id.createdClients.size)
    }

    @Test
    fun missingSessionIsTypedNoBunkerSession() = runBlocking {
        val id = identity()
        id.signing.restoreNip46(Nip19.encodeNpub(user.publicKeyHex), "bunker://${signer.pubkey}?relay=$relay")
        val error = runCatching { id.signing.signEvent(1, emptyList(), "x") }.exceptionOrNull() as SigningException
        assertEquals(SigningError.NoBunkerSession, error.error)
    }

    @Test
    fun interceptAuthUrlsStacksAndRestores() = runBlocking {
        val id = identity()
        val global = CopyOnWriteArrayList<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            id.signing.authUrlEvents.collect { global += it }
        }
        val pairing = id.signing.startNip46Pairing()
        val onAuthUrl = id.authCallbacks.single()
        val outer = CopyOnWriteArrayList<String>()
        val inner = CopyOnWriteArrayList<String>()

        val outerHandle = id.signing.interceptAuthUrls { outer += it }
        val innerHandle = id.signing.interceptAuthUrls { inner += it }
        onAuthUrl("https://a.test/1")
        innerHandle.close()
        onAuthUrl("https://a.test/2")
        outerHandle.close()
        onAuthUrl("https://a.test/3")

        assertEquals(listOf("https://a.test/1"), inner)
        assertEquals(listOf("https://a.test/2"), outer)
        assertEquals(listOf("https://a.test/3"), global)
        job.cancel()
        pairing.cancel()
    }

    @Test
    fun throwingAuthHandlerFallsBackToTheGlobalEvents() = runBlocking {
        val id = identity()
        val global = CopyOnWriteArrayList<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            id.signing.authUrlEvents.collect { global += it }
        }
        val pairing = id.signing.startNip46Pairing()
        val onAuthUrl = id.authCallbacks.single()
        val handle = id.signing.interceptAuthUrls { throw IllegalStateException("UI weg") }
        onAuthUrl("https://a.test/approve")
        assertEquals(listOf("https://a.test/approve"), global)
        handle.close()
        job.cancel()
        pairing.cancel()
    }

    // ---------------- Amber ----------------

    @Test
    fun amberConnectRestoreAndDisconnectWriteTheirPrefs() = runBlocking {
        val id = TestIdentity(nip55 = FakeNip55(installed = true, publicKey = { Nip19.encodeNpub(user.publicKeyHex) }))
        val ready = CopyOnWriteArrayList<Unit>()
        val job = collectReady(id, ready)

        val result = id.signing.connectAmber()
        assertEquals(AmberConnectResult.Success(user.publicKeyHex, Nip19.encodeNpub(user.publicKeyHex)), result)
        assertEquals("amber", id.prefs.getString(IdentityKeys.SIGNING_MODE))
        assertEquals(user.publicKeyHex, id.prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX))
        assertEquals(Nip19.encodeNpub(user.publicKeyHex), id.prefs.getString(IdentityKeys.AMBER_NPUB))
        assertEquals(1, ready.size)

        id.signing.disconnectAmber()
        assertEquals("local", id.prefs.getString(IdentityKeys.SIGNING_MODE))
        assertNull(id.prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX))
        assertNull(id.prefs.getString(IdentityKeys.AMBER_NPUB))

        // Restore trims with Dart semantics (U+FEFF is whitespace for Dart trim).
        id.signing.restoreAmber("﻿" + Nip19.encodeNpub(user.publicKeyHex) + " ")
        assertEquals("amber", id.prefs.getString(IdentityKeys.SIGNING_MODE))
        assertEquals(user.publicKeyHex, id.prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX))
        assertEquals(Nip19.encodeNpub(user.publicKeyHex), id.prefs.getString(IdentityKeys.AMBER_NPUB))
        assertEquals(2, ready.size)
        job.cancel()
    }

    @Test
    fun amberCancelAndMissingDoNotChangeTheMode() = runBlocking {
        val id = TestIdentity(nip55 = FakeNip55(installed = true))
        assertEquals(AmberConnectResult.Cancelled, id.signing.connectAmber())
        id.nip55.installed = false
        assertEquals(AmberConnectResult.Missing, id.signing.connectAmber())
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX))
    }
}
