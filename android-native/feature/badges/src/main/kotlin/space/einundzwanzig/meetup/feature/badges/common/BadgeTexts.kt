package space.einundzwanzig.meetup.feature.badges.common

import android.content.res.Resources
import space.einundzwanzig.meetup.data.badges.claim.ClaimFailure
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.badges.security.VerifyFailure
import space.einundzwanzig.meetup.data.issuance.scan.ClaimInfo
import space.einundzwanzig.meetup.data.issuance.scan.ScanStrings
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining
import space.einundzwanzig.meetup.feature.badges.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * [ScanStrings] backed by string resources: the confirmation text of the scanner (spec 02 §2.1
 * step 10) in the current locale. Expiry texts are localized too (Flutter kept them German).
 */
internal class ResourceScanStrings(private val res: Resources) : ScanStrings {
    override fun verifyBadgeFound(): String = res.getString(L10nR.string.verifyBadgeFound)
    override fun verifyMsgLocation(name: String): String = res.getString(L10nR.string.verifyMsgLocation, name)
    override fun verifyMsgBlock(block: Long): String = res.getString(L10nR.string.verifyMsgBlock, block.toString())
    override fun verifyMsgSignedBy(npub: String): String = res.getString(L10nR.string.verifyMsgSignedBy, npub)
    override fun verifyMsgProof(): String = res.getString(L10nR.string.verifyMsgProof)
    override fun verifyMsgTagExpiry(expiry: String): String = res.getString(L10nR.string.verifyMsgTagExpiry, expiry)
    override fun mvBadgeBound(): String = res.getString(L10nR.string.mvBadgeBound)
    override fun verifyVerifiedAdmin(): String = res.getString(L10nR.string.verifyVerifiedAdmin)
    override fun mvKnownOrganizer(name: String): String = res.getString(L10nR.string.mvKnownOrganizer, name)
    override fun mvPortalOrganizer(meetup: String): String = res.getString(L10nR.string.mvPortalOrganizer, meetup)
    override fun mvAdminCheckFailed(): String = res.getString(L10nR.string.mvAdminCheckFailed)
    override fun mvUnknownSigner(): String = res.getString(L10nR.string.mvUnknownSigner)
    override fun mvLegacyBadge(): String = res.getString(L10nR.string.mvLegacyBadge)
    override fun mvEventIssuerOk(event: String, creator: String): String =
        res.getString(L10nR.string.mvEventIssuerOk, event, creator)
    override fun mvEventSignerNotListed(event: String): String = res.getString(L10nR.string.mvEventSignerNotListed, event)
    override fun mvEventCreatorNotAuthorized(event: String): String =
        res.getString(L10nR.string.mvEventCreatorNotAuthorized, event)
    override fun mvEventHasNoBadge(event: String): String = res.getString(L10nR.string.mvEventHasNoBadge, event)
    override fun mvEventNotFound(): String = res.getString(L10nR.string.mvEventNotFound)
    override fun expiry(info: ExpiryInfo): String = BadgeTexts.expiry(res, info)
    override fun claimFailed(info: ClaimInfo.Failed): String =
        res.getString(R.string.native_claim_failed, BadgeTexts.claimFailure(res, info.reason, info.message))
}

/** Localized forms of the typed domain texts (spec 02 §2.9). */
internal object BadgeTexts {

    /** `BadgeSecurity.expiryInfo` in the current locale (`verifyNoExpiry`, "Gültig noch 3h 12min" …). */
    fun expiry(res: Resources, info: ExpiryInfo): String = when (info) {
        ExpiryInfo.NoExpiry -> res.getString(L10nR.string.verifyNoExpiry)
        is ExpiryInfo.Expired -> hm(res, info.seconds, R.string.native_expiry_expired_hm, R.string.native_expiry_expired_m)
        is ExpiryInfo.Valid -> hm(res, info.seconds, R.string.native_expiry_valid_hm, R.string.native_expiry_valid_m)
    }

    /** Localized [VerifyFailure] (the text inside `verifyErrPrefix`). */
    fun verifyFailure(res: Resources, failure: VerifyFailure): String = when (failure) {
        VerifyFailure.MissingData -> res.getString(R.string.native_verify_missing_data)
        VerifyFailure.BadPubkey -> res.getString(R.string.native_verify_bad_pubkey)
        VerifyFailure.BadSigFormat -> res.getString(R.string.native_verify_bad_sig_format)
        is VerifyFailure.Expired -> res.getString(R.string.native_verify_expired, expiry(res, failure.expiry))
        VerifyFailure.InvalidSignature -> res.getString(R.string.native_verify_invalid_signature)
        VerifyFailure.LegacyRejected -> res.getString(R.string.native_verify_legacy_rejected)
        is VerifyFailure.Error -> res.getString(R.string.native_verify_error, failure.detail)
    }

    /** Localized [ClaimFailure]; the German [fallback] when the reason is unknown. */
    fun claimFailure(res: Resources, reason: ClaimFailure?, fallback: String): String = when (reason) {
        null -> fallback
        ClaimFailure.NoKey -> res.getString(R.string.native_claim_no_key)
        ClaimFailure.TimestampMismatch -> res.getString(R.string.native_claim_timestamp)
        is ClaimFailure.Error -> res.getString(R.string.native_claim_error, reason.detail)
    }

    /** `MeetupSession.remainingTimeString` in the current locale. */
    fun remaining(res: Resources, remaining: SessionRemaining): String = when (remaining) {
        SessionRemaining.Expired -> res.getString(R.string.native_remaining_expired)
        is SessionRemaining.HoursMinutes ->
            res.getString(R.string.native_remaining_hm, remaining.hours.toInt(), remaining.minutes.toInt())
        is SessionRemaining.Minutes -> res.getString(R.string.native_remaining_m, remaining.minutes.toInt())
    }

    private fun hm(res: Resources, seconds: Long, hoursRes: Int, minutesRes: Int): String {
        val h = seconds / 3600
        val m = seconds / 60
        return if (h > 0) res.getString(hoursRes, h.toInt(), (m % 60).toInt()) else res.getString(minutesRes, m.toInt())
    }
}
