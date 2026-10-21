package space.einundzwanzig.meetup.data.migration.internal

import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.migration.legacy.LegacySecret
import space.einundzwanzig.meetup.data.migration.legacy.SecureKeyInventory

/** Every source of Flutter secrets, already decrypted (spec 14 §4.7 step 5). */
internal data class SecretSources(
    /** Custom-cipher main entries (E2 GCM or E1 CBC fallback). */
    val main: Map<String, LegacySecret> = emptyMap(),
    /** `_BACKUP` entries of an interrupted 10.x migration. */
    val backup: Map<String, LegacySecret> = emptyMap(),
    /** EncryptedSharedPreferences entries (E1 / F1 orphans). */
    val esp: Map<String, String> = emptyMap(),
    /**
     * Keys with a custom-cipher entry (main or `_BACKUP`) that exists but could not be decrypted
     * (no candidate key opened it, safe mode). Such an entry is newer than any ESP value (E1→E2
     * device), so an ESP value never replaces it: the key stays unresolved (pending) and the ESP
     * value is only parked under `legacy_esp/<key>`.
     */
    val undecryptable: Set<String> = emptySet(),
    /** Plaintext prefs routed to secure keys (E0 identity keys, legacy rolling-QR secrets): they overwrite, as Dart did. */
    val plaintext: Map<String, String> = emptyMap(),
    /** npubs the Flutter prefs still reference (`nostr`, `amber_npub`, `nip46_npub`). */
    val referencedNpubs: Set<String> = emptySet(),
)

/** Resolution result: what to write where (spec 14 §4.7 step 6). */
internal data class SecretPlan(
    /** flutter_secure_storage key → value for the same key in `SecureStore`. */
    val primary: Map<String, LegacySecret>,
    /** Recovery name (`legacy_esp/…`, `legacy_backup/…`) → value. */
    val recovery: Map<String, String>,
    /** Warning codes (names only). */
    val warnings: List<String>,
)

/** Conflict and recovery rules of spec 14 §4.8. Pure; never logs. */
internal object SecretResolver {

    fun resolve(s: SecretSources): SecretPlan {
        val primary = LinkedHashMap<String, LegacySecret>()
        val recovery = LinkedHashMap<String, String>()
        val warnings = mutableListOf<String>()
        val espIdentityAccepted = espIdentityMatches(s.esp, s.referencedNpubs)

        val keys = LinkedHashSet<String>().apply {
            addAll(s.plaintext.keys); addAll(s.main.keys); addAll(s.backup.keys); addAll(s.esp.keys)
        }
        for (key in keys) {
            val plain = s.plaintext[key]?.takeIf { it.isNotEmpty() }
            val main = s.main[key]
            val backup = s.backup[key]
            val esp = s.esp[key]
            val chosen: LegacySecret? = when {
                // E0 plaintext and secure_migration_done != true: Dart overwrote secure storage with it.
                plain != null -> LegacySecret(plain, LegacySecret.Source.PLAINTEXT_PREFS)
                main != null -> main
                backup != null -> backup
                esp != null && key !in s.undecryptable &&
                    (key !in SecureKeyInventory.IDENTITY_KEYS || espIdentityAccepted) ->
                    LegacySecret(esp, LegacySecret.Source.ESP)
                else -> null
            }
            if (chosen != null) primary[key] = chosen
            if (chosen == null && esp != null && key in s.undecryptable) warnings += "ESP_HELD_BACK:$key"
            // Every other distinct value is kept under a recovery name, never dropped.
            if (plain != null && main != null && main.value != plain) {
                recovery[recoveryName(RecoveryNames.BACKUP, key)] = main.value
                warnings += "PLAINTEXT_OVERRODE_SECURE:$key"
            }
            if (backup != null && chosen !== backup && backup.value != chosen?.value) {
                val backupName = recoveryName(RecoveryNames.BACKUP, key)
                when (recovery[backupName]) {
                    null -> recovery[backupName] = backup.value
                    backup.value -> Unit
                    // The slot already holds the main value the plaintext overrode: never drop a third value.
                    else -> recovery[recoveryName(RecoveryNames.RECOVERED, key)] = backup.value
                }
            }
            if (esp != null && chosen?.source != LegacySecret.Source.ESP && esp != chosen?.value) {
                recovery[recoveryName(RecoveryNames.ESP, key)] = esp
            }
        }
        // Plausibility of authenticated / plaintext values: flag, never reject (§4.6.5).
        for ((key, secret) in primary) {
            if (!SecureKeyInventory.isPlausible(key, secret.value)) warnings += "IMPLAUSIBLE:$key"
        }
        val nsec = primary["nostr_nsec_key"]?.value
        val npub = primary["nostr_npub_key"]?.value
        val priv = primary["nostr_priv_hex"]?.value
        if (nsec != null && npub != null && priv != null && !Keys.backupKeyConsistent(nsec, npub, priv)) {
            warnings += "IDENTITY_INCONSISTENT"
        }
        return SecretPlan(primary, recovery, warnings)
    }

    /** Secure-store recovery name, read by `IdentityRecovery` and the badge key scan (spec 14 §4.8). */
    fun recoveryName(source: RecoveryNames, key: String): String = source.of(key)

    /**
     * The npub the ESP identity group belongs to: ESP `nostr_npub_key`, else derived from ESP
     * `nostr_nsec_key` / `nostr_priv_hex`, else ESP `easyauth_password_wrap_npub` /
     * `portal_api_token_npub`.
     */
    fun espGroupNpub(esp: Map<String, String>): String? {
        esp["nostr_npub_key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        esp["nostr_nsec_key"]?.let { nsec -> npubFromPrivHex(runCatching { Nip19.decodeNsec(nsec.trim()) }.getOrNull())?.let { return it } }
        npubFromPrivHex(esp["nostr_priv_hex"])?.let { return it }
        esp["easyauth_password_wrap_npub"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return esp["portal_api_token_npub"]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun espIdentityMatches(esp: Map<String, String>, referenced: Set<String>): Boolean {
        if (esp.keys.none { it in SecureKeyInventory.IDENTITY_KEYS }) return false
        val npub = espGroupNpub(esp) ?: return false
        return npub in referenced
    }

    private fun npubFromPrivHex(hex: String?): String? {
        val h = hex?.trim()?.lowercase()?.takeIf { Keys.isValidPrivHex(it) } ?: return null
        return runCatching { Nip19.encodeNpub(Keys.pubHexFromPriv(h)) }.getOrNull()
    }
}
