package space.einundzwanzig.meetup.data.migration.internal

import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.migration.legacy.DecodedPrefs

/** What happens to the Flutter prefs (spec 14 §4.3.4). */
internal data class PrefsPlan(
    /** Copied to `KeyValueStore` under the same name and type. */
    val copy: Map<String, PrefValue>,
    /** Plaintext secrets routed to `SecureStore`: secure key → value. */
    val plaintextSecrets: Map<String, String>,
    /** For each routed secure key, the Flutter prefs key to remove after a verified write. */
    val plaintextSourceKeys: Map<String, String>,
    /** `local_profile_picture` path, handled separately (file copy). */
    val profilePicturePath: String?,
    /** Raw `diagnostic_log` for `AppLogger.importLegacy`. */
    val diagnosticLog: String?,
    /** `diagnostic_verbose` for `AppLogger.importLegacy`. */
    val diagnosticVerbose: Boolean?,
    /** Obsolete keys that were skipped (§4.3.2). */
    val skipped: List<String>,
    /** npubs the prefs reference (`nostr`, `amber_npub`, `nip46_npub`). */
    val referencedNpubs: Set<String>,
    /** Warning codes (names only). */
    val warnings: List<String>,
)

internal object PrefsRouting {
    const val SECURE_MIGRATION_DONE = "secure_migration_done"
    const val LOCAL_PROFILE_PICTURE = "local_profile_picture"
    const val DIAGNOSTIC_LOG = "diagnostic_log"
    const val DIAGNOSTIC_VERBOSE = "diagnostic_verbose"

    /** E0 plaintext identity keys → same secure key (Dart `SecureKeyStore.ensureMigrated`). */
    val E0_IDENTITY_KEYS = listOf("nostr_nsec_key", "nostr_npub_key", "nostr_priv_hex")

    /** Legacy rolling-QR prefs → secure key (Dart `_migrateSeedIfNeeded` / `_loadBasePayloadSecure`). */
    val RQR_ROUTES = mapOf(
        "rqr_session_seed" to "rqr_session_seed_secure",
        "rqr_session_base_payload" to "rqr_session_base_payload_secure",
    )

    /** Obsolete keys (§4.3.2, fixture `obsolete_prefs_keys`). */
    private val OBSOLETE_EXACT = setOf(
        "vouching_consensus_cache", "vouching_consensus_ts", "distrust_reports_cache",
        "nostr_profile_picture", "nostr_profile_picture_time",
        "badge_sync_outbox_v1", "badge_sync_hash_owner", "badge_sync_collection_hash",
    )
    private val OBSOLETE_PATTERNS = listOf(
        Regex("^zap_stats_cache_[0-9a-fA-F]{64}$"),
        Regex("^zap_cache_ts_[0-9a-fA-F]{64}$"),
    )

    /** Npub prefs used by the ESP identity rule (§4.8). */
    val NPUB_REFERENCES = listOf("nostr", "amber_npub", "nip46_npub")

    fun isObsolete(key: String): Boolean = key in OBSOLETE_EXACT || OBSOLETE_PATTERNS.any { it.matches(key) }

    fun plan(prefs: DecodedPrefs): PrefsPlan {
        val values = prefs.values
        val copy = LinkedHashMap<String, PrefValue>()
        val secrets = LinkedHashMap<String, String>()
        val sources = LinkedHashMap<String, String>()
        val skipped = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val migrationDone = (values[SECURE_MIGRATION_DONE] as? PrefValue.BoolValue)?.value == true
        val e0Present = E0_IDENTITY_KEYS.any { it in values }

        for ((key, value) in values) {
            when {
                key in E0_IDENTITY_KEYS -> {
                    if (migrationDone) {
                        warnings += "E0_KEY_AFTER_MIGRATION:$key" // keep in the file, never copy
                    } else {
                        val s = (value as? PrefValue.StringValue)?.value
                        if (!s.isNullOrEmpty()) {
                            secrets[key] = s
                            sources[key] = key
                        }
                    }
                }
                key in RQR_ROUTES -> {
                    val s = (value as? PrefValue.StringValue)?.value
                    val target = RQR_ROUTES.getValue(key)
                    if (!s.isNullOrEmpty()) {
                        secrets[target] = s
                        sources[target] = key
                    }
                }
                key == LOCAL_PROFILE_PICTURE || key == DIAGNOSTIC_LOG || key == DIAGNOSTIC_VERBOSE -> Unit
                isObsolete(key) -> skipped += key
                else -> copy[key] = value
            }
        }
        if (e0Present && !migrationDone) copy[SECURE_MIGRATION_DONE] = PrefValue.BoolValue(true)
        prefs.undecodable.forEach { warnings += "UNDECODABLE_PREF:$it" }

        return PrefsPlan(
            copy = copy,
            plaintextSecrets = secrets,
            plaintextSourceKeys = sources,
            profilePicturePath = (values[LOCAL_PROFILE_PICTURE] as? PrefValue.StringValue)?.value?.takeIf { it.isNotEmpty() },
            diagnosticLog = (values[DIAGNOSTIC_LOG] as? PrefValue.StringValue)?.value,
            diagnosticVerbose = (values[DIAGNOSTIC_VERBOSE] as? PrefValue.BoolValue)?.value,
            skipped = skipped,
            referencedNpubs = NPUB_REFERENCES.mapNotNull { (values[it] as? PrefValue.StringValue)?.value?.trim()?.takeIf(String::isNotEmpty) }.toSet(),
            warnings = warnings,
        )
    }
}
