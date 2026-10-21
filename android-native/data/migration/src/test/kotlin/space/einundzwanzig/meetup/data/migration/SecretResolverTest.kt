package space.einundzwanzig.meetup.data.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.migration.internal.SecretResolver
import space.einundzwanzig.meetup.data.migration.internal.SecretSources
import space.einundzwanzig.meetup.data.migration.legacy.LegacySecret
import space.einundzwanzig.meetup.data.migration.legacy.LegacySecret.Source

/** Spec 14 §4.8 conflict and recovery rules (fixture `migration_routing` rows 7–9). */
class SecretResolverTest {
    private val old = KeyPair.fromPrivateKeyHex("1".repeat(64))
    private val new = KeyPair.fromPrivateKeyHex("2".repeat(64))
    private val hexA = "a".repeat(64)
    private val hexB = "b".repeat(64)

    private fun gcm(v: String) = LegacySecret(v, Source.GCM)

    @Test
    fun mainCustomEntryIsPrimary() {
        val plan = SecretResolver.resolve(SecretSources(main = mapOf("badge_encryption_key" to gcm(hexA))))
        assertEquals(gcm(hexA), plan.primary["badge_encryption_key"])
        assertTrue(plan.recovery.isEmpty())
    }

    @Test
    fun backupIsPrimaryWhenMainMissing() {
        val b = LegacySecret(hexA, Source.CBC_BACKUP)
        val plan = SecretResolver.resolve(SecretSources(backup = mapOf("badge_encryption_key" to b)))
        assertEquals(b, plan.primary["badge_encryption_key"])
        assertTrue(plan.recovery.isEmpty())
    }

    @Test
    fun differingBackupGoesToLegacyBackup() {
        val plan = SecretResolver.resolve(
            SecretSources(
                main = mapOf("badge_encryption_key" to gcm(hexA)),
                backup = mapOf("badge_encryption_key" to LegacySecret(hexB, Source.CBC_BACKUP)),
            ),
        )
        assertEquals(hexA, plan.primary["badge_encryption_key"]?.value)
        assertEquals(mapOf("legacy_backup/badge_encryption_key" to hexB), plan.recovery)
    }

    @Test
    fun equalBackupIsNotDuplicated() {
        val plan = SecretResolver.resolve(
            SecretSources(main = mapOf("k" to gcm("v")), backup = mapOf("k" to LegacySecret("v", Source.GCM_BACKUP))),
        )
        assertTrue(plan.recovery.isEmpty())
    }

    @Test
    fun e0PlaintextWinsAndKeepsTheOverwrittenValue() {
        val plan = SecretResolver.resolve(
            SecretSources(
                main = mapOf("nostr_nsec_key" to gcm(old.nsec)),
                plaintext = mapOf("nostr_nsec_key" to new.nsec),
            ),
        )
        assertEquals(LegacySecret(new.nsec, Source.PLAINTEXT_PREFS), plan.primary["nostr_nsec_key"])
        assertEquals(old.nsec, plan.recovery["legacy_backup/nostr_nsec_key"])
        assertTrue("PLAINTEXT_OVERRODE_SECURE:nostr_nsec_key" in plan.warnings)
    }

    @Test
    fun espIdentityBecomesPrimaryOnlyWhenItsNpubIsStillReferenced() {
        // F1 loss case: the Flutter 1.7.x app shows an empty identity, prefs still reference the old npub.
        val esp = mapOf("nostr_nsec_key" to old.nsec, "nostr_priv_hex" to old.privateKeyHex, "badge_encryption_key" to hexA)
        val matched = SecretResolver.resolve(SecretSources(esp = esp, referencedNpubs = setOf(old.npub)))
        assertEquals(LegacySecret(old.nsec, Source.ESP), matched.primary["nostr_nsec_key"])
        assertEquals(LegacySecret(old.privateKeyHex, Source.ESP), matched.primary["nostr_priv_hex"])
        assertTrue(matched.recovery.isEmpty())

        // The user reset / switched identity: never resurrect it automatically (R5).
        val other = SecretResolver.resolve(SecretSources(esp = esp, referencedNpubs = setOf(new.npub)))
        assertNull(other.primary["nostr_nsec_key"])
        assertEquals(old.nsec, other.recovery["legacy_esp/nostr_nsec_key"])
        assertEquals(old.privateKeyHex, other.recovery["legacy_esp/nostr_priv_hex"])
        // Badge keys: primary when absent elsewhere.
        assertEquals(LegacySecret(hexA, Source.ESP), other.primary["badge_encryption_key"])
    }

    @Test
    fun espGroupNpubPrecedence() {
        assertEquals(new.npub, SecretResolver.espGroupNpub(mapOf("nostr_npub_key" to new.npub, "nostr_nsec_key" to old.nsec)))
        assertEquals(old.npub, SecretResolver.espGroupNpub(mapOf("nostr_nsec_key" to old.nsec, "easyauth_password_wrap_npub" to new.npub)))
        assertEquals(new.npub, SecretResolver.espGroupNpub(mapOf("easyauth_password_wrap_npub" to new.npub)))
        assertEquals(new.npub, SecretResolver.espGroupNpub(mapOf("portal_api_token_npub" to new.npub)))
        assertNull(SecretResolver.espGroupNpub(mapOf("nip46_client_sk" to hexA)))
    }

    @Test
    fun espPortalTokenFollowsTheIdentityRule() {
        val esp = mapOf("portal_api_token" to "1|t", "portal_api_token_npub" to old.npub)
        assertEquals("1|t", SecretResolver.resolve(SecretSources(esp = esp, referencedNpubs = setOf(old.npub))).primary["portal_api_token"]?.value)
        val parked = SecretResolver.resolve(SecretSources(esp = esp, referencedNpubs = emptySet()))
        assertNull(parked.primary["portal_api_token"])
        assertEquals("1|t", parked.recovery["legacy_esp/portal_api_token"])
    }

    @Test
    fun differingCustomValueBeatsEspWhichIsParked() {
        val plan = SecretResolver.resolve(
            SecretSources(
                main = mapOf("nostr_nsec_key" to gcm(new.nsec), "badge_encryption_key" to gcm(hexB)),
                esp = mapOf("nostr_nsec_key" to old.nsec, "badge_encryption_key" to hexA),
                referencedNpubs = setOf(old.npub),
            ),
        )
        assertEquals(new.nsec, plan.primary["nostr_nsec_key"]?.value)
        assertEquals(hexB, plan.primary["badge_encryption_key"]?.value)
        assertEquals(old.nsec, plan.recovery["legacy_esp/nostr_nsec_key"])
        assertEquals(hexA, plan.recovery["legacy_esp/badge_encryption_key"])
    }

    @Test
    fun equalEspValueIsNotParked() {
        val plan = SecretResolver.resolve(SecretSources(main = mapOf("badge_pending_key" to gcm(hexA)), esp = mapOf("badge_pending_key" to hexA)))
        assertTrue(plan.recovery.isEmpty())
    }

    @Test
    fun implausibleAndInconsistentValuesAreFlaggedNotRejected() {
        val plan = SecretResolver.resolve(
            SecretSources(
                main = mapOf(
                    "nostr_nsec_key" to gcm(old.nsec),
                    "nostr_priv_hex" to gcm(old.privateKeyHex),
                    "nostr_npub_key" to gcm(new.npub),
                    "badge_pending_key" to gcm("not-hex"),
                ),
            ),
        )
        assertEquals(4, plan.primary.size)
        assertTrue("IDENTITY_INCONSISTENT" in plan.warnings)
        assertTrue("IMPLAUSIBLE:badge_pending_key" in plan.warnings)
        val consistent = SecretResolver.resolve(
            SecretSources(main = mapOf("nostr_nsec_key" to gcm(old.nsec), "nostr_priv_hex" to gcm(old.privateKeyHex), "nostr_npub_key" to gcm(old.npub))),
        )
        assertFalse("IDENTITY_INCONSISTENT" in consistent.warnings)
        assertTrue(consistent.warnings.isEmpty())
    }

    @Test
    fun recoveryNamesNeverCollideWithFlutterNames() {
        val plan = SecretResolver.resolve(
            SecretSources(main = mapOf("k" to gcm("1")), backup = mapOf("k" to LegacySecret("2", Source.GCM_BACKUP)), esp = mapOf("k" to "3")),
        )
        assertEquals(setOf("legacy_backup/k", "legacy_esp/k"), plan.recovery.keys)
        assertTrue(plan.recovery.keys.all { '/' in it })
    }
}
