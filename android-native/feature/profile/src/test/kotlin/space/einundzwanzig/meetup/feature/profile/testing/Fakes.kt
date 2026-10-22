package space.einundzwanzig.meetup.feature.profile.testing

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayAddResult
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.Nip07ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46Pairing
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.auth.DeviceAuthenticator
import space.einundzwanzig.meetup.data.identity.keys.GeneratedKeys
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.keys.StoredKeys
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.identity.signer.NostrSigner
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityCheckResult
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter

const val NPUB_A = "npub1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const val NPUB_B = "npub1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
const val NSEC_A = "nsec1secretsecretsecretsecretsecretsecretsecretsecretsecretse"

fun meetup(id: String, city: String, name: String = "") =
    Meetup(id = id, name = name, city = city, country = "DE", telegramLink = "", lat = 0.0, lng = 0.0)

class FakeSigningService(
    var mode: SigningMode = SigningMode.LOCAL,
    var npubValue: String? = null,
    var canSignValue: Boolean = true,
) : SigningService {
    var amberResult: AmberConnectResult = AmberConnectResult.Success("aa", NPUB_B)
    var bunkerResult: Nip46ConnectResult = Nip46ConnectResult.Success("bb", NPUB_B)
    var bunkerGate: CompletableDeferred<Unit>? = null
    var sessionAlive = true
    var useLocalCalls = 0
    val disconnects = mutableListOf<String>()
    val connectedUris = mutableListOf<String>()
    var interceptors = 0
    var closedInterceptors = 0
    var authUrlHandler: ((String) -> Unit)? = null

    override val signerReady: SharedFlow<Unit> = MutableSharedFlow()
    override val authUrlEvents: SharedFlow<String> = MutableSharedFlow()
    override fun acknowledgeAuthUrl(url: String) = Unit
    override fun interceptAuthUrls(handler: (String) -> Unit): AutoCloseable {
        interceptors++
        authUrlHandler = handler
        return AutoCloseable {
            closedInterceptors++
            authUrlHandler = null
        }
    }
    override suspend fun getMode(): SigningMode = mode
    override suspend fun isExternalSigner(): Boolean = mode.isExternal
    override suspend fun recommendedExistingPath(): RecommendedExistingPath = RecommendedExistingPath.entries.first()
    override fun isAmberInstalled(): Boolean = true
    override suspend fun nip07Available(): Boolean = false
    override suspend fun activeSigner(): NostrSigner = throw UnsupportedOperationException()
    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent = throw UnsupportedOperationException()
    override suspend fun pubkeyHex(): String? = null
    override suspend fun npub(): String? = npubValue
    override suspend fun canSign(): Boolean = canSignValue
    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String = throw UnsupportedOperationException()
    override suspend fun nip44Decrypt(peerHex: String, payload: String): String = throw UnsupportedOperationException()
    override suspend fun canNip44(): Boolean = false
    var amberGate: CompletableDeferred<AmberConnectResult>? = null
    override suspend fun connectAmber(): AmberConnectResult = (amberGate?.await() ?: amberResult).also {
        if (it is AmberConnectResult.Success) {
            mode = SigningMode.AMBER
            npubValue = it.npub
        }
    }
    override suspend fun restoreAmber(npub: String) = Unit
    override suspend fun disconnectAmber() {
        disconnects += "amber"
        mode = SigningMode.LOCAL
    }
    override suspend fun connectNip07(): Nip07ConnectResult = Nip07ConnectResult.Missing
    override suspend fun restoreNip07(npub: String) = Unit
    override suspend fun disconnectNip07() {
        disconnects += "nip07"
        mode = SigningMode.LOCAL
    }
    override suspend fun useLocalMode() {
        useLocalCalls++
        mode = SigningMode.LOCAL
    }
    override suspend fun connectNip46Bunker(bunkerUri: String): Nip46ConnectResult {
        connectedUris += bunkerUri
        bunkerGate?.await()
        return bunkerResult.also {
            if (it is Nip46ConnectResult.Success) {
                mode = SigningMode.NIP46
                npubValue = it.npub
            }
        }
    }
    override suspend fun startNip46Pairing(): Nip46Pairing = throw IllegalStateException("Keine Relays")
    override suspend fun completeNip46Pairing(pairing: Nip46Pairing): Nip46ConnectResult = Nip46ConnectResult.Timeout
    override suspend fun nip46SessionAlive(): Boolean = sessionAlive
    override suspend fun nip46SessionForBackup(): Map<String, String>? = null
    override suspend fun restoreNip46(npub: String, bunkerUri: String, clientSecretKeyHex: String?) = Unit
    override suspend fun disconnectNip46() {
        disconnects += "nip46"
        mode = SigningMode.LOCAL
        npubValue = null
    }
    override suspend fun nip46PairingRelays(): List<String> = emptyList()
}

class FakeKeyStore(var keys: StoredKeys? = null, var privHex: String? = null) : SecureKeyStore {
    override suspend fun ensureMigrated() = Unit
    override suspend fun saveKeys(nsec: String, npub: String, privHex: String) {
        keys = StoredKeys(nsec, npub)
        this.privHex = privHex
    }
    override suspend fun getNsec(): String? = keys?.nsec
    override suspend fun getNpub(): String? = keys?.npub
    override suspend fun getPrivHex(): String? = privHex
    override suspend fun loadKeys(): StoredKeys? = keys
    override suspend fun hasKey(): Boolean = keys != null
    override suspend fun deleteKeys() {
        keys = null
        privHex = null
    }
    override suspend fun saveNip46ClientKey(privHex: String) = Unit
    override suspend fun getNip46ClientKey(): String? = null
    override suspend fun deleteNip46ClientKey() = Unit
}

class FakeVault(var wrap: String? = null, var wrapNpub: String? = null) : LocalKeyVault {
    var clearCalls = 0
    override suspend fun savePasswordWrap(ncryptsec: String, npub: String) {
        wrap = ncryptsec
        wrapNpub = npub
    }
    override suspend fun getPasswordWrap(): String? = wrap
    override suspend fun getPasswordWrapNpub(): String? = wrapNpub
    override suspend fun passwordWrapMatchesNpub(npub: String): Boolean = wrapNpub == npub
    override suspend fun savePasskeyWrap(ncryptsec: String, credentialId: String) = Unit
    override suspend fun getPasskeyWrap(): String? = null
    override suspend fun getPasskeyCredentialId(): String? = null
    override suspend fun hasPasskeyWrap(): Boolean = false
    override suspend fun clearPasskeyWrap() = Unit
    override suspend fun clearAll() {
        clearCalls++
        wrap = null
        wrapNpub = null
    }
}

class FakeKeyService(private val store: FakeKeyStore) : NostrKeyService {
    var generated = GeneratedKeys(NSEC_A, NPUB_A, "a".repeat(64))
    var importError: Exception? = null
    var importGate: CompletableDeferred<Unit>? = null
    var importCalls = 0
    override suspend fun generateKeyPair(): GeneratedKeys = generated.also { store.saveKeys(it.nsec, it.npub, "1".repeat(64)) }
    override suspend fun importNsec(nsec: String): GeneratedKeys {
        importCalls++
        importGate?.await()
        importError?.let { throw it }
        store.saveKeys(nsec, NPUB_A, "1".repeat(64))
        return GeneratedKeys(nsec, NPUB_A, "a".repeat(64))
    }
    override suspend fun importPrivHex(privHex: String): GeneratedKeys = throw UnsupportedOperationException()
    override suspend fun importNcryptsec(ncryptsec: String, password: String): GeneratedKeys = throw UnsupportedOperationException()
}

class FakeProfiles(var current: UserProfile = UserProfile()) : UserProfileRepository {
    val saved = mutableListOf<UserProfile>()
    var loadError: Exception? = null
    var saveGate: CompletableDeferred<Unit>? = null
    override suspend fun load(): UserProfile {
        loadError?.let { throw it }
        return current
    }
    override suspend fun save(profile: UserProfile) {
        saveGate?.await()
        saved += profile
        current = profile
    }
    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile = transform(current).also { save(it) }
    override val profile: Flow<UserProfile> get() = flowOf(current)
}

class FakeMeetupService(private val list: List<Meetup>) : MeetupService {
    override val cached: List<Meetup> get() = list
    override val cachedFlow: StateFlow<List<Meetup>> = MutableStateFlow(list)
    override val fallbackMeetups: List<Meetup> = emptyList()
    override suspend fun fetchMeetups(): List<Meetup> = list
    override fun resolveFavorite(stored: String): Meetup? = list.firstOrNull { it.id == stored }
    override fun labelFor(stored: String): String = resolveFavorite(stored)?.city ?: stored
    override fun cityFor(stored: String): String = labelFor(stored)
    override fun bySlug(slug: String, country: String): Meetup? = null
    override fun byName(name: String): Meetup? = null
    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = null
}

class FakeProfileService : NostrProfileService {
    var local: String? = null
    override suspend fun fetchProfilePicture(pubkeyHex: String): String? = null
    override suspend fun fetchDisplayName(pubkeyHex: String): String? = null
    override suspend fun cachedDisplayNames(): Map<String, String> = emptyMap()
    override suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String> = emptyMap()
    override suspend fun setLocalPicture(path: String) {
        local = path
    }
    override suspend fun getLocalPicture(): String? = local
    override suspend fun getExistingLocalPicture(): String? = local
    override fun observeLocalPicture(): Flow<String?> = flowOf(local)
    override suspend fun clearCache() = Unit
}

class FakePlatformProofs(initial: List<PlatformProof> = emptyList()) : PlatformProofRepository {
    val stored = MutableStateFlow(initial)
    override val proofs: Flow<List<PlatformProof>> = stored
    override suspend fun getSavedProofs(): List<PlatformProof> = stored.value
    override suspend fun createProof(platformId: String, username: String, customPlatformName: String?): ProofCreateResult = throw UnsupportedOperationException()
    override suspend fun removeProof(platformId: String): Boolean = false
    override suspend fun getProofsForPublishing(): LinkedHashMap<String, PlatformProof> = LinkedHashMap()
    override suspend fun replaceAllRaw(validated: List<JsonElement>): Boolean = true
    override suspend fun unionRaw(incoming: List<JsonElement>): Boolean = true
    override suspend fun clear(): Boolean = true
}

class FakeHumanity(initial: HumanityStatus = HumanityStatus(verified = false)) : HumanityProofRepository {
    val current = MutableStateFlow<HumanityStatus?>(initial)
    override val status: Flow<HumanityStatus> = current.filterNotNull()
    override suspend fun getStatus(): HumanityStatus = current.value ?: HumanityStatus(verified = false)
    override suspend fun checkForZaps(): HumanityCheckResult = HumanityCheckResult(false, HumanityOutcome.NOT_FOUND)
    override suspend fun reverifyIfNeeded(): Boolean = false
    override suspend fun reset() = Unit
    override suspend fun getProofForPublishing(): JsonObject? = null
    override suspend fun checkForZapsByPubkey(pubkeyHex: String): Boolean = false
    override suspend fun verifyReceiptExists(receiptEventId: String): Boolean = false
    override suspend fun restoreFromBackup(backupProof: JsonObject?, additive: Boolean): Boolean = false
    override suspend fun clearForIdentitySwitch(): Boolean = true
}

class FakeAuthenticator(var result: Boolean = true) : DeviceAuthenticator {
    override suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String?): Boolean = result
}

class FakePictureImporter(var error: Exception? = null) : LocalPictureImporter {
    val imported = mutableListOf<String>()
    override suspend fun import(uri: String): String {
        error?.let { throw it }
        imported += uri
        return "/files/profile_picture/local.jpg"
    }
}

class FakeRelayConfig : RelayConfig {
    val disabled = mutableListOf<String>()
    val custom = mutableListOf<String>()
    var addResult: RelayAddResult = RelayAddResult.ADDED
    var addGate: CompletableDeferred<Unit>? = null
    val added = mutableListOf<String>()

    override suspend fun getActiveRelays(): List<String> = RelayConfig.activeRelaysOf(disabled, custom)
    override fun observeActiveRelays(): Flow<List<String>> = flowOf(RelayConfig.activeRelaysOf(disabled, custom))
    override suspend fun getCustomRelays(): List<String> = custom.toList()
    override suspend fun getDisabledDefaults(): List<String> = disabled.toList()
    override suspend fun getRelayStatus(): Map<String, Boolean> = RelayConfig.relayStatusOf(disabled, custom)
    override fun observeRelayStatus(): Flow<Map<String, Boolean>> = flowOf(RelayConfig.relayStatusOf(disabled, custom))
    override suspend fun addCustomRelay(url: String): RelayAddResult {
        added += url
        addGate?.await()
        if (addResult == RelayAddResult.ADDED) custom += url
        return addResult
    }
    override suspend fun removeCustomRelay(url: String) {
        custom.remove(url)
    }
    override suspend fun setDefaultRelayEnabled(url: String, enabled: Boolean) {
        if (enabled) disabled.remove(url) else if (url !in disabled) disabled += url
    }
    override suspend fun probeRelay(url: String, timeout: kotlin.time.Duration): Boolean = true
}
