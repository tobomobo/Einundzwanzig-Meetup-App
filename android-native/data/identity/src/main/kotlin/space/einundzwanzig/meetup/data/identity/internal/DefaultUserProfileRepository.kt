package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import space.einundzwanzig.meetup.core.common.log.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultUserProfileRepository @Inject constructor(
    private val prefs: KeyValueStore,
    private val keyStore: SecureKeyStore,
    private val signing: SigningService,
    private val changes: IdentityChanges,
) : UserProfileRepository {

    private val updateMutex = Mutex()

    override suspend fun load(): UserProfile {
        val hasKey = keyStore.hasKey()
        var npub = prefs.getString(K_NOSTR) ?: ""
        if (hasKey) {
            val keyNpub = keyStore.getNpub()
            if (!keyNpub.isNullOrEmpty()) npub = keyNpub
        }
        val externalSigner = signing.isExternalSigner()
        if (externalSigner) {
            val externalNpub = signing.npub()
            if (!externalNpub.isNullOrEmpty()) npub = externalNpub
        }
        val home = prefs.getString(K_HOME_MEETUP) ?: ""
        val base = UserProfile(
            nickname = prefs.getString(K_NICKNAME) ?: UserProfile.DEFAULT_NICKNAME,
            fullName = prefs.getString(K_FULL_NAME) ?: "",
            telegramHandle = prefs.getString(K_TELEGRAM) ?: "",
            nostrNpub = npub,
            twitterHandle = prefs.getString(K_TWITTER) ?: "",
            isNostrVerified = hasKey || externalSigner || (prefs.getBool(K_NOSTR_VERIFIED) ?: false),
            isAdminVerified = prefs.getBool(K_ADMIN_VERIFIED) ?: false,
            homeMeetupId = home,
            favoriteMeetupIds = prefs.getStringList(K_FAVORITES) ?: if (home.isNotEmpty()) listOf(home) else emptyList(),
            hasNostrKey = hasKey,
        )
        var profile = UserProfile.legacy(
            base,
            isAdmin = prefs.getBool(K_IS_ADMIN) ?: false,
            promotionSource = prefs.getString(K_PROMOTION_SOURCE) ?: "",
        )
        if (prefs.contains(K_VIA_PORTAL)) {
            profile = profile.copy(
                adminViaPortal = prefs.getBool(K_VIA_PORTAL) ?: false,
                adminViaVouch = prefs.getBool(K_VIA_VOUCH) ?: false,
                adminViaSeed = prefs.getBool(K_VIA_SEED) ?: false,
            )
        }
        return profile.copy(isReviewDemo = prefs.getBool(K_REVIEW_DEMO) ?: false)
    }

    override suspend fun save(profile: UserProfile) {
        val ok = prefs.edit {
            setString(K_NICKNAME, profile.nickname)
            setString(K_FULL_NAME, profile.fullName)
            setString(K_TELEGRAM, profile.telegramHandle)
            setString(K_NOSTR, profile.nostrNpub)
            setString(K_TWITTER, profile.twitterHandle)
            setBool(K_NOSTR_VERIFIED, profile.isNostrVerified)
            setBool(K_ADMIN_VERIFIED, profile.isAdminVerified)
            setBool(K_IS_ADMIN, profile.isAdmin)
            setBool(K_VIA_PORTAL, profile.adminViaPortal)
            setBool(K_VIA_VOUCH, profile.adminViaVouch)
            setBool(K_VIA_SEED, profile.adminViaSeed)
            setBool(K_REVIEW_DEMO, profile.isReviewDemo)
            setString(K_HOME_MEETUP, profile.homeMeetupId)
            setStringList(K_FAVORITES, profile.favoriteMeetupIds)
            setString(K_PROMOTION_SOURCE, profile.promotionSource)
        }
        if (!ok) throw IdentityStorageException("Profil konnte nicht gespeichert werden.")
        changes.notifyChanged()
    }

    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile = updateMutex.withLock {
        transform(load()).also { save(it) }
    }

    override val profile: Flow<UserProfile> =
        merge(
            // Only the keys load() reads: other domains' pref writes cause no Keystore reads.
            prefs.observeAll().map { all -> OBSERVED_KEYS.map { all[it] } }.distinctUntilChanged().map { },
            changes.changes,
        )
            .conflate()
            .mapNotNull { loadForFlow() }
            .distinctUntilChanged()

    /** [load] for the flow: an unreadable secure store is logged and skipped, never ends the flow. */
    private suspend fun loadForFlow(): UserProfile? = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.warn("UserProfile", "Profil konnte nicht gelesen werden; letzter Stand bleibt.", e)
        null
    }

    internal companion object {
        const val K_NICKNAME = "nickname"
        const val K_FULL_NAME = "full_name"
        const val K_TELEGRAM = "telegram"
        const val K_NOSTR = "nostr"
        const val K_TWITTER = "twitter"
        const val K_NOSTR_VERIFIED = "nostr_verified"
        const val K_ADMIN_VERIFIED = "admin_verified"
        const val K_IS_ADMIN = "is_admin"
        const val K_VIA_PORTAL = "admin_via_portal"
        const val K_VIA_VOUCH = "admin_via_vouch"
        const val K_VIA_SEED = "admin_via_seed"
        const val K_REVIEW_DEMO = "review_demo"
        const val K_HOME_MEETUP = "home_meetup"
        const val K_FAVORITES = "favorite_meetups"
        const val K_PROMOTION_SOURCE = "promotion_source"

        /** Every pref [load] depends on (profile keys + signer mode/npubs + the plaintext-key migration). */
        val OBSERVED_KEYS: List<String> = listOf(
            K_NICKNAME, K_FULL_NAME, K_TELEGRAM, K_NOSTR, K_TWITTER, K_NOSTR_VERIFIED, K_ADMIN_VERIFIED,
            K_IS_ADMIN, K_VIA_PORTAL, K_VIA_VOUCH, K_VIA_SEED, K_REVIEW_DEMO, K_HOME_MEETUP, K_FAVORITES,
            K_PROMOTION_SOURCE,
            IdentityKeys.SIGNING_MODE, IdentityKeys.AMBER_NPUB, IdentityKeys.NIP07_NPUB, IdentityKeys.NIP46_NPUB,
            IdentityKeys.SECURE_MIGRATION_DONE, IdentityKeys.NSEC, IdentityKeys.NPUB, IdentityKeys.PRIV_HEX,
        )
    }
}
