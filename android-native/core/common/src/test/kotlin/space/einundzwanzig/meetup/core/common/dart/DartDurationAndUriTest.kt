package space.einundzwanzig.meetup.core.common.dart

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors computed with Dart 3.13.5. */
class DartDurationAndUriTest {

    @Test
    fun `duration getters truncate towards zero like Dart`() {
        // micros, inDays, inHours, inMinutes, inSeconds, inMilliseconds, toString
        val cases = listOf(
            listOf(0L, 0L, 0L, 0L, 0L, 0L) to "0:00:00.000000",
            listOf(86399999999L, 0L, 23L, 1439L, 86399L, 86399999L) to "23:59:59.999999",
            listOf(86400000000L, 1L, 24L, 1440L, 86400L, 86400000L) to "24:00:00.000000",
            listOf(-1L, 0L, 0L, 0L, 0L, 0L) to "-0:00:00.000001",
            listOf(-86400000000L, -1L, -24L, -1440L, -86400L, -86400000L) to "-24:00:00.000000",
            listOf(-86400000001L, -1L, -24L, -1440L, -86400L, -86400000L) to "-24:00:00.000001",
            listOf(518400000005L, 6L, 144L, 8640L, 518400L, 518400000L) to "144:00:00.000005",
        )
        for ((v, text) in cases) {
            val d = DartDuration(v[0])
            assertEquals(text, v[1], d.inDays)
            assertEquals(text, v[2], d.inHours)
            assertEquals(text, v[3], d.inMinutes)
            assertEquals(text, v[4], d.inSeconds)
            assertEquals(text, v[5], d.inMilliseconds)
            assertEquals(text, text, d.toString())
        }
        assertEquals("-1:00:00.000000", DartDuration.of(days = 1, hours = -25).toString())
    }

    @Test
    fun `encodeQueryComponent matches Dart`() {
        val cases = listOf(
            "\"a b~*'\"" to "%22a+b~%2A%27%22",
            "abc-_.~!*'()" to "abc-_.~%21%2A%27%28%29",
            "ä€😀" to "%C3%A4%E2%82%AC%F0%9F%98%80",
            " +&=?/#%:@[]" to "+%2B%26%3D%3F%2F%23%25%3A%40%5B%5D",
            "lone\uD800x" to "lone%EF%BF%BDx",
            "tab\t\nnl" to "tab%09%0Anl",
            "A-Z_a.z~0" to "A-Z_a.z~0",
            "\$,;" to "%24%2C%3B",
            "" to "",
        )
        for ((input, expected) in cases) assertEquals(input, expected, DartUri.encodeQueryComponent(input))
    }

    @Test
    fun `encodeComponent matches Dart`() {
        val cases = listOf(
            "\"a b~*'\"" to "%22a%20b~*'%22",
            "abc-_.~!*'()" to "abc-_.~!*'()",
            "ä€😀" to "%C3%A4%E2%82%AC%F0%9F%98%80",
            " +&=?/#%:@[]" to "%20%2B%26%3D%3F%2F%23%25%3A%40%5B%5D",
            "lone\uD800x" to "lone%EF%BF%BDx",
            "tab\t\nnl" to "tab%09%0Anl",
            "\$,;" to "%24%2C%3B",
        )
        for ((input, expected) in cases) assertEquals(input, expected, DartUri.encodeComponent(input))
    }

    @Test
    fun `SatoshiDuell query value from spec 12`() {
        // q = urlEncodeQueryComponent('"' + username.lowercase().replace('"','') + '"')
        assertEquals("%22satoshi+nakamoto%22", DartUri.encodeQueryComponent("\"satoshi nakamoto\""))
    }
}
