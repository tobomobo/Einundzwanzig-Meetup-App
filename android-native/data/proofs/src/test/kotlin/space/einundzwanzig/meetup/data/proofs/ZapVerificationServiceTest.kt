package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.proofs.internal.DefaultZapVerificationService
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import java.util.concurrent.CopyOnWriteArrayList

/** `ZapVerificationService.analyzeZapActivity` against fake relays (spec 06 §8.5). */
class ZapVerificationServiceTest {

    private val me = KeyPair.generate()
    private val alice = KeyPair.generate()
    private val bob = KeyPair.generate()
    private val providerA = KeyPair.generate()
    private val providerMe = KeyPair.generate()
    private val time = FakeTime(1_760_000_000_000L)
    private val relays = FakeRelays()
    private val store = InMemoryKeyValueStore(
        mapOf(
            RelayConfig.KEY_DISABLED_DEFAULTS to PrefValue.StringListValue(RelayConfig.DEFAULT_RELAYS),
            RelayConfig.KEY_CUSTOM_RELAYS to PrefValue.StringListValue(listOf("wss://r1", "wss://r2", "wss://r3", "wss://r4")),
        ),
    )

    /** Recipient → provider; records every provider lookup. */
    private class Providers(val map: MutableMap<String, String>) : ZapProviderResolver {
        val lookups = CopyOnWriteArrayList<String>()
        override suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String) = false
        override suspend fun providerPubkeyFor(recipientPubkey: String): String? {
            lookups += recipientPubkey
            return map[recipientPubkey]
        }
        override suspend fun providerPubkeyFromLnurlp(url: String): String? = null
    }

    private val providers = Providers(mutableMapOf(alice.publicKeyHex to providerA.publicKeyHex, me.publicKeyHex to providerMe.publicKeyHex))

    private fun service() = DefaultZapVerificationService(
        store, RelayConfig(store, relays.connector), RelayClient(relays.connector), providers, FakeIdentity(keyPair = me), time, log, testDispatcher,
    )

    private val now = 1_760_000_000L
    private val day = 86_400L

    @Test
    fun `counts only provider-issued receipts and caches the result`() = runBlocking<Unit> {
        relays.add(
            "wss://r1",
            receiptWithSender(providerA, sender = me, recipient = alice.publicKeyHex, createdAt = now - 100 * day),
            receiptWithSender(bob, sender = me, recipient = alice.publicKeyHex, createdAt = now - 90 * day), // wrong issuer
            receiptWithSender(providerMe, sender = bob, recipient = me.publicKeyHex, createdAt = now - 10 * day),
            receiptWithSender(providerA, sender = me, recipient = alice.publicKeyHex, createdAt = now - 200 * day), // too old
        )
        relays.add("wss://r4", receiptWithSender(providerA, sender = me, recipient = alice.publicKeyHex, createdAt = now - 5 * day))

        val stats = service().getMyStats()
        assertEquals(ZapStats(1, 1, 1, 1, 3, true), stats)
        // Only the first 3 relays are asked.
        assertTrue(relays.connector.connects.none { it == "wss://r4" })
        // REQ shape.
        val req = relays.reqs.first().second
        assertTrue(req[1].jsonPrimitive.content.startsWith("zaps-"))
        assertEquals(
            setOf("""{"kinds":[9735],"since":${now - 180 * day},"limit":100,"#p":["${me.publicKeyHex}"]}""",
                """{"kinds":[9735],"since":${now - 180 * day},"limit":100,"#P":["${me.publicKeyHex}"]}"""),
            relays.reqs.filter { it.first == "wss://r1" }.map { DartJson.encode(it.second[2]) }.toSet(),
        )
        assertEquals(DartJson.encode(stats.toJson()), store.getString("zap_stats_cache_v2_${me.publicKeyHex}"))
        assertEquals(time.millis, store.getInt("zap_cache_ts_v2_${me.publicKeyHex}"))

        // Cache hit: no further sockets.
        val sockets = relays.connector.connects.size
        assertEquals(stats, service().analyzeZapActivity(me.publicKeyHex))
        assertEquals(sockets, relays.connector.connects.size)
        // useCache = false queries again.
        service().analyzeZapActivity(me.publicKeyHex, useCache = false)
        assertTrue(relays.connector.connects.size > sockets)
        // Expired cache queries again.
        time.millis += 6 * 3_600_000L + 1
        val before = relays.connector.connects.size
        service().analyzeZapActivity(me.publicKeyHex)
        assertTrue(relays.connector.connects.size > before)
    }

    @Test
    fun `no identity gives EMPTY`() = runBlocking<Unit> {
        val s = DefaultZapVerificationService(
            store, RelayConfig(store, relays.connector), RelayClient(relays.connector), providers, FakeIdentity(keyPair = null), time, log, testDispatcher,
        )
        assertEquals(ZapStats.EMPTY, s.getMyStats())
    }

    @Test
    fun `only the first 15 recipient groups get a provider lookup`() = runBlocking<Unit> {
        val recipients = List(16) { KeyPair.generate() }
        val issuers = List(16) { KeyPair.generate() }
        recipients.forEachIndexed { i, r -> providers.map[r.publicKeyHex] = issuers[i].publicKeyHex }
        relays.add(
            "wss://r1",
            *recipients.mapIndexed { i, r ->
                receiptWithSender(issuers[i], sender = me, recipient = r.publicKeyHex, createdAt = now - day - i)
            }.toTypedArray(),
        )
        val stats = service().analyzeZapActivity(me.publicKeyHex, useCache = false)
        assertEquals(15, stats.sentCount)
        assertEquals(15, stats.uniqueRecipientCount)
        // The 16th group is dropped without a lookup (whatever its arrival position was).
        val looked = providers.lookups.toSet()
        assertEquals(15, recipients.count { it.publicKeyHex in looked })
    }

    @Test
    fun `50 receipts from the first relay end the direction, relay 2 is not asked for it`() = runBlocking<Unit> {
        relays.add(
            "wss://r1",
            *List(50) { i -> receiptWithSender(providerMe, sender = bob, recipient = me.publicKeyHex, createdAt = now - day - i) }.toTypedArray(),
        )
        relays.add("wss://r2", receiptWithSender(providerMe, sender = alice, recipient = me.publicKeyHex, createdAt = now - 2 * day))
        val stats = service().analyzeZapActivity(me.publicKeyHex, useCache = false)
        assertEquals(50, stats.receivedCount)
        assertEquals(0, stats.sentCount)
        fun filtersTo(relay: String) = relays.reqs.filter { it.first == relay }.map { (it.second[2] as JsonObject).keys }
        assertTrue(filtersTo("wss://r2").none { "#p" in it })
        // The sent direction found nothing on r1 and goes on to r2 and r3.
        assertTrue(filtersTo("wss://r2").any { "#P" in it })
        assertTrue("wss://r3" in relays.connector.connects) // unreachable, but tried for the sent direction
    }

    @Test
    fun `a relay error discards that relay's receipts, a normal close keeps them`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(providerMe, sender = bob, recipient = me.publicKeyHex, createdAt = now - day))
        relays.closeInsteadOfEose["wss://r1"] = java.io.IOException("reset")
        assertEquals(0, service().analyzeZapActivity(me.publicKeyHex, useCache = false).receivedCount)

        relays.closeInsteadOfEose["wss://r1"] = null
        assertEquals(1, service().analyzeZapActivity(me.publicKeyHex, useCache = false).receivedCount)
    }

    @Test
    fun `cache older than 6 h is refetched, exactly 6 h is still used`() = runBlocking<Unit> {
        val cached = ZapStats(7, 7, 7, 7, 7, true)
        store.setString("zap_stats_cache_v2_${me.publicKeyHex}", DartJson.encode(cached.toJson()))
        store.setInt("zap_cache_ts_v2_${me.publicKeyHex}", time.millis - 6 * 3_600_000L)
        assertEquals(cached, service().analyzeZapActivity(me.publicKeyHex))
        assertTrue(relays.connector.connects.isEmpty())

        store.setInt("zap_cache_ts_v2_${me.publicKeyHex}", time.millis - 6 * 3_600_000L - 1)
        assertEquals(ZapStats.EMPTY.sentCount, service().analyzeZapActivity(me.publicKeyHex).sentCount)
        assertTrue(relays.connector.connects.isNotEmpty())
    }

    @Test
    fun `useCache false bypasses a fresh cache and rewrites it (R18)`() = runBlocking<Unit> {
        val cached = ZapStats(7, 7, 7, 7, 7, true)
        store.setString("zap_stats_cache_v2_${me.publicKeyHex}", DartJson.encode(cached.toJson()))
        store.setInt("zap_cache_ts_v2_${me.publicKeyHex}", time.millis - 1_000)
        relays.add("wss://r1", receiptWithSender(providerMe, sender = bob, recipient = me.publicKeyHex, createdAt = now - day))
        time.millis += 5_000
        val fresh = service().analyzeZapActivity(me.publicKeyHex, useCache = false)
        assertTrue(relays.connector.connects.isNotEmpty())
        assertEquals(1, fresh.receivedCount)
        assertEquals(DartJson.encode(fresh.toJson()), store.getString("zap_stats_cache_v2_${me.publicKeyHex}"))
        assertEquals(time.millis, store.getInt("zap_cache_ts_v2_${me.publicKeyHex}"))
    }

    @Test
    fun `a self-zap counts as sent and received`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(providerMe, sender = me, recipient = me.publicKeyHex, createdAt = now - day))
        val stats = service().analyzeZapActivity(me.publicKeyHex, useCache = false)
        assertEquals(1, stats.sentCount)
        assertEquals(1, stats.receivedCount)
    }

    @Test
    fun `unreadable relay config gives EMPTY instead of throwing`() = runBlocking<Unit> {
        val broken = object : RelayConfig by RelayConfig(store, relays.connector) {
            override suspend fun getActiveRelays(): List<String> = throw java.io.IOException("prefs")
        }
        val service = DefaultZapVerificationService(
            store, broken, RelayClient(relays.connector), providers, FakeIdentity(keyPair = me), time, log, testDispatcher,
        )
        assertEquals(ZapStats.EMPTY, service.getMyStats())
        assertEquals(ZapStats.EMPTY, service.analyzeZapActivity(alice.publicKeyHex, useCache = false))
    }

    @Test
    fun `failing cache write still returns the computed stats`() = runBlocking<Unit> {
        relays.add("wss://r1", receiptWithSender(providerA, sender = me, recipient = alice.publicKeyHex, createdAt = now - 5 * day))
        val failingWrites = object : KeyValueStore by store {
            override suspend fun setString(key: String, value: String): Boolean = throw java.io.IOException("disk full")
            override suspend fun setInt(key: String, value: Long): Boolean = throw java.io.IOException("disk full")
        }
        val service = DefaultZapVerificationService(
            failingWrites, RelayConfig(store, relays.connector), RelayClient(relays.connector), providers, FakeIdentity(keyPair = me), time, log,
            testDispatcher,
        )
        val stats = service.getMyStats()
        assertEquals(1, stats.sentCount)
        assertEquals(null, store.getString("zap_stats_cache_v2_${me.publicKeyHex}"))
    }
}
