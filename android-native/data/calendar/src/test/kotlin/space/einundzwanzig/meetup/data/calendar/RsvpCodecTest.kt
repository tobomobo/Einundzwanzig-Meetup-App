package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.calendar.nostr.RsvpCodec

class RsvpCodecTest {

    private val hex = "ab".repeat(32)

    @Test
    fun `d-tag vectors`() {
        assertEquals("rsvp-31923-$hex-zitadelle-2026", RsvpCodec.dTagFor("31923:$hex:Zitadelle 2026!"))
        assertEquals("rsvp-31922-$hex-test", RsvpCodec.dTagFor("31922:$hex:ä-test"))
        assertEquals("rsvp-", RsvpCodec.dTagFor("::"))
        // Locale-independent: dotted capital I lowercases to i (Dart), not i + U+0307.
        assertEquals("rsvp-i", RsvpCodec.dTagFor("İ"))
    }

    @Test
    fun `tags with and without author`() {
        val addr = "31923:$hex:d"
        assertEquals(
            listOf(listOf("a", addr), listOf("d", "rsvp-31923-$hex-d"), listOf("status", "accepted"), listOf("p", hex)),
            RsvpCodec.tags(addr, hex, RsvpStatus.ACCEPTED),
        )
        assertEquals(
            listOf(listOf("a", addr), listOf("d", "rsvp-31923-$hex-d"), listOf("status", "declined")),
            RsvpCodec.tags(addr, "", RsvpStatus.DECLINED),
        )
    }

    @Test
    fun `parse takes the last a and status tags and needs an int created_at`() {
        fun ev(createdAt: Any, vararg tags: List<String>) = jsonObjectOf("created_at" to createdAt, "tags" to tags.toList().toJsonElement())
        assertEquals(
            RsvpCodec.Entry("second", RsvpStatus.TENTATIVE, 5),
            RsvpCodec.parse(ev(5, listOf("a", "first"), listOf("status", "accepted"), listOf("a", "second"), listOf("status", "tentative"))),
        )
        assertNull(RsvpCodec.parse(ev("5", listOf("a", "x"), listOf("status", "accepted"))))
        assertNull(RsvpCodec.parse(ev(5, listOf("a", ""), listOf("status", "accepted"))))
        assertNull(RsvpCodec.parse(ev(5, listOf("a", "x"), listOf("status", "Accepted"))))
        assertNull(RsvpCodec.parse(ev(5, listOf("a", "x"))))
    }

    @Test
    fun `newest per address - strictly greater wins`() {
        val out = RsvpCodec.newestPerAddress(
            listOf(
                RsvpCodec.Entry("x", RsvpStatus.ACCEPTED, 10),
                RsvpCodec.Entry("x", RsvpStatus.DECLINED, 10),
                RsvpCodec.Entry("y", RsvpStatus.DECLINED, 1),
                RsvpCodec.Entry("y", RsvpStatus.ACCEPTED, 2),
            ),
        )
        assertEquals(mapOf("x" to RsvpStatus.ACCEPTED, "y" to RsvpStatus.ACCEPTED), out)
    }
}
