package space.einundzwanzig.meetup.data.proofs

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.data.proofs.zap.ZapActivity
import space.einundzwanzig.meetup.data.proofs.zap.ZapReceipt
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService

/** Port of `test/zap_sent_filter_test.dart` (filter builder) and spec 06 §10.6 stats vectors. */
class ZapStatsTest {

    // ---- zap_sent_filter_test.dart ----

    private val me = "aa"
    private val other = "bb"
    private val since = 1_700_000_000L

    private fun receiptEvent(tags: List<List<String>>): JsonObject =
        jsonObjectOf("kind" to 9735, "pubkey" to "provider", "created_at" to since + 10, "tags" to tags)

    @Test
    fun `Gesendete Zaps fragen #P, empfangene #p`() {
        val sent = ZapVerificationService.receiptFilter(me, since, sent = true)
        val received = ZapVerificationService.receiptFilter(me, since, sent = false)
        assertEquals(jsonObjectOf("x" to listOf(me))["x"], sent["#P"])
        assertFalse(sent.containsKey("#p"))
        assertEquals(jsonObjectOf("x" to listOf(me))["x"], received["#p"])
        assertFalse(received.containsKey("#P"))
    }

    @Test
    fun `Empfangsfrage behaelt Belege ohne P-Tag`() {
        val event = receiptEvent(listOf(listOf("p", me)))
        assertTrue(EventGuard.answersFilter(event, ZapVerificationService.receiptFilter(me, since, sent = false)))
        assertFalse(EventGuard.answersFilter(event, ZapVerificationService.receiptFilter(me, since, sent = true)))
    }

    @Test
    fun `Gesendet-Filter trifft den Absender, nicht den Empfaenger`() {
        val event = receiptEvent(listOf(listOf("p", other), listOf("P", me)))
        assertTrue(EventGuard.answersFilter(event, ZapVerificationService.receiptFilter(me, since, sent = true)))
        assertFalse(EventGuard.answersFilter(event, ZapVerificationService.receiptFilter(me, since, sent = false)))
    }

    @Test
    fun `receiptFilter key order is kinds since limit tag`() {
        assertEquals(
            """{"kinds":[9735],"since":1700000000,"limit":100,"#P":["aa"]}""",
            DartJson.encode(ZapVerificationService.receiptFilter(me, since, sent = true)),
        )
    }

    // ---- lightningScore ----

    private fun stats(sent: Int, recv: Int, uniqRec: Int, months: Int, proof: Boolean) =
        ZapStats(sent, recv, uniqRec, 0, months, proof)

    @Test
    fun `lightningScore vectors`() {
        assertEquals(0.0, ZapStats.EMPTY.lightningScore, 0.0)
        // Spec 06 §10.6 lists 0.5681818181818182 (decimal 0.5 + 0.75/11); IEEE doubles (Dart and JVM
        // alike, same operation order) give ...181.
        assertEquals(0.5681818181818181, stats(1, 0, 1, 0, true).lightningScore, 0.0)
        assertEquals(1.75, stats(10, 10, 3, 3, true).lightningScore, 0.0)
        assertEquals(2.35, stats(90, 90, 50, 24, true).lightningScore, 1e-12)
        assertEquals(0.75, stats(0, 5, 0, 0, true).lightningScore, 0.0)
        assertEquals(0.9230769230769231, stats(3, 0, 3, 2, true).lightningScore, 0.0)
    }

    @Test
    fun `activeMonths vectors`() {
        assertEquals(0, ZapStats.activeMonthsOf(0))
        assertEquals(1, ZapStats.activeMonthsOf(2_592_000))
        assertEquals(2, ZapStats.activeMonthsOf(2_592_001))
        assertEquals(24, ZapStats.activeMonthsOf(100L * 2_592_000))
    }

    @Test
    fun `activity label vectors`() {
        fun act(total: Int) = ZapStats(total, 0, 0, 0, 0, total > 0).activity
        assertEquals(ZapActivity.NONE, act(0))
        assertEquals(ZapActivity.LITTLE, act(4))
        assertEquals(ZapActivity.REGULAR, act(5))
        assertEquals(ZapActivity.REGULAR, act(19))
        assertEquals(ZapActivity.VERY_ACTIVE, act(20))
        assertEquals("Keine Zap-Aktivität", ZapStats.EMPTY.activityLabel)
    }

    // ---- json ----

    @Test
    fun `toJson key order and round trip`() {
        val s = ZapStats(3, 4, 2, 1, 5, true)
        assertEquals(
            """{"sent":3,"received":4,"unique_recipients":2,"unique_senders":1,"active_months":5,"lightning_proof":true}""",
            DartJson.encode(s.toJson()),
        )
        assertEquals(s, ZapStats.fromJson(s.toJson()))
    }

    @Test
    fun `fromJson is lenient for integral doubles and wrong types`() {
        val json = DartJson.decode("""{"sent":3.0,"received":"x","unique_recipients":null,"active_months":2,"lightning_proof":"true"}""") as JsonObject
        assertEquals(ZapStats(3, 0, 0, 0, 2, false), ZapStats.fromJson(json))
    }

    // ---- computeStats ----

    private fun zap(id: String, sender: String, recipient: String, at: Long, pk: String = "me") =
        ZapReceipt(id, sender, recipient, at, isSent = sender == pk, isReceived = recipient == pk, hasBolt11 = true, receiptPubkey = "prov")

    @Test
    fun `compute counts sent, received, unique counterparts and months`() {
        val received = listOf(
            zap("r1", "alice", "me", 1_000_000_000),
            zap("r2", "bob", "me", 1_000_000_000 + 2_592_000 * 2),
            zap("r3", "", "me", 1_000_000_100),
        )
        val sent = listOf(
            zap("s1", "me", "carol", 1_000_000_500),
            zap("s2", "me", "carol", 1_000_000_600),
            zap("self", "me", "me", 1_000_000_700),
            zap("r1", "alice", "me", 1_000_000_000), // duplicate id
        )
        val s = ZapStats.compute(received, sent)
        assertEquals(3, s.sentCount) // s1, s2, self
        assertEquals(4, s.receivedCount) // r1, r2, r3, self
        assertEquals(2, s.uniqueRecipientCount) // carol, me
        assertEquals(3, s.uniqueSenderCount) // alice, bob, me ("" ignored)
        assertEquals(2, s.activeMonths)
        assertTrue(s.hasLightningProof)
        assertEquals(ZapStats.EMPTY, ZapStats.compute(emptyList(), emptyList()))
    }
}
