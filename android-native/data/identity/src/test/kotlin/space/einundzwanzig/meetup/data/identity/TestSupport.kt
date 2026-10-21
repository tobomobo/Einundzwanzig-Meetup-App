package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.identity.internal.DefaultIdentityRecovery
import space.einundzwanzig.meetup.data.identity.internal.DefaultIdentityRepository
import space.einundzwanzig.meetup.data.identity.internal.DefaultLocalEasyAuth
import space.einundzwanzig.meetup.data.identity.internal.DefaultLocalKeyVault
import space.einundzwanzig.meetup.data.identity.internal.DefaultNostrKeyService
import space.einundzwanzig.meetup.data.identity.internal.DefaultSecureKeyStore
import space.einundzwanzig.meetup.data.identity.internal.DefaultSigningService
import space.einundzwanzig.meetup.data.identity.internal.DefaultUserProfileRepository
import space.einundzwanzig.meetup.data.identity.internal.IdentityChanges
import space.einundzwanzig.meetup.data.identity.internal.Nip46ClientFactory
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RelayOpener
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Exception
import space.einundzwanzig.meetup.data.identity.nip55.Nip55SignResult
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Transport
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfService
import java.io.File
import java.net.URLDecoder

internal object Fixtures {
    val identity: JsonObject by lazy {
        val file = File("../../spec/fixtures/01-identity-keys-vectors.json")
        check(file.exists()) { "missing spec fixture ${file.absolutePath}" }
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }
}

internal class FakeTime(var millis: Long = 1_786_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = millis
}

/** Dart `Uri.parse(uri).queryParametersAll` for assertions. */
internal fun queryAll(uri: String): Map<String, List<String>> {
    val query = uri.substringAfter('?', "").substringBefore('#')
    val out = LinkedHashMap<String, MutableList<String>>()
    for (part in query.split('&').filter { it.isNotEmpty() }) {
        val k = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
        val v = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
        out.getOrPut(k) { mutableListOf() } += v
    }
    return out
}

internal fun queryParam(uri: String, name: String): String? = queryAll(uri)[name]?.firstOrNull()

/** Amber transport double. */
internal class FakeNip55(
    var installed: Boolean = false,
    var publicKey: () -> String = { throw Nip55Exception("cancelled") },
    var sign: (String, String) -> Nip55SignResult = { _, _ -> throw Nip55Exception("cancelled") },
    var nip44: (String, String, String, String) -> String = { _, _, _, _ -> throw Nip55Exception("cancelled") },
) : Nip55Transport {
    override fun isInstalled(): Boolean = installed
    override suspend fun getPublicKey(): String = publicKey()
    override suspend fun signEvent(eventJson: String, npub: String): Nip55SignResult = sign(eventJson, npub)
    override suspend fun nip44(type: String, peerHex: String, value: String, npub: String): String = nip44(type, peerHex, value, npub)
}

internal class FakePasskeys : PasskeyPrfService {
    override suspend fun isSupported(): Boolean = false
    override suspend fun register(activity: android.app.Activity, userIdHex: String, userName: String): PasskeyPrfResult =
        throw UnsupportedOperationException()
    override suspend fun unlock(activity: android.app.Activity, credentialIds: List<String>): PasskeyPrfResult =
        throw UnsupportedOperationException()
}

/** The identity object graph on in-memory stores (Dart tests: mock prefs + mock secure storage). */
internal class TestIdentity(
    prefsInit: Map<String, PrefValue> = emptyMap(),
    secureInit: Map<String, String> = emptyMap(),
    val nip55: FakeNip55 = FakeNip55(),
    opener: Nip46RelayOpener = Nip46RelayOpener { url -> throw java.io.IOException("offline $url") },
    timeouts: space.einundzwanzig.meetup.data.identity.nip46.Nip46Timeouts =
        space.einundzwanzig.meetup.data.identity.nip46.Nip46Timeouts(),
) {
    /** Every client the service created, in order. */
    val createdClients = java.util.concurrent.CopyOnWriteArrayList<Nip46Client>()

    /** The `auth_url` callbacks the service handed to its clients. */
    val authCallbacks = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    val prefs = InMemoryKeyValueStore(prefsInit)
    val secure = InMemorySecureStore(secureInit)
    val time = FakeTime()
    val changes = IdentityChanges()
    val keyStore = DefaultSecureKeyStore(secure, prefs, changes)
    val vault = DefaultLocalKeyVault(secure, keyStore)
    val keys = DefaultNostrKeyService(keyStore, Dispatchers.Default)
    val relayConfig = RelayConfig(prefs, FakeRelayConnector.unreachable())
    val clientFactory = Nip46ClientFactory { sk, relays, remote, onAuthUrl ->
        authCallbacks += onAuthUrl
        Nip46Client(sk, relays, remote, onAuthUrl, timeouts = timeouts, openRelay = opener, timeSource = time)
            .also { createdClients += it }
    }
    val signing = DefaultSigningService(prefs, keyStore, relayConfig, nip55, clientFactory, time, Dispatchers.Default)
    val profiles = DefaultUserProfileRepository(prefs, keyStore, signing, changes)
    val recovery = DefaultIdentityRecovery(secure, keyStore, keys, Dispatchers.Default)
    val identityRepo = DefaultIdentityRepository(signing, keyStore, vault, recovery, profiles)
    val easyAuth = DefaultLocalEasyAuth(keyStore, vault, keys, signing, profiles, FakePasskeys(), Dispatchers.Default)
}
