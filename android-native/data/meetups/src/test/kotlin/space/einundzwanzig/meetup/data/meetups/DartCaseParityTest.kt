package space.einundzwanzig.meetup.data.meetups

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.meetups.internal.MeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.dartLower
import space.einundzwanzig.meetup.data.meetups.internal.dartUpper

/**
 * Pins `dartLower`/`dartUpper` and [MeetupServiceImpl.meetupSlug] to the
 * canonical `DartString` table and `MeetupKey.slug` of `:data:badges` (spec 00 item 12), so
 * `bySlug` finds every badge slug.
 */
class DartCaseParityTest {

    @Test
    fun `meetupSlug equals MeetupKey slug`() {
        val cities = listOf(
            "München", " Hamburg ", "Bad Tölz", "SANKT PÖLTEN", "Zürich", "ΟΔΟΣ", "İstanbul",
            "Straße", "Łódź", "Ærøskøbing", "Sankt Gallen", "﻿Wien\u0085", "",
        )
        for (c in cities) assertEquals(c, MeetupKey.slug(c), MeetupServiceImpl.meetupSlug(c))
    }

    @Test
    fun `dartLower and dartUpper match the Dart case table for every code point`() {
        val mismatches = mutableListOf<String>()
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            val s = String(Character.toChars(cp))
            if (s.dartLower() != DartString.lower(s)) mismatches += "lower U+%04X".format(cp)
            if (s.dartUpper() != DartString.upper(s)) mismatches += "upper U+%04X".format(cp)
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
