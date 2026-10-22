package space.einundzwanzig.meetup.data.identity.profile

import space.einundzwanzig.meetup.core.common.dart.dartTrim

/**
 * The local user profile (Dart `UserProfile`, spec 01 §3.2). Immutable; change it with `copy`
 * (or [withAdmin] / [withPromotionSource] for the legacy setters) and persist with
 * [UserProfileRepository.save].
 *
 * Admin status is derived from three independent sources ([adminViaPortal], [adminViaVouch],
 * [adminViaSeed]) so no verifier can revoke another source's grant.
 *
 * @property nickname pref `nickname`, default `"Anon"`.
 * @property fullName pref `full_name`.
 * @property telegramHandle pref `telegram`.
 * @property nostrNpub pref `nostr`; on load the external-signer npub overrides the keystore npub,
 *   which overrides the pref (spec 00 item 11).
 * @property twitterHandle pref `twitter`.
 * @property isNostrVerified pref `nostr_verified`; on load also `true` with a local key or an external signer.
 * @property isAdminVerified pref `admin_verified`.
 * @property homeMeetupId pref `home_meetup`: portal meetup id of the first favorite (legacy: a city name).
 * @property favoriteMeetupIds pref `favorite_meetups` (string list; legacy fallback `[home_meetup]`).
 * @property hasNostrKey **not persisted**: a local nsec exists (false in pure external modes).
 * @property isReviewDemo pref `review_demo`; cosmetic, never an admin source.
 * @property adminCryptoVerified in memory only; set by the admin re-verification (`:data:admin`).
 */
data class UserProfile(
    val nickname: String = DEFAULT_NICKNAME,
    val fullName: String = "",
    val telegramHandle: String = "",
    val nostrNpub: String = "",
    val twitterHandle: String = "",
    val isNostrVerified: Boolean = false,
    val isAdminVerified: Boolean = false,
    val homeMeetupId: String = "",
    val favoriteMeetupIds: List<String> = emptyList(),
    val hasNostrKey: Boolean = false,
    val adminViaPortal: Boolean = false,
    val adminViaVouch: Boolean = false,
    val adminViaSeed: Boolean = false,
    val isReviewDemo: Boolean = false,
    val adminCryptoVerified: Boolean = false,
) {
    /** Derived admin status: any source grants it (persisted as `is_admin`). */
    val isAdmin: Boolean get() = adminViaPortal || adminViaVouch || adminViaSeed

    /** Display source, priority `seed_admin` > `portal_organizer` > `trust_score` > `""`. */
    val promotionSource: String
        get() = when {
            adminViaSeed -> SOURCE_SEED
            adminViaPortal -> SOURCE_PORTAL
            adminViaVouch -> SOURCE_VOUCH
            else -> ""
        }

    /** Nickname set and not `"Anon"` (trimmed, case-sensitive). */
    val hasCustomNickname: Boolean get() = nickname.dartTrim().let { it.isNotEmpty() && it != DEFAULT_NICKNAME }

    /** `isNostrVerified || isAdminVerified`. */
    val isVerified: Boolean get() = isNostrVerified || isAdminVerified

    /** Routing: `hasCustomNickname || isVerified`. */
    val isOnboarded: Boolean get() = hasCustomNickname || isVerified

    /** Dart `isAdmin = v` (legacy): sets or clears the vouch source only. */
    fun withAdmin(isAdmin: Boolean): UserProfile = copy(adminViaVouch = isAdmin)

    /** Dart `promotionSource = s` (legacy): maps one value onto its flag; other values change nothing. */
    fun withPromotionSource(source: String): UserProfile = when (source) {
        SOURCE_SEED -> copy(adminViaSeed = true)
        SOURCE_PORTAL -> copy(adminViaPortal = true)
        SOURCE_VOUCH -> copy(adminViaVouch = true)
        else -> this
    }

    /**
     * The flag update of Dart `reVerifyAdmin` for a verifier result ([isAdmin], [source]): the
     * seed source is set for `seed_admin`, otherwise the seed flag is cleared and the vouch source
     * follows [isAdmin]; the portal flag is never touched. `isAdminVerified` follows the derived
     * [UserProfile.isAdmin], `adminCryptoVerified` becomes `true`. The caller (`:data:admin`) saves.
     */
    fun withAdminVerification(isAdmin: Boolean, source: String): UserProfile {
        val updated = if (source == SOURCE_SEED) {
            copy(adminViaSeed = isAdmin)
        } else {
            copy(adminViaSeed = false, adminViaVouch = isAdmin)
        }
        return updated.copy(isAdminVerified = updated.isAdmin, adminCryptoVerified = true)
    }

    companion object {
        /** Default nickname. */
        const val DEFAULT_NICKNAME = "Anon"
        const val SOURCE_SEED = "seed_admin"
        const val SOURCE_PORTAL = "portal_organizer"
        const val SOURCE_VOUCH = "trust_score"

        /**
         * Dart constructor compatibility: a non-empty [promotionSource] sets its flag, otherwise
         * [isAdmin] sets the vouch flag.
         */
        fun legacy(base: UserProfile, isAdmin: Boolean, promotionSource: String): UserProfile = when {
            promotionSource.isNotEmpty() -> base.withPromotionSource(promotionSource)
            isAdmin -> base.copy(adminViaVouch = true)
            else -> base
        }
    }
}
