package space.einundzwanzig.meetup.data.issuance.session

/**
 * The organizer's (or event helper's) running badge session (Dart `MeetupSession`, spec 02 §3.4).
 * There is exactly one session slot per device.
 *
 * @property seed 64 lowercase hex chars (32 CSPRNG bytes). The HMAC key of the rolling nonce, used
 *   as **UTF-8 text** (spec 02 §7.5). Secret: never log it ([toString] redacts it).
 * @property startedAt unix seconds.
 * @property expiresAt unix seconds.
 * @property meetupId the signed `m` value (`"bad-neuenahr-de"`, `"evt:<kind>:<pubkey>:<d>:<slug>"`).
 * @property meetupName display name (city, free text or event title).
 * @property meetupCountry ISO code or `""`.
 * @property blockHeight block height at session creation (0 when offline).
 * @property pubkey the organizer's **npub** (bech32) despite the name, `""` if unknown.
 * @property lat organizer GPS (meetup) or venue (event); 0 = none. Sent unsigned in the QR.
 */
data class MeetupSession(
    val seed: String,
    val startedAt: Long,
    val expiresAt: Long,
    val meetupId: String,
    val meetupName: String,
    val meetupCountry: String,
    val blockHeight: Long,
    val pubkey: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
) {
    /** `true` for an event-badge session (`m` starts with `evt:`; Home shows a medal icon). */
    val isEventSession: Boolean get() = meetupId.startsWith("evt:")

    /** Dart `isExpired`: `nowSeconds > expiresAt`. */
    fun isExpired(nowSeconds: Long): Boolean = nowSeconds > expiresAt

    /** Dart `remainingTime` in seconds: `max(0, expiresAt - now)`. */
    fun remainingSeconds(nowSeconds: Long): Long = (expiresAt - nowSeconds).coerceAtLeast(0)

    /** Dart `elapsedTime` in seconds (may be negative if the clock went back). */
    fun elapsedSeconds(nowSeconds: Long): Long = nowSeconds - startedAt

    /** Typed `remainingTimeString` (spec 02 §2.3); the UI localizes it. */
    fun remaining(nowSeconds: Long): SessionRemaining = SessionRemaining.of(remainingSeconds(nowSeconds))

    override fun toString(): String =
        "MeetupSession(meetupId=$meetupId, meetupName=$meetupName, meetupCountry=$meetupCountry, " +
            "startedAt=$startedAt, expiresAt=$expiresAt, blockHeight=$blockHeight, pubkey=$pubkey, " +
            "lat=$lat, lng=$lng, seed=<redacted>)"
}

/**
 * Remaining session time, as Dart's `remainingTimeString` shows it: `"Abgelaufen"` when ≤ 0,
 * `"${h}h ${m % 60}min"` when hours > 0, else `"${m}min"` (integer division like `Duration`).
 */
sealed interface SessionRemaining {
    /** No time left. */
    data object Expired : SessionRemaining

    /** At least one hour left. */
    data class HoursMinutes(val hours: Long, val minutes: Long) : SessionRemaining

    /** Less than one hour left (may be `0min` for < 60 s). */
    data class Minutes(val minutes: Long) : SessionRemaining

    /** The hard-coded German Flutter text (`Abgelaufen`, `3h 12min`, `45min`). */
    fun toGermanString(): String = when (this) {
        Expired -> "Abgelaufen"
        is HoursMinutes -> "${hours}h ${minutes}min"
        is Minutes -> "${minutes}min"
    }

    companion object {
        /** Classifies [seconds] remaining. */
        fun of(seconds: Long): SessionRemaining {
            if (seconds <= 0) return Expired
            val hours = seconds / 3600
            val minutes = seconds / 60
            return if (hours > 0) HoursMinutes(hours, minutes % 60) else Minutes(minutes)
        }
    }
}
