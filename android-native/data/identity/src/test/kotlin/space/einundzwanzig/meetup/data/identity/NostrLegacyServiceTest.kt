package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.EventIds

class NostrLegacyServiceTest {
    @Test fun signatureUsesOriginalKind21000EventAndRejectsChangedContent() = runBlocking {
        val identity = TestIdentity()
        identity.keys.importPrivHex("0".repeat(63) + "3")
        val service = LegacyNostrService(identity.signing, identity.time)
        val pubkey = identity.signing.pubkeyHex()!!
        val content = "legacy\nWürzburg ₿"
        val id = EventIds.compute(pubkey, identity.time.nowSeconds(), 21000, emptyList(), content)
        val signature = service.sign(content)
        assertTrue(service.verify(content, signature, pubkey, id, identity.time.nowSeconds()))
        assertFalse(service.verify(content + "!", signature, pubkey, id, identity.time.nowSeconds()))
        assertFalse(service.verify(content, signature, pubkey, id, identity.time.nowSeconds() + 1))
    }

    @Test fun badgeCarriesOriginalEventFieldsAndDartContentOrder() = runBlocking {
        val identity = TestIdentity()
        identity.keys.importPrivHex("0".repeat(63) + "3")
        val service = LegacyNostrService(identity.signing, identity.time)
        val badge = service.signBadge("wien", "Wien", "2026-10-22", 972594)
        val expected = "{\"meetup_id\":\"wien\",\"meetup_name\":\"Wien\",\"date\":\"2026-10-22\",\"block_height\":972594,\"npub\":\"${identity.signing.npub()}\",\"timestamp\":${identity.time.nowMillis()}}"
        assertEquals(expected, badge["content"])
        assertTrue(service.verify(expected, badge["signature"] as String, badge["pubkey"] as String,
            badge["event_id"] as String, badge["created_at"] as Long,
            listOf(listOf("meetup", "wien"), listOf("block", "972594"))))
    }
}
