package space.einundzwanzig.meetup.feature.onboarding

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.Nip07ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46Pairing
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.easyauth.PasskeyRegistration
import space.einundzwanzig.meetup.data.identity.keys.GeneratedKeys
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.keys.StoredKeys
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.identity.review.AppReviewSignIn
import space.einundzwanzig.meetup.data.identity.signer.NostrSigner
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.migration.MigrationGate
import space.einundzwanzig.meetup.data.settings.locale.LocaleController
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.onboarding.setup.KeyFileWriter

class FakeSigningService : SigningService {
    var path = RecommendedExistingPath.BUNKER
    var amberResult: AmberConnectResult = AmberConnectResult.Success("ab".repeat(32), "npub1amber")
    var bunkerResult: Nip46ConnectResult = Nip46ConnectResult.Success("cd".repeat(32), "npub1bunker")

    /** When set, `connectNip46Bunker` suspends until completed. */
    var bunkerGate: CompletableDeferred<Nip46ConnectResult>? = null
    var useLocalCalls = 0
    val bunkerUris = mutableListOf<String>()
    val authHandlers = mutableListOf<(String) -> Unit>()
    var closedInterceptors = 0

    override val signerReady: SharedFlow<Unit> = MutableSharedFlow()
    override val authUrlEvents: SharedFlow<String> = MutableSharedFlow()
    override fun acknowledgeAuthUrl(url: String) = Unit
    override fun interceptAuthUrls(handler: (String) -> Unit): AutoCloseable {
        authHandlers += handler
        return AutoCloseable {
            authHandlers -= handler
            closedInterceptors++
        }
    }
    override suspend fun getMode(): SigningMode = SigningMode.LOCAL
    override suspend fun isExternalSigner(): Boolean = false
    override suspend fun recommendedExistingPath(): RecommendedExistingPath = path
    override fun isAmberInstalled(): Boolean = path == RecommendedExistingPath.AMBER
    override suspend fun nip07Available(): Boolean = false
    override suspend fun activeSigner(): NostrSigner = error("unused")
    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent = error("unused")
    override suspend fun pubkeyHex(): String? = null
    override suspend fun npub(): String? = null
    override suspend fun canSign(): Boolean = false
    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String = error("unused")
    override suspend fun nip44Decrypt(peerHex: String, payload: String): String = error("unused")
    override suspend fun canNip44(): Boolean = false
    override suspend fun connectAmber(): AmberConnectResult = amberResult
    override suspend fun restoreAmber(npub: String) = Unit
    override suspend fun disconnectAmber() = Unit
    override suspend fun connectNip07(): Nip07ConnectResult = Nip07ConnectResult.Missing
    override suspend fun restoreNip07(npub: String) = Unit
    override suspend fun disconnectNip07() = Unit
    override suspend fun useLocalMode() {
        useLocalCalls++
    }
    override suspend fun connectNip46Bunker(bunkerUri: String): Nip46ConnectResult {
        bunkerUris += bunkerUri
        return bunkerGate?.await() ?: bunkerResult
    }
    override suspend fun startNip46Pairing(): Nip46Pairing = throw IllegalStateException("Relays nicht erreichbar")
    override suspend fun completeNip46Pairing(pairing: Nip46Pairing): Nip46ConnectResult = error("unused")
    override suspend fun nip46SessionAlive(): Boolean = false
    override suspend fun nip46SessionForBackup(): Map<String, String>? = null
    override suspend fun restoreNip46(npub: String, bunkerUri: String, clientSecretKeyHex: String?) = Unit
    override suspend fun disconnectNip46() = Unit
    override suspend fun nip46PairingRelays(): List<String> = emptyList()
}

class FakeSecureKeyStore : SecureKeyStore {
    var hasKey = false
    var failure: Exception? = null
    var ensureMigratedCalls = 0
    override suspend fun ensureMigrated() {
        ensureMigratedCalls++
        failure?.let { throw it }
    }
    override suspend fun saveKeys(nsec: String, npub: String, privHex: String) {
        hasKey = true
    }
    override suspend fun getNsec(): String? = null
    override suspend fun getNpub(): String? = null
    override suspend fun getPrivHex(): String? = null
    override suspend fun loadKeys(): StoredKeys? = null
    override suspend fun hasKey(): Boolean {
        failure?.let { throw it }
        return hasKey
    }
    override suspend fun deleteKeys() {
        hasKey = false
    }
    override suspend fun saveNip46ClientKey(privHex: String) = Unit
    override suspend fun getNip46ClientKey(): String? = null
    override suspend fun deleteNip46ClientKey() = Unit
}

class FakeLocalKeyVault : LocalKeyVault {
    var passwordWrap: String? = null
    var passkeyWrap: String? = null
    var clearAllCalls = 0
    override suspend fun savePasswordWrap(ncryptsec: String, npub: String) {
        passwordWrap = ncryptsec
    }
    override suspend fun getPasswordWrap(): String? = passwordWrap
    override suspend fun getPasswordWrapNpub(): String? = null
    override suspend fun passwordWrapMatchesNpub(npub: String): Boolean = true
    override suspend fun savePasskeyWrap(ncryptsec: String, credentialId: String) {
        passkeyWrap = ncryptsec
    }
    override suspend fun getPasskeyWrap(): String? = passkeyWrap
    override suspend fun getPasskeyCredentialId(): String? = null
    override suspend fun hasPasskeyWrap(): Boolean = passkeyWrap != null
    override suspend fun clearPasskeyWrap() {
        passkeyWrap = null
    }
    override suspend fun clearAll() {
        clearAllCalls++
        passwordWrap = null
        passkeyWrap = null
    }
}

class FakeNostrKeyService : NostrKeyService {
    val imported = mutableListOf<String>()
    var failure: Exception? = null
    private val keys = GeneratedKeys("nsec1x", "npub1x", "00")
    override suspend fun generateKeyPair(): GeneratedKeys = keys
    override suspend fun importNsec(nsec: String): GeneratedKeys {
        failure?.let { throw it }
        imported += nsec
        return keys
    }
    override suspend fun importPrivHex(privHex: String): GeneratedKeys = keys
    override suspend fun importNcryptsec(ncryptsec: String, password: String): GeneratedKeys {
        failure?.let { throw it }
        imported += "$ncryptsec|$password"
        return keys
    }
}

class FakeLocalEasyAuth(
    private val vault: FakeLocalKeyVault,
    private val profiles: FakeUserProfileRepository,
) : LocalEasyAuth {
    var registerFailure: Exception? = null
    var unlockFailure: Exception? = null
    val registered = mutableListOf<Pair<String, String>>()
    val unlockedWith = mutableListOf<String>()
    var savedPasskey: PasskeyPrfResult? = null
    var prfUnlocked: PasskeyPrfResult? = null

    override suspend fun register(nickname: String, password: String) {
        registerFailure?.let { throw it }
        registered += nickname to password
        vault.passwordWrap = "ncryptsec1wrap"
        profiles.current = profiles.current.copy(nickname = nickname, hasNostrKey = true)
    }
    override suspend fun passkeyRegistration(displayName: String) = PasskeyRegistration("aa", displayName)
    override suspend fun savePasskeyWrap(registration: PasskeyRegistration, result: PasskeyPrfResult) {
        savedPasskey = result
    }
    override suspend fun addPasskeyWrap(activity: Activity, displayName: String) = error("UI-scope variant unused")
    /** When set, `unlockWithPassword` suspends until completed. */
    var unlockGate: CompletableDeferred<Unit>? = null
    override suspend fun unlockWithPassword(password: String) {
        unlockGate?.await()
        unlockFailure?.let { throw it }
        unlockedWith += password
    }
    override suspend fun passkeyCredentialIds(): List<String> = listOf("cred")
    override suspend fun unlockWithPrfSecret(result: PasskeyPrfResult) {
        prfUnlocked = result
    }
    override suspend fun unlockWithPasskey(activity: Activity) = error("UI-scope variant unused")
}

class FakePasskeyPrfService(var supported: Boolean = false) : PasskeyPrfService {
    override suspend fun isSupported(): Boolean = supported
    override suspend fun register(activity: Activity, userIdHex: String, userName: String) = PasskeyPrfResult("cred", "ff")
    override suspend fun unlock(activity: Activity, credentialIds: List<String>) = PasskeyPrfResult("cred", "ff")
}

class FakeUserProfileRepository(var current: UserProfile = UserProfile()) : UserProfileRepository {
    var failure: Exception? = null
    var loads = 0
    override suspend fun load(): UserProfile {
        loads++
        failure?.let { throw it }
        return current
    }
    override suspend fun save(profile: UserProfile) {
        current = profile
    }
    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile {
        current = transform(current)
        return current
    }
    override val profile: Flow<UserProfile> get() = MutableStateFlow(current)
}

class FakeAppReviewSignIn(override var isOffered: Boolean = false) : AppReviewSignIn {
    var onSignIn: (String, String) -> Unit = { _, _ -> }
    override suspend fun signIn(user: String, pass: String) = onSignIn(user, pass)
}

class FakeMeetupService(var meetups: List<Meetup> = emptyList()) : MeetupService {
    var failure: Exception? = null
    override val cached: List<Meetup> get() = meetups
    override val cachedFlow: StateFlow<List<Meetup>> = MutableStateFlow(emptyList())
    override val fallbackMeetups: List<Meetup> = emptyList()
    override suspend fun fetchMeetups(): List<Meetup> {
        failure?.let { throw it }
        return meetups
    }
    override fun resolveFavorite(stored: String): Meetup? = meetups.firstOrNull { it.id == stored }
    override fun labelFor(stored: String): String = stored
    override fun cityFor(stored: String): String = stored
    override fun bySlug(slug: String, country: String): Meetup? = null
    override fun byName(name: String): Meetup? = null
    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = null
}

class FakeBackupLauncher : BackupLauncher {
    override val busy = MutableStateFlow(false)
    var restoreResult = false
    var exportCalls = 0
    var restoreCalls = 0
    override suspend fun export(): Boolean {
        exportCalls++
        return true
    }
    override suspend fun restore(): Boolean {
        restoreCalls++
        return restoreResult
    }
    override fun launchExport(onResult: (Boolean) -> Unit) = onResult(true)
    override fun launchRestore(onResult: (Boolean) -> Unit) = onResult(restoreResult)
}

class FakeMigrationGate(private val gate: CompletableDeferred<Unit>? = null) : MigrationGate {
    override val isComplete: Boolean get() = gate?.isCompleted ?: true
    override suspend fun await() {
        gate?.await()
    }
}

class FakeLocaleController : LocaleController {
    val locale = MutableStateFlow<String?>(null)
    override val appLocale: Flow<String?> = locale
    override val isManagedByPlatform: Boolean = false
    override suspend fun current(): String? = locale.value
    override suspend fun setLocale(code: String?): Boolean {
        locale.value = code
        return true
    }
    override suspend fun syncWithSystem() = Unit
    override fun currentBlocking(): String? = locale.value
    override fun wrapContext(base: Context): Context = base
}

class FakeKeyFileWriter : KeyFileWriter {
    var failure: Exception? = null
    /** When set, `write` suspends until completed. */
    var gate: CompletableDeferred<Unit>? = null
    val written = mutableListOf<Pair<String, String>>()
    override suspend fun write(uri: String, content: String) {
        gate?.await()
        failure?.let { throw it }
        written += uri to content
    }
}

class FixedTime(var millis: Long) : TimeSource {
    override fun nowMillis(): Long = millis
}

fun meetup(id: String, city: String, name: String = "") =
    Meetup(id = id, name = name, city = city, country = "DE", telegramLink = "", lat = 0.0, lng = 0.0)
