package space.einundzwanzig.meetup.data.issuance.session

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The organizer side of the rolling QR (Dart `RollingQRService`, spec 02 §4.3, §7.5, §8.5): one
 * persisted session per device with a once-signed base payload (kind 21000) and a CSPRNG seed in
 * the secure store, and the QR string of the current 10-second step.
 *
 * Inject the `@Singleton`. All methods are safe to call from the main thread.
 */
interface RollingQrService {

    /**
     * The session as last loaded, created or ended in this process (`null` = none). It does not
     * flip to `null` by itself when the session expires: check [MeetupSession.isExpired]. Call
     * [loadSession] once (e.g. Home start) to fill it from storage.
     */
    val activeSession: StateFlow<MeetupSession?>

    /**
     * Dart `getOrCreateSession`: reuses a running, unexpired session for the same [meetupId]
     * unless [validUntilEpoch] asks for a longer one; otherwise fetches the block height if
     * [blockHeight] ≤ 0 (one retry after 2 s, stays 0 offline), signs the compact base payload
     * once and persists everything, replacing any other session.
     *
     * The signed expiry `x` is Flutter's `now + 4 h` (capped at [MeetupSession.expiresAt]): the
     * replay bound of a photographed QR. Longer (event) sessions are re-signed by
     * [generateQrString]/[getBasePayload] shortly before `x` runs out (spec 02 §8.5, §9 item 10).
     *
     * @param validUntilEpoch own end (unix seconds) for event sessions; ignored if in the past.
     * @throws SessionStartException when nothing can sign, signing fails or storage fails.
     */
    suspend fun getOrCreateSession(
        meetupId: String,
        meetupName: String,
        meetupCountry: String,
        blockHeight: Long,
        lat: Double = 0.0,
        lng: Double = 0.0,
        validUntilEpoch: Long? = null,
    ): MeetupSession

    /**
     * Dart `loadSession`: migrates the legacy prefs seed into the secure store, requires seed,
     * start, expiry and meetup id, and ends an expired session (returning `null`).
     */
    suspend fun loadSession(): MeetupSession?

    /** Dart `endSession`: wipes the seed, every `rqr_session_*` pref and the base payload. */
    suspend fun endSession()

    /**
     * Dart `getBasePayload`: the signed base payload (`v,t,m,b,x,c,p,s`) of the running session,
     * e.g. for the NFC writer; `null` without a session or payload. Re-signs first if needed
     * (see [generateQrString]).
     */
    suspend fun getBasePayload(): JsonObject?

    /**
     * Dart `generateQRString(session)` for the current time step (spec 02 §3.5).
     *
     * Native fix (wire-compatible): when the signed `x` of the base payload ends in less than
     * 10 minutes while the session itself continues (event sessions until midnight), the base
     * payload is re-signed with `x = min(expiresAt, now + 4 h)` and stored atomically. With an
     * external signer (Amber, bunker) this is one signer prompt per ~4 h; if signing fails the old
     * payload is used and the attempt repeats after 60 s.
     *
     * @throws IllegalStateException `Kein signierter Base-Payload vorhanden. Bitte neue Session starten.`
     */
    suspend fun generateQrString(session: MeetupSession): String

    /** Dart `validateNonce` on the injected clock ([RollingQr.validateNonce]). */
    fun validateNonce(payload: Map<String, JsonElement>): NonceValidation

    /** Dart `secondsUntilNextChange()` on the injected clock (1…10). */
    fun secondsUntilNextChange(): Int
}

/** Why [RollingQrService.getOrCreateSession] failed. */
class SessionStartException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Typed reason for localization. */
    enum class Reason {
        /** No identity can sign (`Kein Nostr-Key vorhanden …`). */
        NO_KEY,

        /** The signer failed or was cancelled (`Badge-Signierung fehlgeschlagen: …`). */
        SIGNING_FAILED,

        /** The session could not be persisted (native only; Dart threw a platform error). */
        STORAGE_FAILED,
    }
}

/** Exact Flutter storage keys of the session (spec 02 §4.1). */
object RollingQrKeys {
    /** Secure store: the session seed (64 hex). */
    const val SECURE_SEED = "rqr_session_seed_secure"

    /** Secure store: the base payload JSON (contains the signature). */
    const val SECURE_BASE_PAYLOAD = "rqr_session_base_payload_secure"

    /** Prefs (legacy): plaintext seed, migrated to [SECURE_SEED] and removed. */
    const val LEGACY_SEED = "rqr_session_seed"

    /** Prefs (legacy): plaintext base payload, migrated to [SECURE_BASE_PAYLOAD] and removed. */
    const val LEGACY_BASE_PAYLOAD = "rqr_session_base_payload"

    /** Prefs int: unix seconds. */
    const val START = "rqr_session_start"

    /** Prefs int: unix seconds (also read by the admin panel countdown). */
    const val EXPIRES = "rqr_session_expires"

    /** Prefs string: signed `m`. */
    const val MEETUP_ID = "rqr_session_meetup_id"

    /** Prefs string. */
    const val MEETUP_NAME = "rqr_session_meetup_name"

    /** Prefs string. */
    const val MEETUP_COUNTRY = "rqr_session_meetup_country"

    /** Prefs int. */
    const val BLOCK_HEIGHT = "rqr_session_block_height"

    /** Prefs string: npub. */
    const val PUBKEY = "rqr_session_pubkey"

    /** Prefs double. */
    const val LAT = "rqr_session_lat"

    /** Prefs double. */
    const val LNG = "rqr_session_lng"

    /** Every prefs key [RollingQrService.endSession] removes. */
    val ALL_PREFS: List<String> = listOf(
        LEGACY_SEED, START, EXPIRES, MEETUP_ID, MEETUP_NAME, MEETUP_COUNTRY, BLOCK_HEIGHT, PUBKEY, LAT, LNG,
        LEGACY_BASE_PAYLOAD,
    )
}
