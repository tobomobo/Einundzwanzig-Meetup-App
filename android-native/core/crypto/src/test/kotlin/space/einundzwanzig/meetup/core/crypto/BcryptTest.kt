package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** bcrypt against outputs of `package:dbcrypt` 2.0.0 (`DBCrypt().hashpw`) for the same inputs. */
class BcryptTest {
    private val saltA = "\$2a\$04\$abcdefghijklmnopqrstuu"
    private val saltB = "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF."
    private val saltY = "\$2y\$04\$......................"

    private val dartVectors: List<Triple<String, String, String>> = listOf(
        Triple("", saltA, "\$2a\$04\$abcdefghijklmnopqrstuubyCG3zY1GIXMyxfivm.ClDiInHzxjiq"),
        Triple("", saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.Xd8Ewf..vaNdMfRkndPvNDo26MebAtu"),
        Triple("", saltY, "\$2y\$04\$......................w74bL5gU7LSJClZClCa.Pkz14aTv/XO"),
        Triple("U*U", saltA, "\$2a\$04\$abcdefghijklmnopqrstuuCFaEytnzrfaPZJKbS76hh9vqd9r8v2S"),
        Triple("U*U", saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.HZZLyzXp/APKnmE0fYxxsfwJ7bbQRT6"),
        Triple("U*U", saltY, "\$2y\$04\$......................OFozeos6d2lMGDeQ3d5irLzOX9aUXQa"),
        Triple("passwort", saltA, "\$2a\$04\$abcdefghijklmnopqrstuulPJbgekT1zCkL8V85Evy5ENBxzLSWC."),
        Triple("passwort", saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.DdMBtRyO6q7/Dbw8SXV7OsmpA6l7doi"),
        Triple("passwort", saltY, "\$2y\$04\$......................kyMHBfo1lIttWmB4iGGL/96Pu/WHFGu"),
        Triple("Grüß-😀", saltA, "\$2a\$04\$abcdefghijklmnopqrstuu5ut5.S04tSJGDvDKhMneQRlm3ziGdKG"),
        Triple("Grüß-😀", saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.DASStYEqcdamh0N6DCw7ZujpcqfXGCC"),
        Triple("Grüß-😀", saltY, "\$2y\$04\$......................3pcoDuO9EsQdEoiQoFNctzxB1a8ZWQO"),
        Triple("a".repeat(71), saltA, "\$2a\$04\$abcdefghijklmnopqrstuuMCu.k1vM/ywQwiONaEn3oEMlZoCVBd6"),
        Triple("a".repeat(71), saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.wq7Oc6NMvIN3NUyWm/V5ZslVmcB/h7W"),
        Triple("a".repeat(71), saltY, "\$2y\$04\$......................OKajFstSaQgvFOLFT26yIEg4fPr47jO"),
        Triple("a".repeat(72), saltA, "\$2a\$04\$abcdefghijklmnopqrstuuBzzIgyKkz7xMWYSzkIjUSnxEQFQ0WNe"),
        Triple("a".repeat(72), saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.Tyda.o6kz4y.pokQa990nfo3hdXc8Ai"),
        Triple("a".repeat(72), saltY, "\$2y\$04\$......................UaUp2CqHXn14N7RprrzoDsNv91ahi36"),
        // > 72 bytes: only the first 72 count
        Triple("a".repeat(73), saltA, "\$2a\$04\$abcdefghijklmnopqrstuuBzzIgyKkz7xMWYSzkIjUSnxEQFQ0WNe"),
        Triple("a".repeat(100), saltB, "\$2b\$05\$/OK.fbVrR/bpIqNJ5ianF.Tyda.o6kz4y.pokQa990nfo3hdXc8Ai"),
        // minor-less variant: no trailing NUL
        Triple("abc", "\$2\$04\$abcdefghijklmnopqrstuu", "\$2\$04\$abcdefghijklmnopqrstuuc6Unxmz12vpy80gkiL8/xgnSIKHKWCO"),
    )

    @Test
    fun hashpwMatchesDbcrypt() {
        for ((pw, salt, expected) in dartVectors) {
            assertEquals("pw=$pw salt=$salt", expected, Bcrypt.hashpw(pw, salt))
            assertTrue(Bcrypt.checkpw(pw, expected))
        }
    }

    @Test
    fun checkpwRejectsWrongPasswordAndGarbage() {
        val hash = Bcrypt.hashpw("passwort", saltA)
        assertFalse(Bcrypt.checkpw("Passwort", hash))
        assertFalse(Bcrypt.checkpw("passwort", "not a hash"))
        assertFalse(Bcrypt.checkpw("passwort", "\$2a\$99\$abcdefghijklmnopqrstuu"))
    }

    @Test
    fun gensaltFormat() {
        val salt = Bcrypt.gensalt(4)
        assertTrue(salt, Regex("^\\$2b\\$04\\$[./A-Za-z0-9]{22}$").matches(salt))
        assertTrue(Regex("^\\$2b\\$10\\$").containsMatchIn(Bcrypt.gensalt()))
        val hash = Bcrypt.hashpw("geheim", salt)
        assertEquals(60, hash.length)
        assertTrue(Bcrypt.checkpw("geheim", hash))
        // fixed salt bytes → deterministic salt string (jBCrypt base64)
        assertEquals("\$2b\$12\$......................", Bcrypt.gensalt(12, ByteArray(16)))
    }

    @Test
    fun invalidSaltsThrow() {
        for (bad in listOf("", "\$1\$04\$abcdefghijklmnopqrstuu", "\$2c\$04\$abcdefghijklmnopqrstuu", "\$2a\$4\$abcdefghijklmnopqrstuuu", "\$2a\$03\$abcdefghijklmnopqrstuu", "\$2a\$04\$short")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Bcrypt.hashpw("x", bad) }
        }
    }
}
