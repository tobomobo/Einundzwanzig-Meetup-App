package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.proofs.internal.DefaultZapVerificationService
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats

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

    private val providers = object : ZapProviderResolver {
        val map = mapOf(alice.publicKeyHex to providerA.publicKeyHex, me.publicKeyHex to providerMe.publicKeyHex)
        override suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String) = false
        override suspend fun providerPubkeyFor(recipientPubkey: String): String? = map[recipientPubkey]
        override suspend fun providerPubkeyFromLnurlp(url: String): String? = null
    }

    private fun service() = DefaultZapVerificationService(
        store, RelayConfig(store, relays.connector), RelayClient(relays.connector), providers, FakeIdentity(keyPair = me), time, log, testDispatcher,
    )

    @Test
    fun `counts only provider-issued receipts and caches the result`() = runBlocking<Unit> {
        val day = 86_400L
        val now = 1_760_000_000L
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
}
