package space.einundzwanzig.meetup.data.backup.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import javax.inject.Inject

// Narrow seams onto the other domains (only what the backup needs), so the export/restore logic
// is testable with small fakes. Production adapters delegate 1:1.

/** Signer + key store (Dart `SigningService` / `SecureKeyStore` calls of `BackupService`). */
internal interface BackupIdentity {
    suspend fun mode(): SigningMode
    suspend fun npub(): String?
    suspend fun isExternalSigner(): Boolean

    /** `nip46SessionForBackup()?['bunker_uri']` (never the client session key). */
    suspend fun nip46BunkerUri(): String?
    suspend fun restoreAmber(npub: String)
    suspend fun restoreNip07(npub: String)

    /** Without a client key: the restored bunker identity cannot sign until re-paired. */
    suspend fun restoreNip46(npub: String, bunkerUri: String)
    suspend fun useLocalMode()
    suspend fun keystoreNsec(): String?
    suspend fun keystoreNpub(): String?
    suspend fun keystorePrivHex(): String?
    suspend fun saveKeys(nsec: String, npub: String, privHex: String)
}

/** Admin registry cache (Dart `AdminRegistry`). */
internal interface BackupAdmins {
    suspend fun getAdminList(): List<AdminEntry>
    suspend fun addAdmin(entry: AdminEntry)
    suspend fun forceRefresh(): Int
}

/** Platform proofs + humanity proof (Dart `PlatformProofService` / `HumanityProofService` / prefs). */
internal interface BackupProofs {
    /** Stored platform proofs as backup entries `{platform, username, proof_sig, created_at}`. */
    suspend fun platformProofEntries(): List<JsonObject>

    /** Replace mode: writes the restorable entries if any. @return `false` if the write failed. */
    suspend fun replacePlatformProofs(validated: List<JsonElement>): Boolean

    /** Additive mode: union with the device list (device wins). @return `false` if it failed. */
    suspend fun unionPlatformProofs(validated: List<JsonElement>): Boolean

    /** `humanity_proof` backup object (`verified` is `false` while re-verification is pending). */
    suspend fun humanityBackupJson(): JsonObject

    /** Raw pref `humanity_verified == true` (Dart reads the pref, not `getStatus`). */
    suspend fun humanityVerifiedPref(): Boolean

    /** Writes the restored humanity proof as pending re-verification. */
    suspend fun restoreHumanity(proof: JsonObject, additive: Boolean): Boolean
}

internal class SigningBackupIdentity @Inject constructor(
    private val signing: SigningService,
    private val keys: SecureKeyStore,
) : BackupIdentity {
    override suspend fun mode(): SigningMode = signing.getMode()
    override suspend fun npub(): String? = signing.npub()
    override suspend fun isExternalSigner(): Boolean = signing.isExternalSigner()
    override suspend fun nip46BunkerUri(): String? = signing.nip46SessionForBackup()?.get("bunker_uri")
    override suspend fun restoreAmber(npub: String) = signing.restoreAmber(npub)
    override suspend fun restoreNip07(npub: String) = signing.restoreNip07(npub)
    override suspend fun restoreNip46(npub: String, bunkerUri: String) = signing.restoreNip46(npub, bunkerUri, null)
    override suspend fun useLocalMode() = signing.useLocalMode()
    override suspend fun keystoreNsec(): String? = keys.getNsec()
    override suspend fun keystoreNpub(): String? = keys.getNpub()
    override suspend fun keystorePrivHex(): String? = keys.getPrivHex()
    override suspend fun saveKeys(nsec: String, npub: String, privHex: String) = keys.saveKeys(nsec, npub, privHex)
}

internal class RegistryBackupAdmins @Inject constructor(
    private val registry: AdminRegistry,
) : BackupAdmins {
    override suspend fun getAdminList(): List<AdminEntry> = registry.getAdminList()
    override suspend fun addAdmin(entry: AdminEntry) = registry.addAdmin(entry)
    override suspend fun forceRefresh(): Int = registry.forceRefresh()
}

internal class RepositoryBackupProofs @Inject constructor(
    private val platform: PlatformProofRepository,
    private val humanity: HumanityProofRepository,
    private val prefs: KeyValueStore,
) : BackupProofs {
    override suspend fun platformProofEntries(): List<JsonObject> = platform.getSavedProofs().map { it.toStorageJson() }
    override suspend fun replacePlatformProofs(validated: List<JsonElement>): Boolean = platform.replaceAllRaw(validated)
    override suspend fun unionPlatformProofs(validated: List<JsonElement>): Boolean = platform.unionRaw(validated)
    override suspend fun humanityBackupJson(): JsonObject = humanity.getStatus().toBackupJson()
    override suspend fun humanityVerifiedPref(): Boolean = prefs.getBool(HumanityKeys.VERIFIED) == true
    override suspend fun restoreHumanity(proof: JsonObject, additive: Boolean): Boolean =
        humanity.restoreFromBackup(proof, additive)
}
