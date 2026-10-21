package space.einundzwanzig.meetup.data.issuance.scan

import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.badges.security.VerifyResult
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation

/**
 * Result of [BadgeScanProcessor] (the logic of Dart `_startQRScan` / `_startNfcRead` /
 * `_processFoundTagData`, spec 02 §2.1). The UI maps each case to its status text.
 */
sealed interface ScanOutcome {

    /** The app-review demo payload: show the English "Demo scan OK" dialog, issue nothing. */
    data object ReviewDemo : ScanOutcome

    /**
     * Rolling QR too old ([NonceValidation.status] = `EXPIRED`): `verifyErrQrExpired(validation.message)`.
     * Wrongly typed `n`/`ts` give [Malformed] instead.
     */
    data class NonceRejected(val validation: NonceValidation) : ScanOutcome

    /** Signature, format or expiry check failed: `verifyErrPrefix(result.message)`. */
    data class VerificationFailed(val result: VerifyResult) : ScanOutcome

    /** The NFC text record could not be read as a badge: see [NfcPayloadError]. */
    data class NfcRejected(val error: NfcPayloadError) : ScanOutcome

    /**
     * The payload has fields of the wrong JSON type: wrongly typed rolling fields `n`/`ts`
     * (detail `Ungültiges Rolling-QR-Format.`) or verified fields (Dart threw inside
     * `_processFoundTagData` and the scan silently stopped). Show `verifyErrPrefix(detail)`.
     */
    data class Malformed(val detail: String) : ScanOutcome

    /** The organizer scanned his own code: `"✗ ${verifyCantSelfBadge}${verifyAskScan}"`. Nothing stored. */
    data object SelfScan : ScanOutcome

    /**
     * One badge per meetup per local day / per event is already in the wallet:
     * `verifyAlreadyEvent(fullName)` if [isEvent], else `verifyAlreadyToday(fullName)`.
     */
    data class AlreadyCollected(val fullName: String, val isEvent: Boolean) : ScanOutcome

    /**
     * A new badge ready for "ZUR WALLET HINZUFÜGEN" ([BadgeSaveUseCase.confirm]). Show
     * [FoundInfo.message]; accent orange if [FoundInfo.unknownSigner], else green.
     */
    data class Found(val pending: PendingBadge, val info: FoundInfo) : ScanOutcome
}

/**
 * A scanned, not yet stored badge.
 *
 * @property orgLat signed organizer coordinates (`BadgeSecurity.signedCoordinates`): always
 *   0/0 today, because `la`/`lo` are unsigned (Security Audit H1).
 */
data class PendingBadge(
    val badge: MeetupBadge,
    val orgLat: Double = 0.0,
    val orgLng: Double = 0.0,
)

/** Binding (kind-21002 claim) result shown in the confirmation card. */
sealed interface ClaimInfo {
    /** No claim attempted (no v2 signature). */
    data object None : ClaimInfo

    /** `mvBadgeBound`. */
    data object Bound : ClaimInfo

    /** `"⚠ Binding: $message"` ([message] is the claim service's German reason). */
    data class Failed(val message: String) : ClaimInfo
}

/** Signer recognition shown in the confirmation card (spec 02 §2.1 step 10, §8.7, §8.8). */
sealed interface AdminCheckInfo {
    /** No check (no v2 signature / no pubkey). */
    data object None : AdminCheckInfo

    /** `mvKnownOrganizer(name ?: verifyVerifiedAdmin)` (registry name, else meetup). */
    data class KnownOrganizer(val name: String?) : AdminCheckInfo

    /** `mvPortalOrganizer(name)`: portal leader of this meetup or the meetup's portal `nostr` field. */
    data class PortalOrganizer(val meetupName: String) : AdminCheckInfo

    /** `mvAdminCheckFailed`: registry unreachable / no information — about the network, not the person. */
    data object CheckFailed : AdminCheckInfo

    /** `mvUnknownSigner` (orange state). */
    data object UnknownSigner : AdminCheckInfo

    /** `mvLegacyBadge` (unreachable: v1 is rejected earlier). */
    data object LegacyBadge : AdminCheckInfo

    /** `mvEventIssuerOk(eventTitle, creatorName ?: verifyVerifiedAdmin)`. */
    data class EventIssuerOk(val eventTitle: String, val creatorName: String?) : AdminCheckInfo

    /** `mvEventSignerNotListed(eventTitle)`. */
    data class EventSignerNotListed(val eventTitle: String) : AdminCheckInfo

    /** `mvEventCreatorNotAuthorized(eventTitle)`. */
    data class EventCreatorNotAuthorized(val eventTitle: String) : AdminCheckInfo

    /** `mvEventHasNoBadge(eventTitle)`. */
    data class EventHasNoBadge(val eventTitle: String) : AdminCheckInfo

    /** `mvEventNotFound`. */
    data object EventNotFound : AdminCheckInfo
}

/**
 * Everything the "BADGE GEFUNDEN" card shows.
 *
 * @property verifiedBy short signer npub (`_verified_by`), or `null`.
 * @property expiry `BadgeSecurity.expiryInfo(tagData)`; not shown for [ExpiryInfo.NoExpiry].
 * @property unknownSigner Dart `_isUnknownSigner`: v2 signature from a signer no source recognised.
 */
data class FoundInfo(
    val fullName: String,
    val blockHeight: Long,
    val verifiedBy: String?,
    val sigVersion: Long,
    val claim: ClaimInfo,
    val adminCheck: AdminCheckInfo,
    val expiry: ExpiryInfo,
    val unknownSigner: Boolean,
) {
    /** The exact Flutter concatenation of the confirmation text, with localized parts from [strings]. */
    fun message(strings: ScanStrings): String {
        val sb = StringBuilder()
        sb.append(strings.verifyBadgeFound()).append("\n\n")
        sb.append(strings.verifyMsgLocation(fullName)).append('\n')
        if (blockHeight > 0) sb.append(strings.verifyMsgBlock(blockHeight)).append('\n')
        if (verifiedBy != null) sb.append(strings.verifyMsgSignedBy(verifiedBy)).append('\n')
        if (sigVersion == 2L) sb.append(strings.verifyMsgProof()).append('\n')
        when (claim) {
            ClaimInfo.None -> Unit
            ClaimInfo.Bound -> sb.append(strings.mvBadgeBound())
            is ClaimInfo.Failed -> sb.append("⚠ Binding: ").append(claim.message)
        }
        val admin = strings.adminCheck(adminCheck)
        if (admin.isNotEmpty()) sb.append("\n\n").append(admin)
        if (expiry !is ExpiryInfo.NoExpiry) sb.append("\n\n").append(strings.verifyMsgTagExpiry(strings.expiry(expiry)))
        return sb.toString()
    }
}

/**
 * Localized parts of the scanner texts (ARB keys of spec 02 §2.10). The feature implements it
 * with `stringResource`/`Resources`; [FoundInfo.message] assembles them exactly like Flutter.
 */
interface ScanStrings {
    fun verifyBadgeFound(): String
    fun verifyMsgLocation(name: String): String
    fun verifyMsgBlock(block: Long): String
    fun verifyMsgSignedBy(npub: String): String
    fun verifyMsgProof(): String
    fun verifyMsgTagExpiry(expiry: String): String
    fun mvBadgeBound(): String
    fun verifyVerifiedAdmin(): String
    fun mvKnownOrganizer(name: String): String
    fun mvPortalOrganizer(meetup: String): String
    fun mvAdminCheckFailed(): String
    fun mvUnknownSigner(): String
    fun mvLegacyBadge(): String
    fun mvEventIssuerOk(event: String, creator: String): String
    fun mvEventSignerNotListed(event: String): String
    fun mvEventCreatorNotAuthorized(event: String): String
    fun mvEventHasNoBadge(event: String): String
    fun mvEventNotFound(): String

    /** Localized expiry ("Gültig noch 3h 12min"); default: the hard-coded German Flutter text. */
    fun expiry(info: ExpiryInfo): String = info.toGermanString()

    /** The admin-check line, `""` for [AdminCheckInfo.None]. */
    fun adminCheck(info: AdminCheckInfo): String = when (info) {
        AdminCheckInfo.None -> ""
        is AdminCheckInfo.KnownOrganizer -> mvKnownOrganizer(info.name ?: verifyVerifiedAdmin())
        is AdminCheckInfo.PortalOrganizer -> mvPortalOrganizer(info.meetupName)
        AdminCheckInfo.CheckFailed -> mvAdminCheckFailed()
        AdminCheckInfo.UnknownSigner -> mvUnknownSigner()
        AdminCheckInfo.LegacyBadge -> mvLegacyBadge()
        is AdminCheckInfo.EventIssuerOk -> mvEventIssuerOk(info.eventTitle, info.creatorName ?: verifyVerifiedAdmin())
        is AdminCheckInfo.EventSignerNotListed -> mvEventSignerNotListed(info.eventTitle)
        is AdminCheckInfo.EventCreatorNotAuthorized -> mvEventCreatorNotAuthorized(info.eventTitle)
        is AdminCheckInfo.EventHasNoBadge -> mvEventHasNoBadge(info.eventTitle)
        AdminCheckInfo.EventNotFound -> mvEventNotFound()
    }
}
