package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.humanity.LastChecked
import space.einundzwanzig.meetup.data.proofs.internal.DefaultHumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.internal.HumanityReverifyStartupTask
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 06 §10.6 humanity flow (fake relays, fake provider resolver) and status rules. */
class HumanityProofTest {

    private val me = KeyPair.generate()
    private val friend = KeyPair.generate()
    private val provider = KeyPair.generate()
    private val time = FakeTime(1_760_000_000_000L)
    private val identity = FakeIdentity(keyPair = me)
    private val relays = FakeRelays()
    private val store = InMemoryKeyValueStore(
        mapOf(
            RelayConfig.KEY_DISABLED_DEFAULTS to PrefValue.StringListValue(RelayConfig.DEFAULT_RELAYS),
            RelayConfig.KEY_CUSTOM_RELAYS to PrefValue.StringListValue(listOf("wss://r1", "wss://r2")),
        ),
    )

    /** Recipient → provider; records every provider check. */
    private class FakeResolver(val providers: Map<String, String>) : ZapProviderResolver {
        val checks = CopyOnWriteArrayList<String>()
        override suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String): Boolean {
            checks += receiptPubkey
            if (receiptPubkey == recipientPubkey || receiptPubkey == senderPubkey) return false
            return providers[recipientPubkey] == receiptPubkey
        }
        override suspend fun providerPubkeyFor(recipientPubkey: String): String? = providers[recipientPubkey]
        override suspend fun providerPubkeyFromLnurlp(url: String): String? = null
    }

    private val resolver = FakeResolver(mapOf(friend.publicKeyHex to provider.publicKeyHex))

    private fun repo(r: ZapProviderResolver = resolver) = DefaultHumanityProofRepository(
        store,
        RelayConfig(store, relays.connector),
        RelayClient(relays.connector),
        r,
        identity,
        time,
        log,
        testDispatcher,
    )

    @Test
    fun `provider-confirmed receipt on the second relay is found`() = runBlocking<Unit> {
        relays.add("wss://r1")
        val sent = receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex, createdAt = 1_759_000_000L)
        relays.add("wss://r2", sent)
        val result = repo().checkForZaps()
        assertTrue(result.found)
        assertEquals(HumanityOutcome.FOUND, result.outcome)
        assertEquals("Lightning-Zahlung gefunden — du bist verifiziert!", result.message)
        val id = sent["id"]!!.jsonPrimitive.content
        assertEquals(id, result.receiptEventId)
        assertEquals(true, store.getBool(HumanityKeys.VERIFIED))
        assertEquals(id, store.getString(HumanityKeys.RECEIPT_ID))
        assertEquals(1_759_000_000L, store.getInt(HumanityKeys.FIRST_ZAP_AT))
        assertEquals(1_760_000_000L, store.getInt(HumanityKeys.CHECKED_AT))
        // Three REQs per socket, in Dart order.
        val subs = relays.reqs.filter { it.first == "wss://r1" }.map { it.second[1].jsonPrimitive.content.substringBeforeLast('-') }
        assertEquals(listOf("zap-recv", "zap-req", "zap-sent"), subs)
        val r1Filters = relays.reqs.filter { it.first == "wss://r1" }.map { DartJson.encode(it.second[2]) }
        assertEquals(
            listOf(
                """{"kinds":[9735],"#p":["${me.publicKeyHex}"],"limit":3}""",
                """{"kinds":[9734],"authors":["${me.publicKeyHex}"],"limit":3}""",
                """{"kinds":[9735],"#P":["${me.publicKeyHex}"],"limit":3}""",
            ),
            r1Filters,
        )
        assertEquals(HumanityStatus(true, 1_759_000_000L, id, 1_760_000_000L), repo().getStatus())
        assertEquals(
            jsonObjectOf("verified" to true, "method" to "lightning_zap", "first_zap_at" to 1_759_000_000L),
            repo().getProofForPublishing(),
        )
    }

    @Test
    fun `received zaps count too`() = runBlocking<Unit> {
        val selfProviderMap = FakeResolver(mapOf(me.publicKeyHex to provider.publicKeyHex))
        relays.add("wss://r1", receiptWithSender(provider, sender = friend, recipient = me.publicKeyHex))
        assertTrue(repo(selfProviderMap).checkForZaps().found)
    }

    @Test
    fun `only self-issued receipts are not found, checked_at set, verified unchanged`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.VERIFIED, true)
        relays.add("wss://r1", receiptWithSender(me, sender = me, recipient = friend.publicKeyHex))
        val result = repo().checkForZaps()
        assertFalse(result.found)
        assertEquals(HumanityOutcome.NOT_FOUND, result.outcome)
        assertEquals(1_760_000_000L, store.getInt(HumanityKeys.CHECKED_AT))
        assertEquals(true, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getString(HumanityKeys.RECEIPT_ID))
    }

    @Test
    fun `a kind-9734 event alone is not found`() = runBlocking<Unit> {
        val request = DartJson.decode(zapRequest(me, friend.publicKeyHex)) as JsonObject
        relays.add("wss://r1", request)
        assertFalse(repo().checkForZaps().found)
        assertTrue(resolver.checks.isEmpty())
    }

    @Test
    fun `at most six candidates are checked per relay`() = runBlocking<Unit> {
        val strangers = List(8) { KeyPair.generate() }
        val receipts = strangers.mapIndexed { i, issuer ->
            receiptWithSender(issuer, sender = me, recipient = friend.publicKeyHex, createdAt = 1_759_000_000L + i)
        }
        relays.add("wss://r1", *receipts.toTypedArray())
        assertFalse(repo().checkForZaps().found)
        assertEquals(6, resolver.checks.size)
    }

    @Test
    fun `invalid receipts never reach the provider check`() = runBlocking<Unit> {
        // Valid id/sig but the bolt11 hash does not match the request.
        val description = zapRequest(me, friend.publicKeyHex)
        val bad = receipt(provider, friend.publicKeyHex, description, bolt11 = bolt11WithHash("0".repeat(64)))
        val tagged = EventIds.sign(
            provider, 1_759_000_000L, 9735,
            listOf(listOf("p", friend.publicKeyHex), listOf("P", me.publicKeyHex)) +
                (bad["tags"] as kotlinx.serialization.json.JsonArray).drop(1).map { t ->
                    (t as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
                },
            "",
        ).toJsonObject()
        relays.add("wss://r1", tagged)
        assertFalse(repo().checkForZaps().found)
        assertTrue(resolver.checks.isEmpty())
    }

    @Test
    fun `no key and invalid npub`() = runBlocking<Unit> {
        identity.npubOverride = null
        val noKey = DefaultHumanityProofRepository(
            store, RelayConfig(store, relays.connector), RelayClient(relays.connector), resolver,
            FakeIdentity(keyPair = null), time, log, testDispatcher,
        )
        assertEquals(HumanityOutcome.NO_KEY, noKey.checkForZaps().outcome)
        identity.npubOverride = "npub1invalid"
        assertEquals(HumanityOutcome.INVALID_NPUB, repo().checkForZaps().outcome)
        assertEquals("Ungültiger npub", repo().checkForZaps().message)
    }

    @Test
    fun `pending re-verification hides verified and reverify clears it`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.VERIFIED, true)
        store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        val r = repo()
        assertFalse(r.getStatus().verified)
        assertTrue(r.getStatus().needsReverification)
        assertNull(r.getProofForPublishing())
        assertFalse(r.status.first().verified)

        // Not found: flag stays.
        assertFalse(r.reverifyIfNeeded())
        assertEquals(true, store.getBool(HumanityKeys.NEEDS_REVERIFICATION))

        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            HumanityReverifyStartupTask(r, store, identity, scope, log).run()
            awaitStored { store.getBool(HumanityKeys.NEEDS_REVERIFICATION) == null }
        } finally {
            scope.cancel()
        }
        assertEquals(true, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getBool(HumanityKeys.NEEDS_REVERIFICATION))
        assertTrue(r.getStatus().verified)
        assertFalse(r.reverifyIfNeeded()) // flag gone → no-op
    }

    @Test
    fun `startup task re-verifies a proof restored later in the same session`() = runBlocking<Unit> {
        val r = repo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Flag is not set at start: run() returns at once and nothing is searched.
            withTimeout(1_000) { HumanityReverifyStartupTask(r, store, identity, scope, log).run() }
            assertTrue(relays.connector.connects.isEmpty())
            relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
            // Onboarding / backup screen restore after the task already ran.
            assertTrue(r.restoreFromBackup(jsonObjectOf("verified" to true, "first_zap_at" to 1, "receipt_event_id" to "ee".repeat(32)), additive = false))
            awaitStored { store.getBool(HumanityKeys.NEEDS_REVERIFICATION) == null }
            assertTrue(r.getStatus().verified)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed proof write is reported as error, not found`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        store.failingKeys = setOf(HumanityKeys.VERIFIED)
        val result = repo().checkForZaps()
        assertFalse(result.found)
        assertEquals(HumanityOutcome.ERROR, result.outcome)
        assertNull(store.getBool(HumanityKeys.VERIFIED))
    }

    private suspend fun awaitStored(condition: suspend () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `manual check also clears a pending re-verification (R6)`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        assertTrue(repo().checkForZaps().found)
        assertTrue(repo().getStatus().verified)
    }

    @Test
    fun `backup restore marks pending and identity switch clears`() = runBlocking<Unit> {
        val r = repo()
        val backup = jsonObjectOf("verified" to true, "first_zap_at" to 1_700_000_000L, "receipt_event_id" to "ee".repeat(32), "checked_at" to 0)
        assertTrue(r.restoreFromBackup(backup, additive = false))
        assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        assertEquals(true, store.getBool(HumanityKeys.NEEDS_REVERIFICATION))
        assertEquals(1_700_000_000L, store.getInt(HumanityKeys.FIRST_ZAP_AT))
        assertNull(store.getInt(HumanityKeys.CHECKED_AT))
        assertEquals(
            """{"verified":false,"first_zap_at":1700000000,"receipt_event_id":"${"ee".repeat(32)}","checked_at":0}""",
            DartJson.encode(r.getStatus().toBackupJson()),
        )

        // Additive restore keeps an already verified device.
        store.setBool(HumanityKeys.VERIFIED, true)
        store.remove(HumanityKeys.NEEDS_REVERIFICATION)
        assertFalse(r.restoreFromBackup(backup, additive = true))
        assertFalse(r.restoreFromBackup(jsonObjectOf("verified" to false), additive = false))

        store.setString("platform_proofs", "[]")
        assertTrue(r.clearForIdentitySwitch())
        assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getString("platform_proofs"))
        assertNull(store.getInt(HumanityKeys.FIRST_ZAP_AT))
        assertNull(store.getString(HumanityKeys.RECEIPT_ID))
    }

    @Test
    fun `status strings`() {
        val zone = ZoneOffset.UTC
        val now = 1_760_000_000_000L
        assertEquals(LastChecked.Never, HumanityStatus(false).lastChecked(now, zone))
        assertEquals("Nie", HumanityStatus(false).lastCheckedStr(now, zone))
        assertEquals("Gerade eben", HumanityStatus(false, lastCheckedAt = 1_760_000_000L - 59).lastCheckedStr(now, zone))
        assertEquals("Gerade eben", HumanityStatus(false, lastCheckedAt = 1_760_000_000L + 3600).lastCheckedStr(now, zone))
        assertEquals("Vor 5 Min.", HumanityStatus(false, lastCheckedAt = 1_760_000_000L - 330).lastCheckedStr(now, zone))
        assertEquals("Vor 23 Std.", HumanityStatus(false, lastCheckedAt = 1_760_000_000L - 86_399).lastCheckedStr(now, zone))
        assertEquals("3.5.2025", HumanityStatus(false, lastCheckedAt = 1_746_230_400L).lastCheckedStr(now, zone))
        assertEquals("3.5.2025", HumanityStatus(true, firstZapAt = 1_746_230_400L).firstZapDateStr(zone))
        assertEquals("", HumanityStatus(true).firstZapDateStr(zone))
    }

    // ---- identity switch during a running search (spec 06 §14 R20) ----

    /** [FakeResolver] that runs [during] on the first provider check, i.e. while the search runs. */
    private class HookResolver(val inner: ZapProviderResolver, val during: suspend () -> Unit) : ZapProviderResolver by inner {
        private var fired = false
        override suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String): Boolean {
            if (!fired) {
                fired = true
                during()
            }
            return inner.isFromRecipientProvider(receiptPubkey, recipientPubkey, senderPubkey)
        }
    }

    @Test
    fun `clearForIdentitySwitch while the relay is answering keeps humanity_verified false`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        lateinit var r: DefaultHumanityProofRepository
        r = repo(HookResolver(resolver) { assertTrue(r.clearForIdentitySwitch()) })
        val result = r.checkForZaps()
        assertFalse(result.found)
        assertEquals(HumanityOutcome.ERROR, result.outcome)
        assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getString(HumanityKeys.RECEIPT_ID))
        assertNull(store.getInt(HumanityKeys.FIRST_ZAP_AT))
        assertNull(store.getInt(HumanityKeys.CHECKED_AT))
    }

    @Test
    fun `badge-sync wipe plus a new npub during the search writes nothing`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        val r = repo(
            HookResolver(resolver) {
                identity.npubOverride = friend.npub
                store.edit { HumanityKeys.clearIdentityProofs(this); setBool("badge_restore_proofs", true) }
            },
        )
        assertFalse(r.checkForZaps().found)
        assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getString(HumanityKeys.RECEIPT_ID))
    }

    @Test
    fun `a new npub alone during the search writes nothing, also not checked_at`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        val r = repo(HookResolver(resolver) { identity.npubOverride = friend.npub })
        assertEquals(HumanityOutcome.ERROR, r.checkForZaps().outcome)
        assertNull(store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getInt(HumanityKeys.CHECKED_AT))
    }

    @Test
    fun `reverify does not confirm a proof whose flag an identity switch cleared`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.VERIFIED, false)
        store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        lateinit var r: DefaultHumanityProofRepository
        r = repo(HookResolver(resolver) { r.clearForIdentitySwitch() })
        assertFalse(r.reverifyIfNeeded())
        assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getBool(HumanityKeys.NEEDS_REVERIFICATION))
        assertFalse(r.getStatus().verified)
    }

    @Test
    fun `clearing the flag cancels a running startup re-verification`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val blocking = HookResolver(resolver) {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val r = repo(blocking)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            HumanityReverifyStartupTask(r, store, identity, scope, log).run()
            withTimeout(10_000) { entered.await() }
            assertTrue(r.clearForIdentitySwitch())
            withTimeout(10_000) { cancelled.await() }
            assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent checks share one search`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val r = repo(
            HookResolver(resolver) {
                entered.complete(Unit)
                release.await()
            },
        )
        val first = async(Dispatchers.Default) { r.checkForZaps() }
        withTimeout(10_000) { entered.await() }
        val second = async(Dispatchers.Default) { r.checkForZaps() }
        delay(300) // the second caller is now waiting for the running search
        release.complete(Unit)
        val a = first.await()
        val b = second.await()
        assertTrue(a.found)
        assertEquals(a, b)
        assertEquals(1, relays.connector.connects.count { it == "wss://r1" })
    }

    // ---- review fixes: discarded runs, self-cancelling startup attempt, retries, ERROR text ----

    /** Pass-through resolver that blocks on the [blockOn]-th provider check until [release]. */
    private class NthCallResolver(val inner: ZapProviderResolver, val blockOn: Int) : ZapProviderResolver by inner {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String): Boolean {
            if (calls.incrementAndGet() == blockOn) {
                entered.complete(Unit)
                release.await()
            }
            return inner.isFromRecipientProvider(receiptPubkey, recipientPubkey, senderPubkey)
        }
    }

    @Test
    fun `a waiter behind a discarded run searches again instead of taking an older result`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
        val resolver2 = NthCallResolver(resolver, blockOn = 2)
        val r = repo(resolver2)
        // Search A: FOUND.
        assertTrue(r.checkForZaps().found)
        // Manual search B is running ...
        val b = async(Dispatchers.Default) { r.checkForZaps() }
        withTimeout(10_000) { resolver2.entered.await() }
        // ... when a backup restore marks the proof pending; the startup re-verification waits.
        assertTrue(r.restoreFromBackup(jsonObjectOf("verified" to true, "receipt_event_id" to "ee".repeat(32)), additive = false))
        val reverify = async(Dispatchers.Default) { r.reverifyIfNeeded() }
        delay(300)
        resolver2.release.complete(Unit)
        val bResult = b.await()
        assertEquals(HumanityOutcome.ERROR, bResult.outcome) // B saw the restore: discarded
        assertEquals("Prüfung fehlgeschlagen — bitte erneut versuchen.", bResult.message)
        assertTrue(reverify.await())
        // A fresh relay search ran for the re-verification (not A's result).
        assertEquals(3, resolver2.calls.get())
        assertEquals(3, relays.connector.connects.count { it == "wss://r1" })
        assertTrue(r.getStatus().verified)
        assertNull(store.getBool(HumanityKeys.NEEDS_REVERIFICATION))
    }

    @Test
    fun `a manual check waiting behind the startup attempt reuses its result although the attempt was cancelled by its own write`() =
        runBlocking<Unit> {
            store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
            relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
            val blocking = NthCallResolver(resolver, blockOn = 1)
            val r = repo(blocking)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                HumanityReverifyStartupTask(r, store, identity, scope, log).run()
                withTimeout(10_000) { blocking.entered.await() }
                val manual = async(Dispatchers.Default) { r.checkForZaps() }
                delay(300)
                blocking.release.complete(Unit)
                assertTrue(withTimeout(10_000) { manual.await() }.found)
                assertEquals(1, blocking.calls.get())
                assertEquals(1, relays.connector.connects.count { it == "wss://r1" })
                assertTrue(r.getStatus().verified)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `startup task retries after the backoff while the flag stays set`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        val r = repo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val task = HumanityReverifyStartupTask(r, store, identity, scope, log)
            task.retryDelays = listOf(200.milliseconds)
            task.run()
            awaitStored { store.getInt(HumanityKeys.CHECKED_AT) != null } // 1st attempt: nothing found
            assertEquals(true, store.getBool(HumanityKeys.NEEDS_REVERIFICATION))
            relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
            awaitStored { store.getBool(HumanityKeys.NEEDS_REVERIFICATION) == null }
            assertTrue(r.getStatus().verified)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startup task retries when a signer becomes ready after the backoff ran out`() = runBlocking<Unit> {
        store.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        identity.npubOverride = "" // signer not ready yet: NO_KEY
        val r = repo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val task = HumanityReverifyStartupTask(r, store, identity, scope, log)
            task.retryDelays = emptyList()
            task.run()
            delay(300)
            assertEquals(true, store.getBool(HumanityKeys.NEEDS_REVERIFICATION))
            assertTrue(relays.connector.connects.isEmpty())
            relays.add("wss://r1", receiptWithSender(provider, sender = me, recipient = friend.publicKeyHex))
            identity.npubOverride = null
            identity.signerReadyEvents.emit(Unit)
            awaitStored { store.getBool(HumanityKeys.NEEDS_REVERIFICATION) == null }
            assertTrue(r.getStatus().verified)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ERROR has its own message`() {
        assertEquals(
            "Prüfung fehlgeschlagen — bitte erneut versuchen.",
            space.einundzwanzig.meetup.data.proofs.humanity.HumanityCheckResult(false, HumanityOutcome.ERROR).message,
        )
        assertTrue(
            space.einundzwanzig.meetup.data.proofs.humanity.HumanityCheckResult(false, HumanityOutcome.NOT_FOUND).message
                .startsWith("Kein Zap-Receipt gefunden."),
        )
    }
}
