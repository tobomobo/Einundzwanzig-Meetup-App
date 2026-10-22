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
    private class Group(val source: RecoverySource, val slot: Int, val values: Map<String, String>) {
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
        return RecoverySource.entries.flatMap { source ->
            (1..16).flatMap { slot ->
                val values = IdentityKeys.IDENTITY_SECRET_NAMES.mapNotNull { name ->
                    all[recoveryName(source, name, slot)]?.let { name to it }
                }.toMap()
                // Overflow slots are allocated per key name, so a slot may contain keys from
                // different identities. Split by the actual key instead of deleting the slot.
                val plain = linkedMapOf<String, MutableMap<String, String>>()
                for (name in listOf(IdentityKeys.NSEC, IdentityKeys.PRIV_HEX)) {
                    val value = values[name] ?: continue
                    val priv = if (name == IdentityKeys.NSEC) runCatching { Nip19.decodeNsec(value.dartTrim()) }.getOrNull() else value.lowercase()
                    if (priv == null || !Keys.isValidPrivHex(priv)) continue
                    val npub = Nip19.encodeNpub(Keys.pubHexFromPriv(priv))
                    plain.getOrPut(npub) { linkedMapOf() }[name] = value
                }
                val wrapNpub = values[IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB] ?: values[IdentityKeys.NPUB]
                val wrap = values[IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC]
                val result = plain.map { (npub, entries) ->
                    if (values[IdentityKeys.NPUB] == npub) {
                        entries[IdentityKeys.NPUB] = npub
                        for (name in listOf(IdentityKeys.EASYAUTH_PASSKEY_NCRYPTSEC, IdentityKeys.EASYAUTH_PASSKEY_CREDENTIAL_ID)) {
                            values[name]?.let { entries[name] = it }
                        }
                    }
                    if (wrapNpub == npub && wrap != null) {
                        entries[IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC] = wrap
                        values[IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB]?.let { entries[IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB] = it }
                    }
                    Group(source, slot, entries)
                }.toMutableList()
                if (wrap != null && wrapNpub !in plain) {
                    val entries = values.filterKeys { it == IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC || it == IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB }
                        .toMutableMap()
                    if (values[IdentityKeys.EASYAUTH_PASSWORD_WRAP_NPUB] == null) values[IdentityKeys.NPUB]?.let { entries[IdentityKeys.NPUB] = it }
                    result += Group(source, slot, entries)
                }
                result
            }
        }
    }

    private fun recoveryName(source: RecoverySource, name: String, slot: Int): String =
        IdentityKeys.recoveryName(source.prefix, name) + if (slot == 1) "" else "#$slot"

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
            RecoveryCandidate(g.source, npub, hasPrivateKey = g.privHex != null, needsPassword = g.privHex == null, slot = g.slot)
        }
    }

    private suspend fun group(candidate: RecoveryCandidate): Group =
        groups().firstOrNull { it.source == candidate.source && it.slot == candidate.slot && it.npub == candidate.npub }
            ?: throw KeyFormatException("Die gesicherte Identität ist nicht mehr vorhanden.")

    override suspend fun adopt(candidate: RecoveryCandidate, password: String?): GeneratedKeys {
        val g = group(candidate)
        val priv = g.privHex
        val adopted = when {
            priv != null -> keys.importPrivHex(priv)
            g.wrap != null && password != null -> {
                // Decrypt first and import only the identity the notice showed: a wrap of another
                // identity must neither replace the active key nor be installed under a wrong npub.
                val wrapped = withContext(cpu) { keyCrypto { Nip49.decrypt(g.wrap.dartTrim(), password) } }
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
        deleteSlot(candidate)
        return adopted
    }

    override suspend fun exportNsec(candidate: RecoveryCandidate): String {
        val priv = group(candidate).privHex
            ?: throw KeyFormatException("Die gesicherte Identität enthält keinen Klartext-Schlüssel.")
        AppLogger.registerSecret(priv)
        return Nip19.encodeNsec(priv).also(AppLogger::registerSecret)
    }

    override suspend fun exportNcryptsec(candidate: RecoveryCandidate): String =
        (group(candidate).wrap ?: throw KeyFormatException("No encrypted recovery key"))
            .also(AppLogger::registerSecret)

    override suspend fun discard(candidate: RecoveryCandidate) {
        deleteSlot(candidate)
        AppLogger.info("IdentityRecovery", "Gesicherte Identität verworfen (${candidate.source.prefix}).")
    }

    override suspend fun discardAll() {
        // Deterministic names first (covers unreadable entries, which readAll does not list),
        // then anything else listed under a recovery prefix with an identity name.
        val names = LinkedHashSet<String>()
        for (prefix in IdentityKeys.RECOVERY_PREFIXES) {
            for (name in IdentityKeys.IDENTITY_SECRET_NAMES) {
                for (slot in 1..16) names += IdentityKeys.recoveryName(prefix, name) + if (slot == 1) "" else "#$slot"
            }
        }
        secure.readAllChecked().getOrNull()?.keys
            ?.filter { k -> k.startsWith("legacy_") && k.substringAfter('/').substringBefore('#') in IdentityKeys.IDENTITY_SECRET_NAMES }
            ?.let(names::addAll)
        val failed = names.filterNot { secure.delete(it) }
        if (failed.isNotEmpty()) {
            throw IdentityStorageException("Sicherer Speicher: ${failed.joinToString()} konnten nicht gelöscht werden.")
        }
    }

    private suspend fun deleteSlot(candidate: RecoveryCandidate) {
        for (name in group(candidate).values.keys) secure.deleteOrThrow(recoveryName(candidate.source, name, candidate.slot))
    }
}
