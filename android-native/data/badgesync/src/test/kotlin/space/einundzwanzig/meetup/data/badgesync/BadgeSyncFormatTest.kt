package space.einundzwanzig.meetup.data.badgesync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.DartDuration
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * Port of `test/badge_sync_format_test.dart` (all 9 cases, same inputs) plus the spec 08 §10.2
 * sync vectors of `08-backup-sync-vectors.json`.
 */
class BadgeSyncFormatTest {

    private fun badge(
        id: String,
        date: DartDateTime,
        claimTimestamp: Long = 0,
        claimed: Boolean = true,
        presenceVerified: Boolean = true,
        long: Boolean = false,
    ): MeetupBadge {
        // long makes the badge big enough for several chunks.
        val filler = if (long) "x".repeat(800) else ""
        return MeetupBadge(
            id = id,
            meetupName = "M $id",
            date = date,
            iconPath = "",
            sig = filler,
            sigContent = filler,
            claimSig = if (claimed) "sig$id" else "",
            claimPubkey = if (claimed) "pk" else "",
            claimTimestamp = claimTimestamp,
            presenceVerified = presenceVerified,
        )
    }

    // measure like NIP-44: the plaintext length decides the payload.
    private val measure: (String) -> Int = { it.length }

    private fun day(y: Int, m: Int, d: Int, h: Int = 0) = DartDateTime.local(y, m, d, h)

    @Test
    fun `Sortierung nach date, bei Gleichstand nach id`() {
        val list = BadgeSyncFormat.sortBadges(
            listOf(badge("b", day(2026, 5, 2)), badge("a", day(2026, 5, 2)), badge("c", day(2026, 5, 1))),
        )
        assertEquals(listOf("c", "a", "b"), list.map { it.id })
    }

    @Test
    fun `Huelle macht eine Rundreise`() {
        val badges = listOf(
            badge("x", day(2026, 1, 1), claimTimestamp = 100),
            badge("y", day(2026, 1, 2), claimTimestamp = 90),
        )
        val plain = BadgeSyncFormat.envelopeJson(badges, index = 1, total = 2, generation = 42)
        val parsed = BadgeSyncFormat.parseEnvelope(plain)
        assertEquals(1, parsed.index)
        assertEquals(2, parsed.total)
        assertEquals(42L, parsed.generation)
        assertEquals(listOf("x", "y"), parsed.badges.map { it.id })
        assertEquals(100L, parsed.badges.first().claimTimestamp)
    }

    @Test
    fun `Chunks teilen an der Grenze und bleiben unter ihr`() {
        val start = day(2026, 1, 1)
        val badges = List(40) { i -> badge("b$i", start.add(DartDuration.of(days = i.toLong())), long = true) }
        val chunks = BadgeSyncFormat.chunkBadges(badges, measure)
        assertTrue(chunks.size > 1)
        chunks.forEachIndexed { i, chunk ->
            val plain = BadgeSyncFormat.envelopeJson(chunk, i, chunks.size, 0)
            assertTrue(plain.length <= BadgeSyncFormat.CHUNK_PAYLOAD_CHARS)
        }
        // No badge is lost.
        assertEquals(badges.size, chunks.flatten().size)
    }

    @Test
    fun `Determinismus - dieselbe Menge in anderer Reihenfolge ergibt dieselben Chunks`() {
        val a = listOf(badge("1", day(2026, 1, 1)), badge("2", day(2026, 1, 2)), badge("3", day(2026, 1, 3)))
        val b = a.reversed()
        assertEquals(BadgeSyncFormat.plaintexts(a, 7, measure), BadgeSyncFormat.plaintexts(b, 7, measure))
    }

    @Test
    fun `Vereinigung - fernes Badge ohne lokalen Partner bleibt`() {
        val merged = BadgeSyncFormat.merge(listOf(badge("local", day(2026, 1, 1))), listOf(badge("remote", day(2026, 1, 2))))
        assertEquals(listOf("local", "remote"), merged.map { it.id })
    }

    @Test
    fun `Vereinigung - bei gleicher Id gewinnt der Claim`() {
        val withClaim = badge("same", day(2026, 1, 1), claimed = true)
        val without = badge("same", day(2026, 1, 1), claimed = false)
        assertTrue(BadgeSyncFormat.merge(listOf(without), listOf(withClaim)).single().claimSig.isNotEmpty())
        // The other way round: the local one with claim beats the remote one without.
        assertTrue(BadgeSyncFormat.merge(listOf(withClaim), listOf(without)).single().claimSig.isNotEmpty())
    }

    @Test
    fun `Vereinigung - bei gleichem Claim gewinnt presenceVerified, dann lokal`() {
        val local = badge("same", day(2026, 1, 1), presenceVerified = false)
        val remote = badge("same", day(2026, 1, 1), presenceVerified = true)
        assertTrue(BadgeSyncFormat.merge(listOf(local), listOf(remote)).single().presenceVerified)
        val localV = badge("same", day(2026, 1, 1), presenceVerified = true)
        val remoteV = badge("same", day(2026, 1, 1), presenceVerified = true)
        assertSame(localV, BadgeSyncFormat.merge(listOf(localV), listOf(remoteV)).single())
    }

    @Test
    fun `chunkHash ist stabil am Klartext`() {
        val p = BadgeSyncFormat.envelopeJson(listOf(badge("x", day(2026, 1, 1))), 0, 1, 3)
        assertEquals(BadgeSyncFormat.chunkHash(p), BadgeSyncFormat.chunkHash(p))
        val p4 = BadgeSyncFormat.envelopeJson(listOf(badge("x", day(2026, 1, 1))), 0, 1, 4)
        assertNotEquals(BadgeSyncFormat.chunkHash(p), BadgeSyncFormat.chunkHash(p4))
        // contentHash ignores the generation (native addition, the reason it exists).
        assertEquals(BadgeSyncFormat.contentHash(p), BadgeSyncFormat.contentHash(p4))
    }

    @Test
    fun `Vereinigung je Veranstaltung - zwei Ids, ein Abend, ein Badge`() {
        fun scan(id: String, hour: Int, claimed: Boolean = true, organizer: Boolean = false) = MeetupBadge(
            id = id,
            meetupName = "Koblenz",
            meetupEventId = "koblenz-2026-10-11",
            date = day(2026, 10, 11, hour),
            iconPath = "",
            claimSig = if (claimed) "sig" else "",
            claimPubkey = if (claimed) "pk" else "",
            isOrganizer = organizer,
        )
        // Both claimed: the first scan stays, seen from both sides.
        val first = scan("koblenz-2026-10-11", 19)
        val second = scan("koblenz-2026-10-11-2", 20)
        assertEquals(listOf("koblenz-2026-10-11"), BadgeSyncFormat.merge(listOf(first), listOf(second)).map { it.id })
        assertEquals(listOf("koblenz-2026-10-11"), BadgeSyncFormat.merge(listOf(second), listOf(first)).map { it.id })
        // Only the second carries the claim: it stays.
        val unclaimed = scan("koblenz-2026-10-11", 19, claimed = false)
        assertEquals(listOf("koblenz-2026-10-11-2"), BadgeSyncFormat.merge(listOf(unclaimed), listOf(second)).map { it.id })
        // The organizer marker of the same evening is not a double scan.
        val marker = scan("org-koblenz-2026-10-11", 18, organizer = true)
        assertEquals(2, BadgeSyncFormat.merge(listOf(first, marker), listOf(second)).size)
    }

    // ------------------------------------------------------------------ spec 08 §10.2 vectors

    @Test
    fun `fixture envelope is reproduced byte for byte and hashes match`() {
        val sync = SyncFixtures.sync
        val plain = sync.getValue("envelope_plaintext").jsonPrimitive.content
        val env = BadgeSyncFormat.parseEnvelope(plain)
        assertEquals(1, env.badges.size)
        assertEquals(plain, BadgeSyncFormat.envelopeJson(env.badges, env.index, env.total, env.generation))
        assertEquals(1760961600L, env.generation)
        assertEquals(sync.getValue("chunk_hash_sha256_of_envelope").jsonPrimitive.content, BadgeSyncFormat.chunkHash(plain))
        assertEquals(sync.getValue("content_hash").jsonPrimitive.content, BadgeSyncFormat.contentHash(plain))
        assertEquals(
            sync.getValue("content_hash").jsonPrimitive.content,
            BadgeSyncFormat.chunkHash(sync.getValue("content_hash_input").jsonPrimitive.content),
        )
        assertEquals(sync.getValue("d_tags").jsonArray.map { it.jsonPrimitive.content }, BadgeSyncFormat.D_TAGS)
    }

    @Test
    fun `payloadCharsForGzip table`() {
        val table = SyncFixtures.all.getValue("payloadCharsForGzip").jsonArray
        assertTrue(table.size >= 12)
        for (row in table) {
            val o = row.jsonObject
            assertEquals(
                "gzip_bytes ${o.getValue("gzip_bytes")}",
                o.getValue("payload_chars").jsonPrimitive.int,
                BadgeSyncFormat.payloadCharsForGzip(o.getValue("gzip_bytes").jsonPrimitive.int),
            )
        }
        // §10.2 list.
        mapOf(0 to 132, 32 to 132, 33 to 172, 65 to 216, 100 to 260, 256 to 428, 257 to 516, 1000 to 1452,
            1025 to 1796, 5000 to 6916, 20000 to 27396, 22000 to 32856).forEach { (bytes, chars) ->
            assertEquals(chars, BadgeSyncFormat.payloadCharsForGzip(bytes))
        }
    }

    @Test
    fun `parseEnvelope rejects like Dart and drops defective badges`() {
        assertThrows(IllegalArgumentException::class.java) { BadgeSyncFormat.parseEnvelope("[]") }
        assertThrows(IllegalArgumentException::class.java) { BadgeSyncFormat.parseEnvelope("{nope") }
        val v2 = assertThrows(BadgeSyncFormatException::class.java) { BadgeSyncFormat.parseEnvelope("""{"v":2,"badges":[]}""") }
        assertEquals("Hülle v2 wird nicht unterstützt", v2.message)
        assertThrows(BadgeSyncFormatException::class.java) { BadgeSyncFormat.parseEnvelope("""{"v":1}""") }
        assertThrows(BadgeSyncFormatException::class.java) { BadgeSyncFormat.parseEnvelope("""{"v":1,"i":"0","badges":[]}""") }
        // Dart: 1.0 == 1.
        assertEquals(0, BadgeSyncFormat.parseEnvelope("""{"v":1.0,"badges":[]}""").badges.size)

        val good = badge("ok", day(2026, 1, 1)).toJsonString()
        val env = BadgeSyncFormat.parseEnvelope(
            """{"v":1,"gen":5.9,"badges":[$good,{"id":"kaputt"},{"id":"x","meetupName":"M","date":"2026-01-01","iconPath":"","blockHeight":1.5},"text",null]}""",
        )
        assertEquals(listOf("ok"), env.badges.map { it.id })
        assertEquals(0, env.index)
        assertEquals(1, env.total)
        assertEquals(5L, env.generation)
    }

    @Test
    fun `envelope key order and integer types`() {
        val plain = BadgeSyncFormat.envelopeJson(emptyList(), 2, 3, 1L shl 40)
        assertEquals("""{"v":1,"i":2,"n":3,"gen":1099511627776,"badges":[]}""", plain)
        assertTrue(BadgeSyncFormat.chunkBadges(emptyList(), measure).isEmpty())
        assertEquals("badges-15", BadgeSyncFormat.dTag(15))
        assertEquals(JsonPrimitive(1), kotlinx.serialization.json.Json.parseToJsonElement(plain).jsonObject["v"])
    }

    @Test
    fun `a single oversized badge gets a chunk of its own`() {
        val big = MeetupBadge(id = "big", meetupName = "B", date = day(2026, 1, 2), iconPath = "", sig = "y".repeat(31_000))
        val small = badge("a", day(2026, 1, 1))
        val chunks = BadgeSyncFormat.chunkBadges(listOf(big, small), measure)
        assertEquals(listOf(listOf("a"), listOf("big")), chunks.map { c -> c.map { it.id } })
    }
}
