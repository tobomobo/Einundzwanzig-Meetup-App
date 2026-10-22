package space.einundzwanzig.meetup.data.badges.security

/**
 * Result of [BadgeSecurity.verify] / [BadgeSecurity.verifyCompact] (Dart `VerifyResult`).
 *
 * @property isValid signature valid (and not expired when checked).
 * @property version 2 (compact / legacy full format), 1 (rejected v1), 0 (exception path).
 * @property adminNpub organizer npub (`""` if unknown).
 * @property adminPubkey organizer hex pubkey as given.
 * @property message German text exactly as in Flutter (logs; UI fallback when [reason] is null).
 * @property reason typed failure for localized UI texts (spec 02 §2.9); `null` when valid.
 */
data class VerifyResult(
    val isValid: Boolean,
    val version: Int,
    val adminNpub: String,
    val adminPubkey: String = "",
    val message: String,
    val reason: VerifyFailure? = null,
)

/** Typed reason of a failed [VerifyResult] (the UI localizes it; [VerifyResult.message] is German). */
sealed interface VerifyFailure {
    /** `Fehlende Signatur-Daten`. */
    data object MissingData : VerifyFailure

    /** `Ungültiges Pubkey-Format (erwartet: 64 Hex-Zeichen)`. */
    data object BadPubkey : VerifyFailure

    /** `Ungültiges Signatur-Format (erwartet: 128 Hex-Zeichen)`. */
    data object BadSigFormat : VerifyFailure

    /** `Tag abgelaufen! <expiry>. Bitte Organisator um neuen Tag.` */
    data class Expired(val expiry: ExpiryInfo) : VerifyFailure

    /** Schnorr signature / event id does not match (`Signatur ungültig!`, `… Invalid event`). */
    data object InvalidSignature : VerifyFailure

    /** v1 payloads are rejected (Audit C1). */
    data object LegacyRejected : VerifyFailure

    /** Wrongly typed fields; [detail] is technical (not localized). */
    data class Error(val detail: String) : VerifyFailure
}

/**
 * Result of [BadgeSecurity.signQrV3] (Dart `QRSignResult`). `isNostr == false` and empty fields
 * when no key can sign (the producer then falls back to the rejected `21:` format).
 */
data class QrSignResult(
    val signature: String,
    val eventId: String,
    val createdAt: Long,
    val pubkeyHex: String,
    val isNostr: Boolean,
)

/** Result of [BadgeSecurity.verifyQrV3] (Dart `QRVerifyResult`). */
data class QrVerifyResult(
    val isValid: Boolean,
    val version: Int,
    val signerNpub: String,
    val message: String = "",
)

/**
 * The five parts of a `21v3:` reputation QR (`21v3:BASE64.SIG.EVENTID.CREATEDAT.PUBKEY`).
 *
 * @property json the decoded JSON text (the signed kind-21001 content).
 */
data class ReputationQrV3Parts(
    val json: String,
    val signature: String,
    val eventId: String,
    val createdAt: Long,
    val pubkeyHex: String,
)

/** Typed Dart `expiryInfo` (spec 02 §8.12); [toGermanString] gives the Flutter text. */
sealed interface ExpiryInfo {
    /** No `x` / `expires_at` (or 0). */
    data object NoExpiry : ExpiryInfo

    /** Expired [seconds] ago (`diff <= 0`, so 0 counts as expired). */
    data class Expired(val seconds: Long) : ExpiryInfo

    /** Still valid for [seconds]. */
    data class Valid(val seconds: Long) : ExpiryInfo

    /** `"Kein Ablauf"`, `"Abgelaufen vor 1h 3min"`, `"Gültig noch 12min"` … (integer division like `Duration`). */
    fun toGermanString(): String = when (this) {
        NoExpiry -> "Kein Ablauf"
        is Expired -> {
            val h = seconds / 3600
            val m = seconds / 60
            if (h > 0) "Abgelaufen vor ${h}h ${m % 60}min" else "Abgelaufen vor ${m}min"
        }
        is Valid -> {
            val h = seconds / 3600
            val m = seconds / 60
            if (h > 0) "Gültig noch ${h}h ${m % 60}min" else "Gültig noch ${m}min"
        }
    }
}
