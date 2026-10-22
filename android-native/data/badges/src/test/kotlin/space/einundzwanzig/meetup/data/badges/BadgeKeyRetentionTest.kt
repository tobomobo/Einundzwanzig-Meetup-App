package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys

/**
 * Port of `test/badge_key_retention_test.dart`: the badge key is never re-created on a read
 * error. The encrypted file stays, another key in the store can still open it, and a save does
 * not overwrite it.
 *
 * Dart mutates the map behind `FlutterSecureStorage.setMockInitialValues(secure)` mid-test; here
 * the tests write into the [InMemorySecureStore] directly. `_BrokenRead` (every `read` throws,
 * `readAll` works) is `failRead = { true }`.
 */
class BadgeKeyRetentionTest {

    private val blobKey = BadgeStoreKeys.PRIMARY
    private val preservedKey = BadgeStoreKeys.PRESERVED
    private val olderKey = BadgeStoreKeys.OLDER
    private val keyAlias = BadgeStoreKeys.KEY
    private val suspectKeyAlias = BadgeStoreKeys.KEY_SUSPECT
    private val pendingBlobKey = BadgeStoreKeys.PENDING
    private val pendingKeyAlias = BadgeStoreKeys.PENDING_KEY
    private val legacyPendingKey = BadgeStoreKeys.LEGACY_PENDING

    private fun badge(id: String) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
    )

    private fun privateBadge(id: String) = MeetupBadge(
        id = id,
        meetupName = "Geheimtreffen Hinterzimmer",
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
        lat = 49.97654,
        lng = 9.14321,
        claimSig = "c".repeat(128),
    )

    private fun hexKey(): String = CryptoRandom.bytes(32).toHex()

    private fun blob(keyHex: String, badges: List<MeetupBadge>): String =
        AesGcmBlob.seal(Hex.decode(keyHex), MeetupBadge.listToJsonString(badges))

    private fun harness(prefs: Map<String, PrefValue> = emptyMap(), secure: Map<String, String> = emptyMap()) =
        StoreHarness(prefs = InMemoryKeyValueStore(prefs), secure = InMemorySecureStore(secure))

    private fun s(value: String) = PrefValue.StringValue(value)

    private fun StoreHarness.secureMap() = secure.entries.value

    private fun ids(list: List<MeetupBadge>) = list.map { it.id }

    @Test
    fun `creates a key and reads the badges again`() = runTest {
        val h = harness()
        h.repo.saveBadges(listOf(badge("a")))
        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
        assertFalse(h.repo.badgesUnreadable.value)
        assertEquals(64, h.secureMap()[keyAlias]!!.length)
    }

    @Test
    fun `a missing key creates no new one and leaves the file`() = runTest {
        val good = hexKey()
        val blob = blob(good, listOf(badge("a")))
        val h = harness(prefs = mapOf(blobKey to s(blob)))

        assertTrue(h.repo.loadBadges().isEmpty())
        assertTrue(h.repo.badgesUnreadable.value)
        assertFalse(keyAlias in h.secureMap())
        assertEquals(blob, h.prefs.getString(blobKey))
        assertEquals(blob, h.prefs.getString(preservedKey))
    }

    @Test
    fun `a read error writes no new key`() = runTest {
        val blob = blob(hexKey(), listOf(badge("a")))
        val h = harness(prefs = mapOf(blobKey to s(blob)))
        h.secure.failRead = { true }

        assertTrue(h.repo.loadBadges().isEmpty())
        assertTrue(h.repo.badgesUnreadable.value)
        assertTrue(h.secureMap().isEmpty())
        assertEquals(blob, h.prefs.getString(blobKey))
    }

    @Test
    fun `a key of the wrong length is not replaced`() = runTest {
        val blob = blob(hexKey(), listOf(badge("a")))
        val h = harness(prefs = mapOf(blobKey to s(blob)), secure = mapOf(keyAlias to "kein-schluessel"))

        h.repo.loadBadges()

        assertEquals("kein-schluessel", h.secureMap()[keyAlias])
        assertTrue(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `another value in the store opens the file again`() = runTest {
        val good = hexKey()
        val blob = blob(good, listOf(badge("a")))
        val h = harness(
            prefs = mapOf(blobKey to s(blob)),
            secure = mapOf(keyAlias to hexKey(), "old_badge_key" to good),
        )

        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
        assertEquals(good, h.secureMap()[keyAlias])
        assertFalse(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `identity secrets are never tried as badge keys`() = runTest {
        val good = hexKey()
        val blob = blob(good, listOf(badge("a")))
        for (alias in listOf(
            IdentityKeys.PRIV_HEX,
            IdentityKeys.NIP46_CLIENT_SK,
            "${IdentityKeys.PRIV_HEX}_BACKUP",
            IdentityKeys.recoveryName("legacy_esp", IdentityKeys.PRIV_HEX),
        )) {
            val h = harness(prefs = mapOf(blobKey to s(blob)), secure = mapOf(alias to good))
            assertTrue(h.repo.loadBadges().isEmpty())
            assertTrue(alias, h.repo.badgesUnreadable.value)
            assertEquals(blob, h.prefs.getString(blobKey))
        }
        // A recovery copy of the badge key itself still rescues the file.
        val h = harness(
            prefs = mapOf(blobKey to s(blob)),
            secure = mapOf(IdentityKeys.recoveryName("legacy_esp", keyAlias) to good),
        )
        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
    }

    @Test
    fun `a first save without a load does not overwrite a file only the backup key opens`() = runTest {
        val good = hexKey()
        val other = hexKey()
        val blob = blob(good, listOf(badge("a")))
        val h = harness(
            prefs = mapOf(blobKey to s(blob)),
            secure = mapOf(keyAlias to other, BadgeStoreKeys.KEY_BACKUP to good),
        )
        assertFalse(h.repo.loaded.value)

        assertTrue(h.repo.update { it + badge("neu") })

        assertTrue(h.repo.loaded.value)
        assertEquals(good, h.secureMap()[keyAlias])
        val reopened = harness(prefs = mapOf(blobKey to s(h.prefs.getString(blobKey)!!)), secure = mapOf(keyAlias to good))
        assertEquals(listOf("a", "neu"), ids(reopened.repo.loadBadges()))
    }

    @Test
    fun `a first save without a load is refused while the file is unreadable`() = runTest {
        val blob = blob(hexKey(), listOf(badge("a")))
        val other = hexKey()
        val h = harness(prefs = mapOf(blobKey to s(blob)), secure = mapOf(keyAlias to other))

        assertFalse(h.repo.saveBadges(listOf(badge("neu"))))
        assertFalse(h.repo.update { it + badge("neu") })

        assertEquals(blob, h.prefs.getString(blobKey))
        assertTrue(h.repo.badgesUnreadable.value)
        assertTrue(h.repo.loaded.value)
    }

    @Test
    fun `saving does not overwrite an unreadable file`() = runTest {
        val blob = blob(hexKey(), listOf(badge("a")))
        val h = harness(prefs = mapOf(blobKey to s(blob)))
        h.repo.loadBadges()

        assertFalse(h.repo.saveBadges(listOf(badge("neu"))))
        assertEquals(blob, h.prefs.getString(blobKey))
        assertFalse(keyAlias in h.secureMap())
    }

    @Test
    fun `a kept-aside scan joins once the key is back`() = runTest {
        val good = hexKey()
        val h = harness(prefs = mapOf(blobKey to s(blob(good, listOf(badge("alt"))))))
        h.repo.loadBadges()
        h.repo.keepAside(badge("neu"))

        h.secure.write(keyAlias, good)
        val loaded = h.repo.loadBadges()

        assertTrue(ids(loaded).containsAll(listOf("alt", "neu")))
        assertFalse(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `reset also clears the backup`() = runTest {
        val blob = blob(hexKey(), listOf(badge("a")))
        val h = harness(prefs = mapOf(blobKey to s(blob), preservedKey to s(blob)))
        h.repo.loadBadges()

        assertTrue(h.repo.saveBadges(emptyList(), discardLocked = true))
        assertNull(h.prefs.getString(blobKey))
        assertNull(h.prefs.getString(preservedKey))
        assertFalse(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `saving empty does not delete an unreadable file`() = runTest {
        val blob = blob(hexKey(), listOf(badge("a")))
        val h = harness(prefs = mapOf(blobKey to s(blob)))
        h.repo.loadBadges()

        assertFalse(h.repo.saveBadges(emptyList()))
        assertEquals(blob, h.prefs.getString(blobKey))
    }

    @Test
    fun `a kept-aside scan is not stored in plaintext`() = runTest {
        val h = harness(prefs = mapOf(blobKey to s(blob(hexKey(), listOf(badge("alt"))))))
        h.repo.loadBadges()

        h.repo.keepAside(privateBadge("neu"))

        assertFalse(h.prefs.contains(legacyPendingKey))
        assertTrue(h.prefs.getString(pendingBlobKey)!!.contains(':'))
        for ((key, value) in h.prefs.getAll()) {
            val text = when (value) {
                is PrefValue.StringListValue -> value.value.toString()
                else -> value.toString()
            }
            assertFalse(key, text.contains("Geheimtreffen"))
            assertFalse(key, text.contains("49.97"))
            assertFalse(key, text.contains("c".repeat(128)))
        }
        // Own key; the broken badge key stays untouched.
        assertEquals(64, h.secureMap()[pendingKeyAlias]!!.length)
        assertFalse(keyAlias in h.secureMap())
    }

    @Test
    fun `after merging, the kept-aside slot and its key are gone`() = runTest {
        val good = hexKey()
        val h = harness(prefs = mapOf(blobKey to s(blob(good, listOf(badge("alt"))))))
        h.repo.loadBadges()
        h.repo.keepAside(privateBadge("neu"))

        h.secure.write(keyAlias, good)
        val loaded = h.repo.loadBadges()

        val neu = loaded.first { it.id == "neu" }
        assertEquals("Geheimtreffen Hinterzimmer", neu.meetupName)
        assertEquals(49.97654, neu.lat, 0.0)
        assertFalse(h.prefs.contains(pendingBlobKey))
        assertFalse(pendingKeyAlias in h.secureMap())
    }

    @Test
    fun `plaintext kept-aside list from test builds is taken over encrypted`() = runTest {
        val good = hexKey()
        val h = harness(
            prefs = mapOf(
                blobKey to s(blob(good, listOf(badge("alt")))),
                legacyPendingKey to PrefValue.StringListValue(listOf(privateBadge("klartext").toJsonString())),
            ),
        )
        h.repo.loadBadges()

        h.repo.keepAside(badge("neu"))

        assertFalse(h.prefs.contains(legacyPendingKey))
        assertFalse(h.prefs.getString(pendingBlobKey)!!.contains("Geheimtreffen"))

        h.secure.write(keyAlias, good)
        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("alt", "klartext", "neu")))
    }

    @Test
    fun `an unreadable kept-aside slot is not discarded on save`() = runTest {
        val pendingBlob = blob(hexKey(), listOf(badge("vorgemerkt")))
        val h = harness(prefs = mapOf(pendingBlobKey to s(pendingBlob)))

        assertTrue(h.repo.saveBadges(listOf(badge("a"))))
        assertEquals(pendingBlob, h.prefs.getString(pendingBlobKey))
    }

    @Test
    fun `keepAside reports true when keeping aside works`() = runTest {
        val h = harness()
        assertTrue(h.repo.keepAside(badge("neu")))
        assertTrue(h.prefs.getString(pendingBlobKey)!!.contains(':'))
    }

    @Test
    fun `keepAside reports false when the kept-aside slot is unreadable`() = runTest {
        val h = harness(prefs = mapOf(pendingBlobKey to s(blob(hexKey(), listOf(badge("vorgemerkt"))))))
        assertFalse(h.repo.keepAside(badge("neu")))
    }

    @Test
    fun `opening unites primary and backup by id`() = runTest {
        val keyA = hexKey()
        val keyB = hexKey()
        val primaryBlob = blob(keyA, listOf(badge("a"), badge("b")))
        val preservedBlob = blob(keyB, listOf(badge("b"), badge("c")))
        val h = harness(
            prefs = mapOf(blobKey to s(primaryBlob), preservedKey to s(preservedBlob)),
            secure = mapOf(keyAlias to keyA, "old_badge_key" to keyB),
        )

        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("a", "b", "c")))
        assertFalse(h.repo.badgesUnreadable.value)
        // The backup stays the old file. The primary carries the union and is not replaced
        // again on the next open.
        assertEquals(preservedBlob, h.prefs.getString(preservedKey))
        val written = h.prefs.getString(blobKey)
        assertNotEquals(primaryBlob, written)
        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("a", "b", "c")))
        assertEquals(written, h.prefs.getString(blobKey))

        h.prefs.remove(preservedKey)
        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `a second replace moves the backup one step older`() = runTest {
        val blob = blob(hexKey(), listOf(badge("alt")))
        val h = harness(prefs = mapOf(blobKey to s(blob)))
        h.repo.loadBadges()

        assertTrue(h.repo.saveBadges(listOf(badge("fern1")), replaceUnreadable = true))
        assertTrue(h.repo.saveBadges(listOf(badge("fern2")), replaceUnreadable = true))

        assertEquals(blob, h.prefs.getString(olderKey))
        assertNotEquals(blob, h.prefs.getString(preservedKey))

        // Both new versions join through the union.
        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("fern1", "fern2")))
        assertFalse(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `replacing with an unusable key value moves it aside`() = runTest {
        val blob = blob(hexKey(), listOf(badge("alt")))
        val h = harness(prefs = mapOf(blobKey to s(blob)), secure = mapOf(keyAlias to "kein-schluessel"))
        h.repo.loadBadges()

        assertTrue(h.repo.saveBadges(listOf(badge("neu")), replaceUnreadable = true))
        assertEquals("kein-schluessel", h.secureMap()[suspectKeyAlias])
        assertTrue(Regex("^[0-9a-f]{64}$").matches(h.secureMap()[keyAlias] ?: ""))
        assertEquals(blob, h.prefs.getString(preservedKey))
        assertEquals(listOf("neu"), ids(h.repo.loadBadges()))
        assertFalse(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `promoting a readable backup keeps the unreadable primary`() = runTest {
        val lostKey = hexKey()
        val goodKey = hexKey()
        val unopenable = blob(lostKey, listOf(badge("verloren")))
        val h = harness(
            prefs = mapOf(blobKey to s(unopenable), preservedKey to s(blob(goodKey, listOf(badge("sicher"))))),
            secure = mapOf("old_badge_key" to goodKey),
        )

        assertEquals(listOf("sicher"), ids(h.repo.loadBadges()))
        // The unreadable primary survives in the backup chain so its key can open it later.
        assertTrue(unopenable in listOf(h.prefs.getString(preservedKey), h.prefs.getString(olderKey)))

        h.secure.write(keyAlias, lostKey) // the key comes back
        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("sicher", "verloren")))

        // The union lives in the primary, even without the backups.
        h.prefs.remove(preservedKey)
        h.prefs.remove(olderKey)
        assertTrue(ids(h.repo.loadBadges()).containsAll(listOf("sicher", "verloren")))
    }

    @Test
    fun `a failed read pushes no version out of the backups`() = runTest {
        val primaryBlob = blob(hexKey(), listOf(badge("eins")))
        val preservedBlob = blob(hexKey(), listOf(badge("null")))
        val olderBlob = blob(hexKey(), listOf(badge("alt")))
        val h = harness(
            prefs = mapOf(blobKey to s(primaryBlob), preservedKey to s(preservedBlob), olderKey to s(olderBlob)),
        )

        h.repo.loadBadges()

        assertTrue(h.repo.badgesUnreadable.value)
        assertEquals(primaryBlob, h.prefs.getString(blobKey))
        assertEquals(preservedBlob, h.prefs.getString(preservedKey))
        assertEquals(olderBlob, h.prefs.getString(olderKey))
    }

    @Test
    fun `for the same event the version with a claim wins`() = runTest {
        val keyA = hexKey()
        val keyB = hexKey()
        val withClaim = MeetupBadge(
            id = "x",
            meetupEventId = "x",
            meetupName = "Aschaffenburg",
            date = DartDateTime.utc(2026, 10, 11, 19),
            iconPath = "assets/badge_icon.png",
            claimSig = "c".repeat(128),
        )
        val h = harness(
            prefs = mapOf(blobKey to s(blob(keyA, listOf(badge("x")))), preservedKey to s(blob(keyB, listOf(withClaim)))),
            secure = mapOf(keyAlias to keyA, "old_badge_key" to keyB),
        )

        val loaded = h.repo.loadBadges()
        assertEquals(1, loaded.size)
        assertEquals("c".repeat(128), loaded.first().claimSig)

        h.prefs.remove(preservedKey)
        assertEquals("c".repeat(128), h.repo.loadBadges().single().claimSig)
    }
}
