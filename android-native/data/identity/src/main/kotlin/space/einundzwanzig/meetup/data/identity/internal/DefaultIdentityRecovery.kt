package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Nip49
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.KeyFormatException
import space.einundzwanzig.meetup.data.identity.SecureStorageUnreadableException
import space.einundzwanzig.meetup.data.identity.keys.GeneratedKeys
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.keys.IdentityRecovery
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.RecoveryCandidate
import space.einundzwanzig.meetup.data.identity.keys.RecoverySource
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultIdentityRecovery @Inject constructor(
    private val secure: SecureStore,
    private val keyStore: SecureKeyStore,
    private val keys: NostrKeyService,
    @param:DefaultDispatcher private val cpu: CoroutineDispatcher,
) : IdentityRecovery {

    /** The readable identity entries of one source, keyed by the Flutter name. */
    private class Group(val source: RecoverySource, val values: Map<String, String>) {
        val privHex: String? = values[IdentityKeys.NSEC]?.let { nsec ->
            runCatching { Nip19.decodeNsec(nsec.dartTrim()) }.getOrNull()
        }?.takeIf(Keys::isValidPrivHex)
            ?: values[IdentityKeys.PRIV_HEX]?.lowercase()?.takeIf(Keys::isValidPrivHex)

        val wrap: String? = values[IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC]?.takeIf { it.isNotEmpty() }

        /**
         * The identity this group restores: derived from the plain key; for a password wrap the
         * wrap's own binding comes first (a stray `nostr_npub_key` may belong to another identity,
         * `legacy_recovered/` collects values of several retries). [adopt] checks it after decrypting.
         */
        val npub: String? = privHex?.let { Nip19.encodeNpub(Keys.pubHexFromPriv(it)) }
            ?: wrap?.let { values[IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB]?.takeIf { it.startsWith("npub1") } }
            ?: values[IdentityKeys.NPUB]?.takeIf { it.startsWith("npub1") }
    }

    private suspend fun groups(): List<Group> {
        val all = secure.readAllChecked().getOrElse { e ->
            throw SecureStorageUnreadableException("legacy_*", isPermanent = false, cause = e)
        }
        return RecoverySource.entries.mapNotNull { source ->
            val values = IdentityKeys.IDENTITY_SECRET_NAMES.mapNotNull { name ->
                all[IdentityKeys.recoveryName(source.prefix, name)]?.let { name to it }
            }.toMap()
            if (values.isEmpty()) null else Group(source, values)
        }
    }

    override suspend fun candidates(): List<RecoveryCandidate> {
        val active = try {
            keyStore.getNpub()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return groups().mapNotNull { g ->
            val npub = g.npub ?: return@mapNotNull null
            if (g.privHex == null && g.wrap == null) return@mapNotNull null
            if (npub == active) return@mapNotNull null
            RecoveryCandidate(g.source, npub, hasPrivateKey = g.privHex != null, needsPassword = g.privHex == null)
        }
    }

    private suspend fun group(candidate: RecoveryCandidate): Group =
        groups().firstOrNull { it.source == candidate.source && it.npub == candidate.npub }
            ?: throw KeyFormatException("Die gesicherte Identität ist nicht mehr vorhanden.")

    override suspend fun adopt(candidate: RecoveryCandidate, password: String?): GeneratedKeys {
        val g = group(candidate)
        val priv = g.privHex
        val adopted = when {
            priv != null -> keys.importPrivHex(priv)
            g.wrap != null && password != null -> {
                // Decrypt first and import only the identity the notice showed: a wrap of another
                // identity must neither replace the active key nor be installed under a wrong npub.
                val wrapped = withContext(cpu) { Nip49.decrypt(g.wrap.dartTrim(), password) }
                AppLogger.registerSecret(wrapped)
                if (!Keys.isValidPrivHex(wrapped) || Nip19.encodeNpub(Keys.pubHexFromPriv(wrapped)) != candidate.npub) {
                    throw KeyFormatException("Die gesicherte Identität passt nicht zur angezeigten Identität.")
                }
                keys.importPrivHex(wrapped)
            }
            g.wrap != null -> throw KeyFormatException("Für diese Identität wird das Passwort benötigt.")
            else -> throw KeyFormatException("Die gesicherte Identität enthält keinen Schlüssel.")
        }
        AppLogger.security("IdentityRecovery", "Gesicherte Identität übernommen (${candidate.source.prefix}).")
        deleteSource(candidate.source)
        return adopted
    }

    override suspend fun exportNsec(candidate: RecoveryCandidate): String {
        val priv = group(candidate).privHex
            ?: throw KeyFormatException("Die gesicherte Identität enthält keinen Klartext-Schlüssel.")
        AppLogger.registerSecret(priv)
        return Nip19.encodeNsec(priv).also(AppLogger::registerSecret)
    }

    override suspend fun discard(candidate: RecoveryCandidate) {
        deleteSource(candidate.source)
        AppLogger.info("IdentityRecovery", "Gesicherte Identität verworfen (${candidate.source.prefix}).")
    }

    override suspend fun discardAll() {
        // Deterministic names first (covers unreadable entries, which readAll does not list),
        // then anything else listed under a recovery prefix with an identity name.
        val names = LinkedHashSet<String>()
        for (prefix in IdentityKeys.RECOVERY_PREFIXES) {
            for (name in IdentityKeys.IDENTITY_SECRET_NAMES) names += IdentityKeys.recoveryName(prefix, name)
        }
        secure.readAllChecked().getOrNull()?.keys
            ?.filter { k -> k.startsWith("legacy_") && k.substringAfter('/') in IdentityKeys.IDENTITY_SECRET_NAMES }
            ?.let(names::addAll)
        val failed = names.filterNot { secure.delete(it) }
        if (failed.isNotEmpty()) {
            throw IdentityStorageException("Sicherer Speicher: ${failed.joinToString()} konnten nicht gelöscht werden.")
        }
    }

    private suspend fun deleteSource(source: RecoverySource) {
        for (name in IdentityKeys.IDENTITY_SECRET_NAMES) secure.deleteOrThrow(IdentityKeys.recoveryName(source.prefix, name))
    }
}
