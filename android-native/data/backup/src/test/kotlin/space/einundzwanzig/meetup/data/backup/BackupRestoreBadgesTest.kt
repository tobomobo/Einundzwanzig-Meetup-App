package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * Port of `test/backup_restore_badges_test.dart`: an import must not report success when the badges
 * were not written (spec 08 §8.5 `restoreBadges`, §10.6).
 */
class BackupRestoreBadgesTest {

    private val blobKey = "badges_encrypted"
    private val keyAlias = "badge_encryption_key"
    private val suspectKeyAlias = "badge_encryption_key_suspect"

    /** One event per id: the union works per event, not per id. */
    private fun badge(id: String) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
    )

    private fun blob(badges: List<MeetupBadge>): String =
        AesGcmBlob.seal(CryptoRandom.bytes(32), MeetupBadge.listToJsonString(badges))

    private fun harness(prefs: Map<String, PrefValue> = emptyMap(), secure: Map<String, String> = emptyMap()) =
        BackupHarness(prefs = InMemoryKeyValueStore(prefs), secure = InMemorySecureStore(secure))

    @Before
    fun setUp() = runTest { AppLogger.clear() }

    @Test
    fun `kaputter Badge-Schluessel ohne Datei - Restore schiebt ihn beiseite und schreibt`() = runTest {
        val h = harness(secure = mapOf(keyAlias to "kein-schluessel"))

        val ok = h.engine.restoreBadges(listOf(badge("a")))

        assertTrue(ok)
        assertTrue(h.prefs.contains(blobKey))
        // The unusable value stays for a later investigation.
        assertEquals("kein-schluessel", h.secure.entries.value[suspectKeyAlias])
        assertTrue(Regex("^[0-9a-f]{64}$").matches(h.secure.entries.value[keyAlias] ?: ""))
        assertEquals(listOf("a"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `nicht lesbarer sicherer Speicher - Restore meldet Fehlschlag`() = runTest {
        val old = blob(listOf(badge("alt")))
        val h = harness(prefs = mapOf(blobKey to PrefValue.StringValue(old)))
        h.secure.failRead = { true }
        h.repo.loadBadges()

        val ok = h.engine.restoreBadges(listOf(badge("a")))

        assertFalse(ok)
        assertEquals(old, h.prefs.getString(blobKey))
    }

    @Test
    fun `leeres Backup bei nicht lesbarer Sammlung ist kein Fehler`() = runTest {
        val old = blob(listOf(badge("alt")))
        val h = harness(prefs = mapOf(blobKey to PrefValue.StringValue(old)))
        h.repo.loadBadges()

        val ok = h.engine.restoreBadges(emptyList())

        assertTrue(ok)
        assertEquals(old, h.prefs.getString(blobKey))
    }

    @Test
    fun `gelungener Restore schreibt die Badges`() = runTest {
        val h = harness()

        assertTrue(h.engine.restoreBadges(listOf(badge("a"), badge("b"))))

        assertEquals(listOf("a", "b"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `Restore zaehlt Varianten derselben Veranstaltung getrennt vom Bestand`() = runTest {
        val h = harness()
        val original = badge("event-a")
        val variant = MeetupBadge.fromJson(JsonObject(original.toJson() + ("id" to JsonPrimitive("rotating-qr-id"))))

        assertTrue(h.engine.restoreBadges(listOf(original, variant, badge("event-b"))))

        val loaded = h.repo.loadBadges()
        assertEquals(2, loaded.size)
        assertEquals(
            setOf(BadgeIdentity.unionKey(original), BadgeIdentity.unionKey(badge("event-b"))),
            loaded.map { BadgeIdentity.unionKey(it) }.toSet(),
        )
        assertTrue(logText().contains("3 Eingänge, 2 eindeutige Einträge"))
    }

    @Test
    fun `Restore erhaelt verschiedene Veranstaltungen trotz gleicher Badge-ID`() = runTest {
        val h = harness()
        val first = badge("shared-id")
        val second = MeetupBadge.fromJson(
            JsonObject(
                first.toJson() +
                    ("meetupEventId" to JsonPrimitive("different-event")) +
                    ("date" to JsonPrimitive(DartDateTime.utc(2026, 10, 12, 19).toIso8601String())),
            ),
        )

        assertTrue(h.engine.restoreBadges(listOf(first, second)))

        assertEquals(
            setOf(BadgeIdentity.unionKey(first), BadgeIdentity.unionKey(second)),
            h.repo.loadBadges().map { BadgeIdentity.unionKey(it) }.toSet(),
        )
    }
}
