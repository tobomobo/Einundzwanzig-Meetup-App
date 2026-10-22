package space.einundzwanzig.meetup.data.issuance.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation
import space.einundzwanzig.meetup.data.issuance.session.RollingQr
import space.einundzwanzig.meetup.data.issuance.session.RollingQrKeys
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.issuance.session.SessionStartException
import javax.inject.Inject
import javax.inject.Singleton

/** Port of `lib/services/rolling_qr_service.dart` (spec 02 §4.3, §8.5). */
@Singleton
internal class DefaultRollingQrService @Inject constructor(
    private val prefs: KeyValueStore,
    private val secure: SecureStore,
    private val signer: BadgeSigner,
    private val identity: IdentityPort,
    private val blockHeights: BlockHeightPort,
    private val time: TimeSource,
    private val seeds: SeedSource,
) : RollingQrService {

    /** Tests replace it; production logs through the app logger. */
    internal var log: DiagnosticLog = AppLogger

    /** Guards every read/write of the stored session (short critical sections, no signing inside). */
    private val mutex = Mutex()

    /** Serializes [getOrCreateSession] (check-then-create) so two concurrent starts sign only once. */
    private val createLock = Mutex()

    /** Serializes the base-payload re-signing in [generateQrString] (one signer prompt at a time). */
    private val resignLock = Mutex()

    /** Last failed re-sign attempt (unix s), so a refusing signer is not prompted every 10 s. */
    @Volatile
    private var lastResignFailure: Long = Long.MIN_VALUE / 2

    private val session = MutableStateFlow<MeetupSession?>(null)

    override val activeSession: StateFlow<MeetupSession?> = session.asStateFlow()

    override suspend fun getOrCreateSession(
        meetupId: String,
        meetupName: String,
        meetupCountry: String,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        validUntilEpoch: Long?,
    ): MeetupSession = createLock.withLock {
        val existing = loadSession()
        if (existing != null && !existing.isExpired(time.nowSeconds()) && existing.meetupId == meetupId) {
            // Reuse: re-entering the QR must not invalidate codes already handed out — unless the
            // caller needs a longer session (event until midnight), which replaces this one.
            if (validUntilEpoch == null || validUntilEpoch <= existing.expiresAt) return@withLock existing
        }

        var height = blockHeight
        if (height <= 0) {
            height = blockHeights.blockHeight()
            if (height == 0L) {
                delay(BLOCK_HEIGHT_RETRY_MS)
                height = blockHeights.blockHeight()
            }
            // No hard-coded fallback: stays 0 offline.
        }
        createSession(meetupId, meetupName, meetupCountry, height, lat, lng, validUntilEpoch)
    }

    private suspend fun createSession(
        meetupId: String,
        meetupName: String,
        meetupCountry: String,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        validUntilEpoch: Long?,
    ): MeetupSession {
        val pubkey = try {
            identity.npub().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "npub nicht lesbar, Session ohne npub", e)
            ""
        }
        val now = time.nowSeconds()
        // Never in the past: an end that is already over would be a session that is expired at once.
        val expiresAt = if (validUntilEpoch != null && validUntilEpoch > now) {
            validUntilEpoch
        } else {
            now + RollingQr.SESSION_VALIDITY_HOURS * 3600L
        }
        val seed = seeds.newSeed()

        val canSign = try {
            signer.canSign()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionStartException(SessionStartException.Reason.SIGNING_FAILED, signingFailed(e), e)
        }
        if (!canSign) {
            throw SessionStartException(
                SessionStartException.Reason.NO_KEY,
                "Kein Nostr-Key vorhanden. Bitte zuerst einen Key erstellen oder importieren.",
            )
        }
        val basePayload = try {
            // Flutter bound: x = now + 4 h (capped at the session end). Longer (event) sessions are
            // re-signed in generateQrString before x runs out (spec 02 §8.5, §9 item 10).
            signBase(meetupId, blockHeight, now, expiresAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionStartException(SessionStartException.Reason.SIGNING_FAILED, signingFailed(e), e)
        }

        val created = MeetupSession(
            seed = seed,
            startedAt = now,
            expiresAt = expiresAt,
            meetupId = meetupId,
            meetupName = meetupName,
            meetupCountry = meetupCountry,
            blockHeight = blockHeight,
            pubkey = pubkey,
            lat = lat,
            lng = lng,
        )

        mutex.withLock {
            // One slot: clear the old metadata first, so a half-written replacement can never pair
            // old metadata with the new seed/payload.
            prefs.edit { RollingQrKeys.ALL_PREFS.forEach(::remove) }
            val secureOk = secure.writeAll(
                mapOf(
                    RollingQrKeys.SECURE_SEED to seed,
                    RollingQrKeys.SECURE_BASE_PAYLOAD to DartJson.encode(basePayload),
                ),
            )
            if (!secureOk) {
                session.value = null
                throw SessionStartException(
                    SessionStartException.Reason.STORAGE_FAILED,
                    "Session konnte nicht gespeichert werden (sicherer Speicher).",
                )
            }
            val prefsOk = prefs.edit {
                setInt(RollingQrKeys.START, now)
                setInt(RollingQrKeys.EXPIRES, expiresAt)
                setString(RollingQrKeys.MEETUP_ID, meetupId)
                setString(RollingQrKeys.MEETUP_NAME, meetupName)
                setString(RollingQrKeys.MEETUP_COUNTRY, meetupCountry)
                setInt(RollingQrKeys.BLOCK_HEIGHT, blockHeight)
                setString(RollingQrKeys.PUBKEY, pubkey)
                setDouble(RollingQrKeys.LAT, lat)
                setDouble(RollingQrKeys.LNG, lng)
            }
            if (!prefsOk) {
                secure.delete(RollingQrKeys.SECURE_SEED)
                secure.delete(RollingQrKeys.SECURE_BASE_PAYLOAD)
                session.value = null
                throw SessionStartException(
                    SessionStartException.Reason.STORAGE_FAILED,
                    "Session konnte nicht gespeichert werden.",
                )
            }
            session.value = created
        }
        log.debug(TAG, "Session gestartet: $meetupId bis $expiresAt (Block $blockHeight).")
        return created
    }

    override suspend fun loadSession(): MeetupSession? = mutex.withLock {
        val seed = loadSeed()
        val start = prefs.getInt(RollingQrKeys.START)
        val expires = prefs.getInt(RollingQrKeys.EXPIRES)
        val meetupId = prefs.getString(RollingQrKeys.MEETUP_ID)
        if (seed == null || start == null || expires == null || meetupId == null) {
            session.value = null
            return@withLock null
        }
        val loaded = MeetupSession(
            seed = seed,
            startedAt = start,
            expiresAt = expires,
            meetupId = meetupId,
            meetupName = prefs.getString(RollingQrKeys.MEETUP_NAME) ?: "",
            meetupCountry = prefs.getString(RollingQrKeys.MEETUP_COUNTRY) ?: "",
            blockHeight = prefs.getInt(RollingQrKeys.BLOCK_HEIGHT) ?: 0,
            pubkey = prefs.getString(RollingQrKeys.PUBKEY) ?: "",
            lat = prefs.getDouble(RollingQrKeys.LAT) ?: 0.0,
            lng = prefs.getDouble(RollingQrKeys.LNG) ?: 0.0,
        )
        // Check and wipe in the same critical section: a session created concurrently between
        // the two must never be wiped as "expired".
        if (loaded.isExpired(time.nowSeconds())) {
            wipeLocked()
            return@withLock null
        }
        session.value = loaded
        loaded
    }

    override suspend fun endSession() {
        mutex.withLock { wipeLocked() }
    }

    /** Removes seed, every session pref and the base payload. Caller holds [mutex]. */
    private suspend fun wipeLocked() {
        secure.delete(RollingQrKeys.SECURE_SEED)
        prefs.edit { RollingQrKeys.ALL_PREFS.forEach(::remove) }
        secure.delete(RollingQrKeys.SECURE_BASE_PAYLOAD)
        session.value = null
    }

    override suspend fun getBasePayload(): JsonObject? {
        val current = loadSession() ?: return null
        val stored = mutex.withLock { loadBasePayload() } ?: return null
        val raw = refreshIfNeeded(stored, current) ?: stored
        return runCatching { DartJson.decode(raw) as? JsonObject }
            .onFailure { log.warn(TAG, "Base-Payload nicht lesbar", it) }
            .getOrNull()
    }

    override suspend fun generateQrString(session: MeetupSession): String {
        val raw = mutex.withLock { loadBasePayload() }
            ?: throw IllegalStateException("Kein signierter Base-Payload vorhanden. Bitte neue Session starten.")
        val current = refreshIfNeeded(raw, session) ?: raw
        return RollingQr.buildQrString(current, session, time.nowSeconds())
    }

    /**
     * Re-signs the base payload when its signed `x` runs out within [RESIGN_MARGIN_SECONDS] while
     * the session itself continues (event sessions until midnight). Keeps Flutter's 4 h replay
     * bound for a photographed QR (docs/ROLLING_QR_SESSION_KEY.md) without killing long sessions.
     * Returns the new payload JSON, or `null` to keep [raw] (no refresh needed, or it failed).
     */
    private suspend fun refreshIfNeeded(raw: String, session: MeetupSession): String? {
        if (!needsResign(raw, session, time.nowSeconds())) return null
        return resignLock.withLock {
            // Another caller may have refreshed while we waited.
            val latest = mutex.withLock { loadBasePayload() } ?: return@withLock null
            val now = time.nowSeconds()
            if (!needsResign(latest, session, now)) return@withLock latest
            if (now - lastResignFailure < RESIGN_RETRY_SECONDS) return@withLock null
            val base = parse(latest) ?: return@withLock null
            val meetupId = (base["m"] as? JsonPrimitive)?.contentOrNull ?: session.meetupId
            val blockHeight = (base["b"] as? JsonPrimitive)?.longOrNull ?: session.blockHeight
            val signed = try {
                signBase(meetupId, blockHeight, now, session.expiresAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastResignFailure = now
                log.warn(TAG, "Base-Payload konnte nicht neu signiert werden", e)
                return@withLock null
            }
            val json = DartJson.encode(signed)
            mutex.withLock {
                // Only replace the payload of this very session (it may have ended or been replaced).
                val stillSame = loadBasePayload() == latest &&
                    secure.read(RollingQrKeys.SECURE_SEED) == session.seed
                if (!stillSame || !secure.write(RollingQrKeys.SECURE_BASE_PAYLOAD, json)) return@withLock null
                log.debug(TAG, "Base-Payload neu signiert bis ${signed["x"]}.")
                json
            }
        }
    }

    /** `x` ends within the margin, before the session does, and the session is still running. */
    private fun needsResign(raw: String, session: MeetupSession, now: Long): Boolean {
        if (session.isExpired(now)) return false
        val x = (parse(raw)?.get("x") as? JsonPrimitive)?.longOrNull ?: return false
        return x < session.expiresAt && x - now < RESIGN_MARGIN_SECONDS
    }

    private fun parse(raw: String): JsonObject? = runCatching { DartJson.decode(raw) as? JsonObject }.getOrNull()

    /** Signs the compact base payload with `x = min(sessionEnd, now + 4 h)`. */
    private suspend fun signBase(meetupId: String, blockHeight: Long, now: Long, sessionEnd: Long): JsonObject {
        val hours = RollingQr.SESSION_VALIDITY_HOURS
        return if (sessionEnd < now + hours * 3600L) {
            // signCompact computes x = nowSeconds + hours * 3600: sessionEnd with 0 hours.
            BadgeSecurity.signCompact(signer, meetupId, blockHeight, nowSeconds = sessionEnd, validityHours = 0)
        } else {
            BadgeSecurity.signCompact(signer, meetupId, blockHeight, nowSeconds = now, validityHours = hours)
        }
    }

    override fun validateNonce(payload: Map<String, JsonElement>): NonceValidation =
        RollingQr.validateNonce(payload, time.nowSeconds())

    override fun secondsUntilNextChange(): Int = RollingQr.secondsUntilNextChange(time.nowSeconds())

    /** Seed from the secure store after migrating the legacy prefs copy (Audit C3). Caller holds the lock. */
    private suspend fun loadSeed(): String? {
        val legacy = prefs.getString(RollingQrKeys.LEGACY_SEED)
        if (!legacy.isNullOrEmpty() && secure.write(RollingQrKeys.SECURE_SEED, legacy)) {
            prefs.remove(RollingQrKeys.LEGACY_SEED)
        }
        return secure.read(RollingQrKeys.SECURE_SEED)
    }

    /** Base payload from the secure store after migrating the legacy prefs copy (Audit 2 #3). Caller holds the lock. */
    private suspend fun loadBasePayload(): String? {
        val legacy = prefs.getString(RollingQrKeys.LEGACY_BASE_PAYLOAD)
        if (!legacy.isNullOrEmpty() && secure.write(RollingQrKeys.SECURE_BASE_PAYLOAD, legacy)) {
            prefs.remove(RollingQrKeys.LEGACY_BASE_PAYLOAD)
        }
        return secure.read(RollingQrKeys.SECURE_BASE_PAYLOAD)
    }

    private fun signingFailed(e: Exception): String =
        "Badge-Signierung fehlgeschlagen: ${e.message ?: e.toString()}. " +
            "Bitte stelle sicher, dass ein Nostr-Key vorhanden ist."

    private companion object {
        const val TAG = "RollingQR"
        const val BLOCK_HEIGHT_RETRY_MS = 2_000L

        /** Re-sign when the signed `x` is less than this away (one signer prompt per ~4 h). */
        const val RESIGN_MARGIN_SECONDS = 10 * 60L

        /** Back-off after a refused or failed re-sign. */
        const val RESIGN_RETRY_SECONDS = 60L
    }
}
