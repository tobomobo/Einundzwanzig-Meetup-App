package space.einundzwanzig.meetup.data.badges

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.badges.security.ParsedMeetupId
import space.einundzwanzig.meetup.data.badges.text.DartString

/** Spec 02 §8.14: every vector row plus the shipped case table hash. */
class DartStringTest {

    @Test
    fun `shipped case table is the Dart 3_13_5 table`() {
        val bytes = DartString.caseTableBytes()
        assertEquals("70a94653c2ccd1cc4bef72c531cfd65c0ba53688274b317887f6b0c5deeac138", Sha256.digest(bytes).toHex())
        assertEquals(2050, String(bytes, Charsets.US_ASCII).lines().count { it.isNotEmpty() })
    }

    @Test
    fun `lower and upper follow Dart`() {
        assertEquals("οδοσ", DartString.lower("ΟΔΟΣ"))
        assertEquals("istanbul", DartString.lower("İstanbul"))
        assertEquals(8, DartString.lower("İstanbul").length)
        assertEquals("STRAßE", DartString.upper("straße"))
        assertEquals("ß", DartString.lower("ẞ"))
        assertEquals("ა", DartString.upper("ა"))
        assertEquals("Ꭰ", DartString.lower("Ꭰ"))
        assertEquals("ꭰ", DartString.upper("ꭰ"))
        assertEquals("𐐨", DartString.lower("𐐀"))
        assertEquals("a\uD801", DartString.lower("A\uD801"))
        assertEquals("STRAßE MÜNCHEN", DartString.upper("straße münchen"))
    }

    @Test
    fun `trim follows Dart`() {
        assertEquals("x", DartString.trim("\u0085x"))
        assertEquals("x", DartString.trim("﻿x"))
        assertEquals("\u001Cx\u001F", DartString.trim("\u001Cx\u001F"))
        assertEquals("᠎x", DartString.trim("᠎x"))
        assertEquals("​x", DartString.trim("​x"))
    }

    @Test
    fun `meetup key rows`() {
        assertEquals("οδοσ-istanbul", MeetupKey.slug(" ΟΔΟΣ İstanbul "))
        assertEquals("wien", MeetupKey.slug("﻿Wien\u0085"))
        assertEquals("\u001cwien", MeetupKey.slug("\u001CWien"))
        assertEquals(ParsedMeetupId("wien", "AT"), MeetupKey.parse("wien-at\u0085"))
        assertEquals(ParsedMeetupId("wien", "AT"), MeetupKey.parse("﻿wien-at"))
        assertEquals("wien-at", MeetupKey.compose("wien", "﻿at"))
        assertEquals("wien", MeetupKey.compose("wien", "at\u001F"))
        assertEquals("Straße ꭰ ა Ǆ", MeetupKey.displayFromSlug("straße-ꭰ-ა-ǆ"))
    }

    @Test
    fun `identity key fallback uses Dart case mapping`() {
        val date = DartDateTime.local(2026, 3, 7, 12)
        fun key(name: String) = BadgeIdentity.identityKey(MeetupBadge(id = "x", meetupName = name, date = date, iconPath = ""))
        assertEquals("nd:οδοσ, gr|2026-03-07", key("ΟΔΟΣ, GR"))
        assertEquals("nd:izmir, tr|2026-03-07", key("\u0085İzmir, TR "))
    }

    @Test
    fun `event meetup id with unicode whitespace`() {
        assertEquals(
            "evt:31923:ab:d1:istanbul-meetup",
            BadgeSecurity.eventMeetupId("31923:ab:d1", "﻿İstanbul ΟΔΟΣ Meetup\u0085"),
        )
        assertEquals("evt:31923:ab:d1:straße-fest", BadgeSecurity.eventMeetupId("31923:ab:d1", "STRAẞE  Fest"))
        // U+00A0 and U+3000 are Dart regex whitespace (Java \s is not).
        assertEquals("evt:a:b-c-d", BadgeSecurity.eventMeetupId("a", "B C　D"))
    }
}
