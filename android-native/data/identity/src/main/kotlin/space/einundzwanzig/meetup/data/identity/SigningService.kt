package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.flow.SharedFlow
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.signer.NostrSigner

/**
 * The single signing facade of the app (Dart `SigningService`, spec 01 §6.5/§8.1). Every
 * signature goes through [signEvent], which dispatches to the active signer (local key, Amber,
 * remote signer). Mode and session state live in the shared prefs (`signing_mode`, `amber_*`,
 * `nip07_*`, `nip46_*`) and the secure store (`nip46_client_sk`).
 *
 * Invariants: leaving NIP-46 (any mode switch away from it) clears the NIP-46 prefs, the session
 * key and closes the live client. Connecting a signer **never** deletes the local key.
 */
interface SigningService {

    /**
     * Fires after every path that makes an identity able to sign (connect/restore Amber, NIP-07,
     * NIP-46 connect/pair/restore, [useLocalMode]); replaces Dart `onSignerReady` (badge sync).
     */
    val signerReady: SharedFlow<Unit>

    /**
     * `auth_url`s of a remote signer that demands browser approval (Dart `onNip46AuthUrl`). The
     * app root shows the orange 60 s snackbar for them. While an [interceptAuthUrls] handler is
     * installed the URLs go there instead. Without any collector the host is logged (never the URL).
     * Emitted from the relay transport thread; collectors run in their own context.
     */
    val authUrlEvents: SharedFlow<String>

    /**
     * Routes `auth_url`s to [handler] until the returned handle is closed (the bunker sheet shows
     * them inline). Nested handlers stack; closing restores the previous one, never `null`.
     *
     * [handler] is invoked on the relay transport thread (OkHttp reader), not on Main: it must not
     * block; update thread-safe state (e.g. a `MutableStateFlow`) or post to Main for UI work.
     */
    fun interceptAuthUrls(handler: (String) -> Unit): AutoCloseable

    /** The stored mode; unknown/absent → [SigningMode.LOCAL]. */
    suspend fun getMode(): SigningMode

    /** `true` for Amber, NIP-07 and NIP-46. No silent signing at app start in these modes. */
    suspend fun isExternalSigner(): Boolean

    /** Android: [RecommendedExistingPath.AMBER] if Amber is installed, else [RecommendedExistingPath.BUNKER]. */
    suspend fun recommendedExistingPath(): RecommendedExistingPath

    /** `true` if the Amber app is installed. */
    fun isAmberInstalled(): Boolean

    /** Always `false` on Android (no browser extension). */
    suspend fun nip07Available(): Boolean

    /** The active signer (for code that needs the [NostrSigner] seam). @throws SigningException */
    suspend fun activeSigner(): NostrSigner

    /**
     * Signs with the active signer.
     *
     * @throws SigningException (subclasses for cancel / missing Amber / wrong account) with a
     *   user-facing reason.
     */
    suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent

    /** Hex pubkey of the active identity (`""` for a stored npub with a wrong HRP — Dart quirk). */
    suspend fun pubkeyHex(): String?

    /** npub of the active identity (mode-specific source). */
    suspend fun npub(): String?

    /** Hot-path check without network: can the active mode sign right now? */
    suspend fun canSign(): Boolean

    /** NIP-44 encryption through the active signer (badge sync). */
    suspend fun nip44Encrypt(peerHex: String, plaintext: String): String

    /** NIP-44 decryption through the active signer. */
    suspend fun nip44Decrypt(peerHex: String, payload: String): String

    /** Encrypt-to-self probe with `e21-nip44-probe`; logs step and reason, never payloads. */
    suspend fun canNip44(): Boolean

    /** Connects Amber (foreground `get_public_key`) and stores pubkey + mode on success. */
    suspend fun connectAmber(): AmberConnectResult

    /** Restores the Amber mode from a backup (npub only). */
    suspend fun restoreAmber(npub: String)

    /** Removes the Amber pubkey and switches to local. */
    suspend fun disconnectAmber()

    /** Always [Nip07ConnectResult.Missing] on Android. */
    suspend fun connectNip07(): Nip07ConnectResult

    /** Restores the (web-only) NIP-07 mode from a backup so the identity stays visible. */
    suspend fun restoreNip07(npub: String)

    /** Removes the NIP-07 pubkey and switches to local. */
    suspend fun disconnectNip07()

    /** Activates the local key (after generation/import/unlock); clears a NIP-46 session. */
    suspend fun useLocalMode()

    /** Connects via a pasted `bunker://` URI (`connect` + `get_public_key`, ≤ 75 s each). */
    suspend fun connectNip46Bunker(bunkerUri: String): Nip46ConnectResult

    /** Starts client-initiated pairing; no network until [completeNip46Pairing]. */
    suspend fun startNip46Pairing(): Nip46Pairing

    /** Waits for the signer (≤ 120 s), then `get_public_key`, persists and adopts the session. */
    suspend fun completeNip46Pairing(pairing: Nip46Pairing): Nip46ConnectResult

    /** Ping of the NIP-46 session (proves the connection, not signing rights). `false` unless NIP-46. */
    suspend fun nip46SessionAlive(): Boolean

    /** `{"bunker_uri": …}` for the backup or `null`; never the session key. */
    suspend fun nip46SessionForBackup(): Map<String, String>?

    /**
     * Restores identity and bunker address from a backup. The bunker URI is parsed first (throws
     * [space.einundzwanzig.meetup.data.identity.nip46.Nip46Exception] before anything is stored).
     * Without [clientSecretKeyHex] an old session key is deleted and [canSign] is `false`.
     */
    suspend fun restoreNip46(npub: String, bunkerUri: String, clientSecretKeyHex: String? = null)

    /** Clears the NIP-46 session and switches to local. */
    suspend fun disconnectNip46()

    /** The first 3 active relays (user settings), or the first 2 defaults if none is active. */
    suspend fun nip46PairingRelays(): List<String>
}
