package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Port of test/pbkdf2_equality_test.dart (+ the extra 600k case of spec fixture 01/08). */
class Pbkdf2Test {
    private val salt = DartUtf8.encode("einundzwanzig-salt-32-bytes-lang!")

    private fun hex(password: String, iterations: Int, keyBytes: Int = 32) =
        Pbkdf2.hmacSha256(password, salt, iterations, keyBytes).toHex()

    @Test
    fun matchesReferenceForPasswordA() {
        val expected = mapOf(
            1 to "070375026d4efd90f55f8c596f9804c9d59e8b261c226982aea5b2076a95bb8a",
            2 to "f92df020004a77d443c83df50dd1899ad67b97d28b7d21b9481a78e5cb86885a",
            4096 to "730ecb5f9ff2ce548af5ef16149284b4b0e96be1a1c57f1192bafe12bae7b0c9",
            100000 to "22772726dbae2cda50c4f036ecce698ad1def22782eaab08d439742d531e0166",
        )
        for ((iterations, value) in expected) {
            assertEquals("$iterations Runden", value, hex("korrekt pferd batterie klammer", iterations))
        }
    }

    @Test
    fun sixHundredThousandRoundsAsUsedByTheApp() {
        assertEquals(600_000, Pbkdf2.BACKUP_ITERATIONS)
        assertEquals(
            "c32f5c29f83c8c3a2250ecd4ca726a7766d74edfd8cb74d2cf95e7d4654ce58e",
            hex("passwort", Pbkdf2.BACKUP_ITERATIONS),
        )
    }

    @Test
    fun specialCharactersAreUtf8() {
        assertEquals(
            "090f088c774831b951cdff271be383fe5c34b45a5e925f5cf465eda21affa3a1",
            hex("Gruß-Ähre-müde-😀", 1000),
        )
    }

    @Test
    fun moreThanOneBlock() {
        assertEquals(
            "546f47b1fbba44eb9b8f88a78a65a017911b0f1d744ad7aff4742981e7af99d8" +
                "ca31938c361ececdd17b1f0eeefadfec9df608589f378a985dc1424b6b1481ba",
            hex("passwort", 1000, 64),
        )
    }

    @Test
    fun specFixtureCases() {
        val fixture = Fixtures.identity.obj("pbkdf2_hmac_sha256")
        val fixtureSalt = DartUtf8.encode(fixture.str("salt_utf8"))
        for (c in fixture.arr("cases")) {
            // The 600k cases are covered above / by the backup test; keep this test fast.
            if (c.int("iterations") > 100_000 && c.str("password") == "passwort") continue
            assertEquals(
                "${c.str("password")} / ${c.int("iterations")}",
                c.str("hex"),
                Pbkdf2.hmacSha256(c.str("password"), fixtureSalt, c.int("iterations"), c.int("keyBytes")).toHex(),
            )
        }
    }

    @Test
    fun emptyPasswordWorksLikeDart() {
        // Hmac(sha256, []) is legal in Dart; javax would refuse an empty key.
        assertEquals(32, Pbkdf2.hmacSha256("", salt, 1, 32).size)
        assertEquals(
            HmacSha256.mac(ByteArray(0), salt + byteArrayOf(0, 0, 0, 1)).toHex(),
            Pbkdf2.hmacSha256("", salt, 1, 32).toHex(),
        )
    }

    @Test
    fun zeroIterationsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Pbkdf2.hmacSha256("x", byteArrayOf(1), 0, 32) }
    }

    @Test
    fun zeroKeyLengthRejected() {
        assertThrows(IllegalArgumentException::class.java) { Pbkdf2.hmacSha256("x", byteArrayOf(1), 1, 0) }
    }
}
