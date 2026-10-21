package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.identity.IdentityRepository
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.keys.IdentityRecovery
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultIdentityRepository @Inject constructor(
    private val signing: SigningService,
    private val keyStore: SecureKeyStore,
    private val vault: LocalKeyVault,
    private val recovery: IdentityRecovery,
) : IdentityRepository {

    override suspend fun wipeAll(): Boolean {
        var ok = true
        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ok = false
                AppLogger.warn("IdentityReset", "$name fehlgeschlagen", e)
            }
        }
        step("Signer trennen") { signing.disconnectNip46() }
        step("Schlüssel löschen") { keyStore.deleteKeys() }
        step("Sitzungsschlüssel löschen") { keyStore.deleteNip46ClientKey() }
        step("Tresor leeren") { vault.clearAll() }
        // Recovery copies from the Flutter migration (spec 14 §4.8) are full key copies too.
        step("Gesicherte Alt-Identitäten löschen") { recovery.discardAll() }
        return ok
    }
}
