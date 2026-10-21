package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.profile.UserProfile

/** Port of `user_profile_test.dart` plus the load/save rules of spec 01 §3.2. */
class UserProfileTest {

    private val author = "fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268"

    @Test
    fun onboardingRequiresACustomNicknameOrAVerifiedIdentity() {
        assertFalse(UserProfile().isOnboarded)
        assertFalse(UserProfile(nickname = "").isOnboarded)
        assertFalse(UserProfile(nickname = "Anon").isOnboarded)
        assertTrue(UserProfile(nickname = "Satoshi").isOnboarded)
        assertTrue(UserProfile(isNostrVerified = true).isOnboarded)
        assertTrue(UserProfile(isAdminVerified = true).isOnboarded)
    }

    @Test
    fun publicNpubImportIsLoadedWithoutAPrivateKey() = runBlocking {
        val id = TestIdentity()
        id.prefs.setString("nostr", Nip19.encodeNpub(author))
        val profile = id.profiles.load()
        assertFalse(profile.hasNostrKey)
        assertEquals(author, Nip19.decodeNpub(profile.nostrNpub))
    }

    @Test
    fun restoredRemoteSignerKeepsThePublicIdentityWithoutASession() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(Nip19.encodeNpub(author), "bunker://$author?relay=wss://relay.example")
        val profile = id.profiles.load()
        assertFalse(profile.hasNostrKey)
        assertFalse(id.signing.canSign())
        assertEquals(author, Nip19.decodeNpub(profile.nostrNpub))
        assertTrue(profile.isNostrVerified)
    }

    @Test
    fun npubPrecedenceExternalOverKeystoreOverPrefs() = runBlocking {
        val id = TestIdentity()
        val local = KeyPair.generate()
        id.prefs.setString("nostr", "npub-from-prefs")
        id.keyStore.saveKeys(local.nsec, local.npub, local.privateKeyHex)
        assertEquals(local.npub, id.profiles.load().nostrNpub)
        id.signing.restoreAmber(Nip19.encodeNpub(author))
        val profile = id.profiles.load()
        assertEquals(Nip19.encodeNpub(author), profile.nostrNpub)
        assertTrue(profile.hasNostrKey)
    }

    @Test
    fun saveWritesEveryKeyAndLoadReadsThemBack() = runBlocking {
        val id = TestIdentity()
        val saved = UserProfile(
            nickname = "Satoshi",
            fullName = "S N",
            telegramHandle = "@sn",
            nostrNpub = Nip19.encodeNpub(author),
            twitterHandle = "@x",
            isNostrVerified = true,
            isAdminVerified = true,
            homeMeetupId = "42",
            favoriteMeetupIds = listOf("42", "7"),
            adminViaPortal = true,
            isReviewDemo = true,
        )
        id.profiles.save(saved)
        val all = id.prefs.getAll()
        assertEquals(PrefValue.BoolValue(true), all["is_admin"])
        assertEquals(PrefValue.StringValue("portal_organizer"), all["promotion_source"])
        assertEquals(PrefValue.StringListValue(listOf("42", "7")), all["favorite_meetups"])
        assertEquals(PrefValue.BoolValue(false), all["admin_via_vouch"])
        assertEquals(PrefValue.BoolValue(true), all["review_demo"])
        val loaded = id.profiles.load()
        assertEquals(saved.copy(hasNostrKey = false), loaded)
    }

    @Test
    fun legacyAdminPrefsAndFavoriteFallback() = runBlocking {
        val id = TestIdentity(
            mapOf(
                "is_admin" to PrefValue.BoolValue(true),
                "home_meetup" to PrefValue.StringValue("Berlin"),
            ),
        )
        val p = id.profiles.load()
        assertTrue(p.adminViaVouch)
        assertEquals("trust_score", p.promotionSource)
        assertEquals(listOf("Berlin"), p.favoriteMeetupIds)

        val seeded = TestIdentity(mapOf("promotion_source" to PrefValue.StringValue("seed_admin"), "is_admin" to PrefValue.BoolValue(true)))
        val s = seeded.profiles.load()
        assertTrue(s.adminViaSeed)
        assertFalse(s.adminViaVouch)

        // New flags win over the legacy single value once present.
        val flags = TestIdentity(
            mapOf(
                "promotion_source" to PrefValue.StringValue("seed_admin"),
                "admin_via_portal" to PrefValue.BoolValue(false),
            ),
        )
        assertFalse(flags.profiles.load().isAdmin)
    }

    @Test
    fun legacySettersAndAdminVerification() {
        val p = UserProfile()
        assertTrue(p.withAdmin(true).adminViaVouch)
        assertFalse(p.withAdmin(true).withAdmin(false).isAdmin)
        assertEquals("seed_admin", p.withPromotionSource("seed_admin").withPromotionSource("portal_organizer").promotionSource)
        assertEquals(p, p.withPromotionSource("unbekannt"))

        val portal = UserProfile(adminViaPortal = true, adminViaSeed = true)
        val vouched = portal.withAdminVerification(isAdmin = false, source = "trust_score")
        assertFalse(vouched.adminViaSeed)
        assertTrue(vouched.adminViaPortal)
        assertTrue(vouched.isAdminVerified)
        assertTrue(vouched.adminCryptoVerified)
        val seed = UserProfile().withAdminVerification(isAdmin = true, source = "seed_admin")
        assertTrue(seed.adminViaSeed)
        assertTrue(seed.isAdminVerified)
    }

    @Test
    fun hasCustomNicknameIsTrimmedAndCaseSensitive() {
        assertFalse(UserProfile(nickname = "  Anon ").hasCustomNickname)
        assertTrue(UserProfile(nickname = "anon").hasCustomNickname)
        assertFalse(UserProfile(nickname = "   ").hasCustomNickname)
    }

    @Test
    fun adminCryptoVerificationIsSharedInMemoryForTheVerifiedIdentity() = runBlocking {
        val id = TestIdentity()
        val keys = id.keys.generateKeyPair()
        assertFalse(id.profiles.load().adminCryptoVerified)

        val verified = id.profiles.update { it.withAdminVerification(isAdmin = true, source = "trust_score") }
        assertTrue(verified.adminCryptoVerified)
        // Every later load and the reactive flow see it, not only the returned instance.
        assertTrue(id.profiles.load().adminCryptoVerified)
        assertTrue(id.profiles.profile.first().adminCryptoVerified)
        // It is never persisted.
        assertFalse(id.prefs.entries.value.keys.any { it.contains("crypto", ignoreCase = true) })
        // A stale profile saved later does not revoke it.
        id.profiles.save(verified.copy(adminCryptoVerified = false, nickname = "Satoshi"))
        assertTrue(id.profiles.load().adminCryptoVerified)

        // Another identity does not inherit it.
        id.keys.generateKeyPair()
        assertFalse(id.profiles.load().adminCryptoVerified)
        id.keys.importNsec(keys.nsec)
        assertTrue(id.profiles.load().adminCryptoVerified)

        // The full reset forgets it.
        id.identityRepo.wipeAll()
        id.keys.importNsec(keys.nsec)
        assertFalse(id.profiles.load().adminCryptoVerified)
    }
}
