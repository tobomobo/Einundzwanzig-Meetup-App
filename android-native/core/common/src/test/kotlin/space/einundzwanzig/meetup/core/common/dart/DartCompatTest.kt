package space.einundzwanzig.meetup.core.common.dart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vectors computed with Dart 3.13.5 (VM). */
class DartCompatTest {

    @Test
    fun `double toString matches Dart for hand-picked values`() {
        val cases = listOf(
            0.0 to "0.0",
            -0.0 to "-0.0",
            1.0 to "1.0",
            100.0 to "100.0",
            0.1 to "0.1",
            0.0001 to "0.0001",
            0.000001 to "0.000001",
            1e-7 to "1e-7",
            1.5e-7 to "1.5e-7",
            48.2 to "48.2",
            1e20 to "100000000000000000000.0",
            1e21 to "1e+21",
            1.5e21 to "1.5e+21",
            123456789012345680000.0 to "123456789012345680000.0",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            Double.MIN_VALUE to "5e-324",
            2.2250738585072014e-308 to "2.2250738585072014e-308",
            0.30000000000000004 to "0.30000000000000004",
            1.0 / 3 to "0.3333333333333333",
            2.0 / 3 to "0.6666666666666666",
            123.456 to "123.456",
            -123.456 to "-123.456",
            1e15 to "1000000000000000.0",
            1e16 to "10000000000000000.0",
            1e17 to "100000000000000000.0",
            9007199254740993.0 to "9007199254740992.0",
            2.82879384806159e17 to "282879384806159000.0",
            1.0e23 to "1e+23",
            2e23 to "2e+23",
            4.35 to "4.35",
            0.5 to "0.5",
            1.2345e-5 to "0.000012345",
            -1e-7 to "-1e-7",
            1e100 to "1e+100",
            1.25e-10 to "1.25e-10",
            8.41e21 to "8.41e+21",
            5.0e-7 to "5e-7",
            1.18575755e-316 to "1.18575755e-316",
            3.14159 to "3.14159",
            2.5 to "2.5",
            1e22 to "1e+22",
            6.02214076e23 to "6.02214076e+23",
            Double.NaN to "NaN",
            Double.POSITIVE_INFINITY to "Infinity",
            Double.NEGATIVE_INFINITY to "-Infinity",
        )
        for ((value, expected) in cases) assertEquals("toString($value)", expected, value.toDartString())
    }

    @Test
    fun `double toString matches Dart for 4298 generated values incl all powers of two`() {
        val rows = vectors("double_tostring.tsv")
        assertEquals(4298, rows.size)
        for ((bits, expected) in rows) {
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16))
            assertEquals("bits $bits", expected, value.toDartString())
        }
    }

    @Test
    fun `toStringAsFixed matches the spec fixture`() {
        // spec/fixtures/04-trust-reputation-vectors.json "toStringAsFixed1"
        val cases = listOf(
            2.75 to "2.8", 0.25 to "0.3", 0.35 to "0.3", 12.25 to "12.3", 4.385348983086764 to "4.4",
            0.05 to "0.1", 1.45 to "1.4", 0.0 to "0.0", 10.0 to "10.0",
        )
        for ((value, expected) in cases) assertEquals("$value", expected, value.toStringAsFixed(1))
    }

    @Test
    fun `toStringAsFixed matches Dart for edge cases`() {
        val cases = listOf(
            Triple(-0.0, 1, "-0.0"),
            Triple(-0.0, 0, "-0"),
            Triple(-0.001, 2, "-0.00"),
            Triple(-0.005, 2, "-0.01"),
            Triple(-0.5, 0, "-1"),
            Triple(0.5, 0, "1"),
            Triple(1.5, 0, "2"),
            Triple(2.5, 0, "3"),
            Triple(-1.5, 0, "-2"),
            Triple(-2.5, 0, "-3"),
            Triple(0.125, 2, "0.13"),
            Triple(0.375, 2, "0.38"),
            Triple(1e21, 2, "1e+21"),
            Triple(-1e21, 1, "-1e+21"),
            Triple(1e20, 2, "100000000000000000000.00"),
            Triple(123.456, 0, "123"),
            Triple(123.456, 5, "123.45600"),
            Triple(1.005, 2, "1.00"),
            Triple(-1.005, 2, "-1.00"),
            Triple(Double.MIN_VALUE, 3, "0.000"),
            Triple(999.9999, 2, "1000.00"),
            Triple(0.045, 2, "0.04"),
            Triple(1e-10, 20, "0.00000000010000000000"),
            Triple(12345.6789, 20, "12345.67890000000079453457"),
            Triple(-0.04, 1, "-0.0"),
            Triple(-0.06, 1, "-0.1"),
            Triple(Double.MAX_VALUE, 2, "1.7976931348623157e+308"),
            Triple(Double.NaN, 2, "NaN"),
            Triple(Double.POSITIVE_INFINITY, 2, "Infinity"),
            Triple(Double.NEGATIVE_INFINITY, 2, "-Infinity"),
        )
        for ((value, digits, expected) in cases) assertEquals("$value/$digits", expected, value.toStringAsFixed(digits))
    }

    @Test
    fun `toStringAsFixed matches Dart for 1500 generated values`() {
        val rows = vectors("double_tostringasfixed.tsv")
        assertEquals(1500, rows.size)
        for (row in rows) {
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(row[0], 16))
            assertEquals("bits ${row[0]} digits ${row[1]}", row[2], value.toStringAsFixed(row[1].toInt()))
        }
    }

    @Test
    fun `int toStringAsFixed converts to double first`() {
        assertEquals("0.00", 0.toStringAsFixed(2))
        assertEquals("5.00", 5.toStringAsFixed(2))
        assertEquals("-5.00", (-5).toStringAsFixed(2))
        assertEquals("1779.00", 1779L.toStringAsFixed(2))
        assertEquals("9007199254740992.00", 9007199254740993L.toStringAsFixed(2))
    }

    @Test
    fun `toStringAsFixed rejects digits outside 0 to 20`() {
        assertThrows(IllegalArgumentException::class.java) { 1.0.toStringAsFixed(21) }
        assertThrows(IllegalArgumentException::class.java) { 1.0.toStringAsFixed(-1) }
    }

    @Test
    fun `round is half away from zero and saturates like Dart`() {
        val cases = listOf(
            0.5 to 1L, 1.5 to 2L, 2.5 to 3L, -0.5 to -1L, -1.5 to -2L, -2.5 to -3L,
            0.49999999999999994 to 0L, -0.49999999999999994 to 0L,
            4503599627370495.5 to 4503599627370496L,
            1779.4 to 1779L, 1779.5 to 1780L, -1779.5 to -1780L,
            1e18 to 1000000000000000000L,
            9.3e18 to Long.MAX_VALUE, -9.3e18 to Long.MIN_VALUE, 1e300 to Long.MAX_VALUE,
            // Converter: sats per unit = round(100_000_000 / price) (spec 12 §7)
            100_000_000 / 56200.0 to 1779L,
        )
        for ((value, expected) in cases) assertEquals("round($value)", expected, value.dartRound())
    }

    @Test
    fun `round matches Dart for 600 generated values`() {
        val rows = vectors("double_round.tsv")
        assertEquals(600, rows.size)
        for ((bits, expected) in rows) {
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16))
            assertEquals("bits $bits", expected.toLong(), value.dartRound())
        }
    }

    @Test
    fun `round and toInt throw for NaN and infinity`() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(UnsupportedOperationException::class.java) { v.dartRound() }
            assertThrows(UnsupportedOperationException::class.java) { v.dartToInt() }
        }
    }

    @Test
    fun `toInt truncates towards zero`() {
        assertEquals(1000L, 1000.9.dartToInt())
        assertEquals(-1000L, (-1000.9).dartToInt())
        assertEquals(Long.MAX_VALUE, 1e300.dartToInt())
    }

    @Test
    fun `dartTrim strips exactly the Dart whitespace set`() {
        assertEquals("a b", " \t\n\u000B\u000C\r a b \u0085        　﻿".dartTrim())
        // Kotlin's trim() would strip U+001C..U+001F; Dart keeps them.
        assertEquals("\u001Cx\u001F", "\u001Cx\u001F".dartTrim())
        assertEquals("", "   ".dartTrim())
        assertEquals("x", "x".dartTrim())
    }

    private fun vectors(name: String): List<List<String>> =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("dart-vectors/$name")) { name }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
}
