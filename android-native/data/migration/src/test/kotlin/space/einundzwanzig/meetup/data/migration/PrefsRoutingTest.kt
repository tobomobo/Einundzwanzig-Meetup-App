package space.einundzwanzig.meetup.data.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.migration.internal.PrefsRouting
import space.einundzwanzig.meetup.data.migration.legacy.DecodedPrefs

/** Spec 14 §4.3.2 / §4.3.4 routing exceptions (fixtures `prefs_key_inventory`, `obsolete_prefs_keys`, `migration_routing`). */
class PrefsRoutingTest {
    private val hex = "ab".repeat(32)

    private fun plan(vararg entries: Pair<String, PrefValue>, undecodable: List<String> = emptyList()) =
        PrefsRouting.plan(DecodedPrefs(linkedMapOf(*entries), undecodable))

    private fun s(v: String) = PrefValue.StringValue(v)

    @Test
    fun e0IdentityKeysAreRoutedToSecureStoreAndNeverCopied() {
        val p = plan(
            "nostr_nsec_key" to s("nsec1x"),
            "nostr_npub_key" to s("npub1x"),
            "nostr_priv_hex" to s(""),
            "nickname" to s("Satoshi"),
        )
        assertEquals(mapOf("nostr_nsec_key" to "nsec1x", "nostr_npub_key" to "npub1x"), p.plaintextSecrets)
        assertEquals(mapOf("nostr_nsec_key" to "nostr_nsec_key", "nostr_npub_key" to "nostr_npub_key"), p.plaintextSourceKeys)
        assertTrue(PrefsRouting.E0_IDENTITY_KEYS.none { it in p.copy })
        assertEquals(PrefValue.BoolValue(true), p.copy["secure_migration_done"])
        assertEquals(s("Satoshi"), p.copy["nickname"])
    }

    @Test
    fun e0KeysAfterSecureMigrationAreIgnoredWithWarning() {
        val p = plan("secure_migration_done" to PrefValue.BoolValue(true), "nostr_nsec_key" to s("nsec1stale"))
        assertTrue(p.plaintextSecrets.isEmpty())
        assertNull(p.copy["nostr_nsec_key"])
        assertEquals(PrefValue.BoolValue(true), p.copy["secure_migration_done"])
        assertEquals(listOf("E0_KEY_AFTER_MIGRATION:nostr_nsec_key"), p.warnings)
    }

    @Test
    fun noE0KeysLeavesTheFlagAlone() {
        assertNull(plan("nickname" to s("x")).copy["secure_migration_done"])
        assertEquals(PrefValue.BoolValue(false), plan("secure_migration_done" to PrefValue.BoolValue(false)).copy["secure_migration_done"])
    }

    @Test
    fun rollingQrLegacySecretsAreRouted() {
        val p = plan("rqr_session_seed" to s(hex), "rqr_session_base_payload" to s("{}"), "rqr_session_meetup_id" to s("42"))
        assertEquals(mapOf("rqr_session_seed_secure" to hex, "rqr_session_base_payload_secure" to "{}"), p.plaintextSecrets)
        assertEquals(mapOf("rqr_session_seed_secure" to "rqr_session_seed", "rqr_session_base_payload_secure" to "rqr_session_base_payload"), p.plaintextSourceKeys)
        assertEquals(setOf("rqr_session_meetup_id"), p.copy.keys)
        // Empty legacy values are neither routed nor copied.
        val empty = plan("rqr_session_seed" to s(""))
        assertTrue(empty.plaintextSecrets.isEmpty() && empty.copy.isEmpty())
    }

    @Test
    fun specialKeysAreRoutedSeparately() {
        val p = plan(
            "local_profile_picture" to s("/data/x.jpg"),
            "diagnostic_log" to s("[]"),
            "diagnostic_verbose" to PrefValue.BoolValue(true),
            "badge_restore_expect" to s("npub1legacy"),
        )
        assertEquals("/data/x.jpg", p.profilePicturePath)
        assertEquals("[]", p.diagnosticLog)
        assertEquals(true, p.diagnosticVerbose)
        assertEquals(setOf("badge_restore_expect"), p.copy.keys)
        assertEquals(s("npub1legacy"), p.copy["badge_restore_expect"]) // stored type preserved
    }

    @Test
    fun obsoleteKeysFromFixtureAreSkipped() {
        val obsolete = MigrationFixtures.vectors.arr("obsolete_prefs_keys").map { it.str("key").replace("<hex64>", hex) }
        assertEquals(10, obsolete.size)
        val p = plan(*obsolete.map { it to s("x") }.toTypedArray())
        assertEquals(obsolete, p.skipped)
        assertTrue(p.copy.isEmpty())
        // The live v2 / per-pubkey keys are not obsolete.
        for (live in listOf("zap_stats_cache_v2_$hex", "zap_cache_ts_v2_$hex", "nostr_profile_picture_$hex", "nostr_profile_picture_time_$hex")) {
            assertFalse(live, PrefsRouting.isObsolete(live))
        }
    }

    @Test
    fun everyInventoryKeyHasARoute() {
        val inventory = MigrationFixtures.vectors.arr("prefs_key_inventory")
        assertEquals(112, inventory.size)
        val routedElsewhere = PrefsRouting.E0_IDENTITY_KEYS + PrefsRouting.RQR_ROUTES.keys +
            listOf("local_profile_picture", "diagnostic_log", "diagnostic_verbose")
        for (row in inventory) {
            val key = row.str("key").replace(Regex("<[^>]+>"), "x")
            val action = row.str("action")
            val p = plan(key to s("v"))
            when {
                key in routedElsewhere -> assertFalse(key, key in p.copy)
                else -> {
                    assertTrue("$key ($action)", action.startsWith("copy"))
                    assertEquals(key, s("v"), p.copy[key])
                }
            }
            assertFalse(key, PrefsRouting.isObsolete(key))
        }
        // Sensitivity SECRET rows are never copied.
        inventory.filter { it.jsonObjectSensitivity() == "SECRET" }.forEach { row ->
            val key = row.str("key")
            assertFalse(key, key in plan(key to s("v")).copy)
        }
    }

    @Test
    fun referencedNpubsAndUndecodableWarnings() {
        val p = plan(
            "nostr" to s(" npub1a "),
            "amber_npub" to s("npub1b"),
            "nip46_npub" to s(""),
            undecodable = listOf("tile_order"),
        )
        assertEquals(setOf("npub1a", "npub1b"), p.referencedNpubs)
        assertEquals(listOf("UNDECODABLE_PREF:tile_order"), p.warnings)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSensitivity(): String = str("sensitivity")
}
