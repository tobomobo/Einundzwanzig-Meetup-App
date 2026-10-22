package space.einundzwanzig.meetup.data.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.migration.legacy.FlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.SharedPrefsFiles
import java.io.File

/**
 * Data-driven check of fixture `migration_routing` (spec 14 §4.3.4 / §4.8): every row of the
 * fixture has an end-to-end scenario on a constructed device, and every scenario belongs to a row
 * (a new or changed row fails here until it is covered). One Robolectric application per row, so
 * no SharedPreferences instance cached by an earlier row leaks into the next.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class MigrationRoutingFixtureTest(private val index: Int) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alice = KeyPair.fromPrivateKeyHex("3".repeat(64))
    private val bob = KeyPair.fromPrivateKeyHex("4".repeat(64))

    private fun flutterPrefs(): Map<String, Any> = SharedPrefsFiles(context).read(FlutterPrefs.FILE)

    /** `from` of the fixture row → scenario checking its `when` / `to` / `then`. */
    private val scenarios: Map<String, suspend () -> Unit> = linkedMapOf(
        "prefs nostr_nsec_key/nostr_npub_key/nostr_priv_hex" to suspend {
            val h = MigrationHarness(context)
            // when: secure_migration_done != true, non-empty; to: same key, overwriting the secure-storage value.
            h.device.write(
                FlutterPrefs.FILE,
                mapOf(
                    "flutter.nostr_nsec_key" to alice.nsec,
                    "flutter.nostr_npub_key" to alice.npub,
                    "flutter.nostr_priv_hex" to alice.privateKeyHex,
                    "flutter.secure_migration_done" to false,
                ),
            )
            h.writeE2(mapOf("nostr_nsec_key" to bob.nsec))
            assertEquals(MigrationState.IMPORTED, h.migration().ensureMigrated().state)
            assertEquals(alice.nsec, h.secure.read("nostr_nsec_key"))
            assertEquals(alice.npub, h.secure.read("nostr_npub_key"))
            assertEquals(alice.privateKeyHex, h.secure.read("nostr_priv_hex"))
            assertEquals("overwritten value kept", bob.nsec, h.secure.read("legacy_backup/nostr_nsec_key"))
            // then: removed from the Flutter prefs after read-back; secure_migration_done=true.
            val left = flutterPrefs().keys
            assertTrue(left.none { it in setOf("flutter.nostr_nsec_key", "flutter.nostr_npub_key", "flutter.nostr_priv_hex") })
            assertEquals(true, h.kv.getBool("secure_migration_done"))
            assertTrue(h.kv.keys().none { it.startsWith("nostr_") })
        },
        "prefs rqr_session_seed" to suspend { rqrRoute("rqr_session_seed", "ab".repeat(32)) },
        "prefs rqr_session_base_payload" to suspend { rqrRoute("rqr_session_base_payload", "{\"p\":1}") },
        "prefs local_profile_picture" to suspend {
            val h = MigrationHarness(context)
            val picked = File(context.cacheDir, "scaled_route.png").apply { writeBytes(byteArrayOf(7, 8)) }
            h.device.write(FlutterPrefs.FILE, mapOf("flutter.local_profile_picture" to picked.absolutePath))
            h.migration().ensureMigrated()
            val stored = h.kv.getString("local_profile_picture")!!
            assertEquals(File(context.filesDir, "profile/local_picture.png").absolutePath, stored)
            assertArrayEquals(byteArrayOf(7, 8), File(stored).readBytes())
            // else: omit the key.
            val g = MigrationHarness(context)
            g.device.write(FlutterPrefs.FILE, mapOf("flutter.local_profile_picture" to File(context.cacheDir, "gone.png").absolutePath))
            g.migration().ensureMigrated()
            assertNull(g.kv.get("local_profile_picture"))
        },
        "prefs <obsolete keys>" to suspend {
            val h = MigrationHarness(context)
            val obsolete = MigrationFixtures.vectors.arr("obsolete_prefs_keys")
                .map { it.str("key") }
                .filter { '<' !in it }
            assertTrue(obsolete.isNotEmpty())
            h.device.write(FlutterPrefs.FILE, obsolete.associate { "flutter.$it" to "x" })
            h.migration().ensureMigrated()
            for (k in obsolete) assertNull(k, h.kv.get(k))
            // then: kept in the Flutter file until cleanup.
            assertTrue(flutterPrefs().keys.containsAll(obsolete.map { "flutter.$it" }))
        },
        "prefs everything else with flutter. prefix" to suspend {
            val h = MigrationHarness(context)
            h.device.write(
                FlutterPrefs.FILE,
                mapOf(
                    "flutter.nickname" to "Anon",
                    "flutter.haptic_enabled" to false,
                    "flutter.admin_registry_timestamp" to 1786000000123L,
                    "flutter.favorite_meetups" to FlutterPrefs.JSON_LIST + "[\"1\",\"2\"]",
                    "not_flutter" to "ignored",
                ),
            )
            h.migration().ensureMigrated()
            assertEquals(PrefValue.StringValue("Anon"), h.kv.get("nickname"))
            assertEquals(PrefValue.BoolValue(false), h.kv.get("haptic_enabled"))
            assertEquals(PrefValue.IntValue(1786000000123L), h.kv.get("admin_registry_timestamp"))
            assertEquals(listOf("1", "2"), h.kv.getStringList("favorite_meetups"))
            assertNull(h.kv.get("not_flutter"))
        },
        "FSS <prefix>_<key> (custom cipher: GCM, or E1-fallback CBC)" to suspend {
            val h = MigrationHarness(context)
            h.writeE2(mapOf("badge_encryption_key" to "a".repeat(64)))
            assertEquals(MigrationState.IMPORTED, h.migration().ensureMigrated().state)
            assertEquals("a".repeat(64), h.secure.read("badge_encryption_key"))
            assertTrue(h.kv.getAll().isEmpty())
        },
        "FSS <prefix>_<key>_BACKUP (old cipher)" to suspend {
            val h = MigrationHarness(context)
            // when: main missing or undecryptable → primary.
            h.writeE2(
                mapOf("badge_pending_key" + Fss.BACKUP to "c".repeat(64), "badge_encryption_key" + Fss.BACKUP to "d".repeat(64)),
                extra = mapOf(Fss.ENTRY + "badge_pending_key" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n"),
            )
            assertEquals(MigrationState.IMPORTED, h.migration().ensureMigrated().state)
            assertEquals("c".repeat(64), h.secure.read("badge_pending_key"))
            assertEquals("d".repeat(64), h.secure.read("badge_encryption_key"))
            // otherwise: recovery candidate legacy_backup/<key>.
            val g = MigrationHarness(context)
            g.writeE2(mapOf("badge_encryption_key" to "a".repeat(64), "badge_encryption_key" + Fss.BACKUP to "b".repeat(64)))
            g.migration().ensureMigrated()
            assertEquals("a".repeat(64), g.secure.read("badge_encryption_key"))
            assertEquals("b".repeat(64), g.secure.read("legacy_backup/badge_encryption_key"))
        },
        "ESP <prefix>_<key>" to suspend {
            // when: no custom value; identity group npub referenced by the prefs → primary.
            val h = MigrationHarness(context)
            h.device.write(FlutterPrefs.FILE, mapOf("flutter.amber_npub" to alice.npub))
            h.device.write(
                Fss.DATA,
                FssWriter.espEntries(
                    h.keystore.masterAeadForWriting(),
                    mapOf("nostr_nsec_key" to alice.nsec, "badge_pending_key" to "e".repeat(64)),
                ),
            )
            assertEquals(MigrationState.IMPORTED, h.migration().ensureMigrated().state)
            assertEquals(alice.nsec, h.secure.read("nostr_nsec_key"))
            assertEquals("e".repeat(64), h.secure.read("badge_pending_key"))
            // otherwise (npub not referenced): legacy_esp/<key>; non-identity keys stay primary.
            val g = MigrationHarness(context)
            g.device.write(FlutterPrefs.FILE, mapOf("flutter.nostr" to bob.npub))
            g.device.write(
                Fss.DATA,
                FssWriter.espEntries(
                    g.keystore.masterAeadForWriting(),
                    mapOf("nostr_nsec_key" to alice.nsec, "badge_pending_key" to "e".repeat(64)),
                ),
            )
            g.migration().ensureMigrated()
            assertNull(g.secure.read("nostr_nsec_key"))
            assertEquals(alice.nsec, g.secure.read("legacy_esp/nostr_nsec_key"))
            assertEquals("e".repeat(64), g.secure.read("badge_pending_key"))
            // a differing custom-cipher value always wins.
            val c = MigrationHarness(context)
            c.writeKeys()
            c.device.write(
                Fss.DATA,
                FssWriter.espEntries(c.keystore.masterAeadForWriting(), mapOf("badge_encryption_key" to "f".repeat(64))) +
                    c.e2Entries(mapOf("badge_encryption_key" to "a".repeat(64))),
            )
            c.migration().ensureMigrated()
            assertEquals("a".repeat(64), c.secure.read("badge_encryption_key"))
            assertEquals("f".repeat(64), c.secure.read("legacy_esp/badge_encryption_key"))
        },
    )

    private suspend fun rqrRoute(prefKey: String, value: String) {
        val h = MigrationHarness(context)
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.$prefKey" to value, "flutter.nickname" to "Anon"))
        h.migration().ensureMigrated()
        assertEquals(value, h.secure.read("${prefKey}_secure"))
        assertNull(h.kv.get(prefKey))
        assertFalse(flutterPrefs().containsKey("flutter.$prefKey"))
        assertTrue(flutterPrefs().containsKey("flutter.nickname"))
    }

    @Test
    fun fixtureRowsAndScenariosMatch() {
        assertEquals(scenarios.keys.toList(), rows().map { ROWS[it[0] as Int].str("from") })
    }

    @Test
    fun routingRowHoldsEndToEnd() = runBlocking {
        val row = ROWS[index]
        for (field in listOf("when", "to", "then")) assertTrue(row.str(field).isNotEmpty())
        val scenario = scenarios[row.str("from")] ?: throw AssertionError("no scenario for routing row '${row.str("from")}'")
        try {
            scenario()
        } catch (e: AssertionError) {
            throw AssertionError("routing row '${row.str("from")}' → '${row.str("to")}': ${e.message}", e)
        }
    }

    companion object {
        private val ROWS get() = MigrationFixtures.vectors.arr("migration_routing")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "row {0}")
        fun rows(): List<Array<Any>> = ROWS.indices.map { arrayOf<Any>(it) }
    }
}
