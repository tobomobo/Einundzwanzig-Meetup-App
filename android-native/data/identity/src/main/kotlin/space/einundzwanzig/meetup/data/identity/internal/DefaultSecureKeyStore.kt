package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.keys.StoredKeys
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultSecureKeyStore @Inject constructor(
    private val secure: SecureStore,
    private val prefs: KeyValueStore,
    private val changes: IdentityChanges,
    private val identitySwitch: IdentitySwitchBarrier? = null,
) : SecureKeyStore {

    private val migrationMutex = Mutex()

    @Volatile
    private var migrated = false

    override suspend fun ensureMigrated() {
        if (migrated) return
        migrationMutex.withLock {
            if (migrated) return
            if (prefs.getBool(IdentityKeys.SECURE_MIGRATION_DONE) != true) {
                val legacy = listOf(IdentityKeys.NSEC, IdentityKeys.NPUB, IdentityKeys.PRIV_HEX)
                    .mapNotNull { key -> prefs.getString(key)?.takeIf { it.isNotEmpty() }?.let { key to it } }
                    .toMap()
                if (legacy.isNotEmpty()) {
                    secure.writeAllOrThrow(legacy)
                    legacy[IdentityKeys.PRIV_HEX]?.let(AppLogger::registerSecret)
                }
                val cleaned = prefs.edit {
                    remove(IdentityKeys.NSEC)
                    remove(IdentityKeys.NPUB)
                    remove(IdentityKeys.PRIV_HEX)
                    setBool(IdentityKeys.SECURE_MIGRATION_DONE, true)
                }
                if (!cleaned) throw IdentityStorageException("Klartext-Schlüssel konnten nicht aus den Einstellungen entfernt werden.")
                if (legacy.isNotEmpty()) changes.notifyChanged()
                if (legacy.containsKey(IdentityKeys.NSEC)) {
                    AppLogger.debug("SecureKeyStore", "Migration abgeschlossen: Keys aus SharedPreferences entfernt.")
                }
            }
            migrated = true
        }
    }

    override suspend fun saveKeys(nsec: String, npub: String, privHex: String) {
        ensureMigrated()
        identitySwitch?.beforeChange(npub)
        secure.writeAllOrThrow(
            linkedMapOf(IdentityKeys.NSEC to nsec, IdentityKeys.NPUB to npub, IdentityKeys.PRIV_HEX to privHex),
        )
        AppLogger.registerSecret(privHex)
        AppLogger.registerSecret(nsec)
        changes.notifyChanged()
    }

    override suspend fun getNsec(): String? {
        ensureMigrated()
        return secure.readOrThrow(IdentityKeys.NSEC)
    }

    override suspend fun getNpub(): String? {
        ensureMigrated()
        return secure.readOrThrow(IdentityKeys.NPUB)
    }

    override suspend fun getPrivHex(): String? {
        ensureMigrated()
        return secure.readOrThrow(IdentityKeys.PRIV_HEX)?.also { if (it.isNotEmpty()) AppLogger.registerSecret(it) }
    }

    override suspend fun loadKeys(): StoredKeys? {
        ensureMigrated()
        val nsec = secure.readOrThrow(IdentityKeys.NSEC)
        val npub = secure.readOrThrow(IdentityKeys.NPUB)
        return if (nsec == null || npub == null) null else StoredKeys(nsec, npub)
    }

    override suspend fun hasKey(): Boolean {
        ensureMigrated()
        return !secure.readOrThrow(IdentityKeys.NSEC).isNullOrEmpty()
    }

    override suspend fun deleteKeys() {
        ensureMigrated()
        identitySwitch?.beforeChange(null)
        try {
            secure.deleteOrThrow(IdentityKeys.NSEC)
            secure.deleteOrThrow(IdentityKeys.NPUB)
            secure.deleteOrThrow(IdentityKeys.PRIV_HEX)
        } finally {
            changes.notifyChanged()
        }
    }

    override suspend fun saveNip46ClientKey(privHex: String) {
        ensureMigrated()
        secure.writeOrThrow(IdentityKeys.NIP46_CLIENT_SK, privHex)
        AppLogger.registerSecret(privHex)
    }

    override suspend fun getNip46ClientKey(): String? {
        ensureMigrated()
        return secure.readOrThrow(IdentityKeys.NIP46_CLIENT_SK)
    }

    override suspend fun deleteNip46ClientKey() {
        ensureMigrated()
        secure.deleteOrThrow(IdentityKeys.NIP46_CLIENT_SK)
    }
}
