package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.admin.internal.DefaultAdminStatusVerifier
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.model.AdminVerification
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.score.BootstrapPhase
import space.einundzwanzig.meetup.data.trust.score.CoAttestorData
import space.einundzwanzig.meetup.data.trust.score.TrustConfig
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator

/**
 * `AdminStatusVerifier.verifyAdminStatus` + `UserProfile.reVerifyAdmin` (spec 05 §8.3, §3.4,
 * Audit C2): registry cache/relay hits never count for the self check; only the hard-coded super
 * admin (bootstrap) or the trust score.
 */
class AdminStatusVerifierTest {

    private class InMemoryProfiles(initial: UserProfile = UserProfile()) : UserProfileRepository {
        private val state = MutableStateFlow(initial)
        override suspend fun load(): UserProfile = state.value
        override suspend fun save(profile: UserProfile) { state.value = profile }
        override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile =
            transform(state.value).also { state.value = it }
        override val profile: Flow<UserProfile> = state
    }

    /** A signer that only reports an npub (the super admin's key is not available in tests). */
    private class NpubOnlySigner(private val npub: String?) : AdminSigner {
        override suspend fun canSign(): Boolean = npub != null
        override suspend fun npub(): String? = npub
        override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
            error("not used")
    }

    private val trust = TrustScoreCalculator.create(FakeClock())
    private val noRelay = AdminRelayFetch { _, _ -> null }

    private fun verifier(
        signer: AdminSigner,
        registry: AdminRegistry,
        profiles: UserProfileRepository = InMemoryProfiles(),
        calculator: TrustScoreCalculator = trust,
    ) = DefaultAdminStatusVerifier(signer, calculator, registry, profiles)

    /** Returns a fixed score; the real calculator would need signed badge fixtures. */
    private class FakeTrustScoreCalculator(private val totalScore: Double, private val meets: Boolean) : TrustScoreCalculator {
        override fun calculate(
            badges: List<MeetupBadge>,
            firstBadgeDate: DartDateTime?,
            coAttestors: Map<String, CoAttestorData>?,
        ): TrustScore = TrustScore(
            totalScore = totalScore,
            maturityScore = 0.0,
            diversityScore = 0.0,
            qualityScore = 0.0,
            activityScore = 0.0,
            totalBadges = badges.size,
            uniqueMeetups = 1,
            uniqueSigners = 1,
            uniqueCities = 1,
            accountAgeDays = 30,
            meetsPromotionThreshold = meets,
            promotionReason = "",
            badgeValues = emptyList(),
            currentPhase = BootstrapPhase.KEIMPHASE,
            activeThresholds = TrustConfig.phases.getValue(BootstrapPhase.KEIMPHASE),
            progress = emptyMap(),
        )
        override fun bridgeScore(badges: List<MeetupBadge>): Double = 0.0
        override fun suspiciousPatterns(badges: List<MeetupBadge>, coAttestors: Map<String, CoAttestorData>?): List<String> = emptyList()
    }

    private val oneBadge = listOf(MeetupBadge(id = "b", meetupName = "Aschaffenburg", date = DartDateTime.utc(2026, 3, 7, 18), iconPath = ""))

    @Test
    fun `ohne Schluessel - no_key`() = runTest {
        val v = verifier(NpubOnlySigner(null), registry(prefs(), noRelay)).verifyAdminStatus(emptyList())
        assertEquals(AdminVerification.NO_KEY, v)
        assertEquals("Kein Nostr-Key vorhanden.", v.reason)
    }

    @Test
    fun `Super-Admin in der Bootstrap-Phase - seed_admin, reVerifyAdmin setzt adminViaSeed`() = runTest {
        val profiles = InMemoryProfiles(UserProfile(adminViaPortal = true))
        val verifier = verifier(NpubOnlySigner(AdminConstants.SUPER_ADMIN_NPUB), registry(prefs(), noRelay), profiles)

        val v = verifier.verifyAdminStatus(emptyList())
        assertEquals(AdminVerification(true, "seed_admin", "Super-Admin (super_admin)."), v)
        assertTrue(verifier.isVerifiedAdmin(emptyList()))
        assertFalse(v.shouldPublishClaim)

        val re = verifier.reVerifyAdmin(emptyList())
        assertTrue(re.profile.adminViaSeed)
        assertTrue(re.profile.adminViaPortal) // portal flag untouched
        assertTrue(re.profile.isAdminVerified)
    }

    @Test
    fun `eigener Eintrag im Registry-Cache zaehlt NICHT (zirkulaer)`() = runTest {
        val me = freshNpub()
        val clock = FakeClock()
        val store = prefs(
            AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(me, "x", "Ich"))),
            AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis(),
        )
        val registry = registry(store, noRelay, clock)
        assertEquals("local_cache", registry.checkAdmin(me).source)

        val profiles = InMemoryProfiles(UserProfile(adminViaVouch = true, adminViaSeed = true))
        val verifier = verifier(NpubOnlySigner(me), registry, profiles)
        assertEquals(AdminVerification.NOT_ADMIN, verifier.verifyAdminStatus(emptyList()))

        val re = verifier.reVerifyAdmin(emptyList())
        assertFalse(re.profile.adminViaSeed)
        assertFalse(re.profile.adminViaVouch)
        assertFalse(re.profile.isAdminVerified)
        assertEquals("Weder Trust Score noch Seed-Admin Bedingungen erfüllt.", re.verification.reason)
    }

    @Test
    fun `shouldPublishClaim nur fuer trust_score und vouch_consensus`() {
        assertTrue(AdminVerification(true, "trust_score").shouldPublishClaim)
        assertTrue(AdminVerification(true, "vouch_consensus").shouldPublishClaim)
        assertFalse(AdminVerification(false, "trust_score").shouldPublishClaim)
        assertFalse(AdminVerification(true, "seed_admin").shouldPublishClaim)
    }

    @Test
    fun `Trust Score erfuellt Schwellenwert - trust_score, reVerifyAdmin setzt adminViaVouch`() = runTest {
        assertEquals(5.0, TrustConfig.phases.getValue(BootstrapPhase.KEIMPHASE).promotionScore, 0.0)
        val profiles = InMemoryProfiles(UserProfile(adminViaSeed = true))
        val verifier = verifier(
            NpubOnlySigner(freshNpub()),
            registry(prefs(), noRelay),
            profiles,
            FakeTrustScoreCalculator(totalScore = 5.26, meets = true),
        )

        val v = verifier.verifyAdminStatus(oneBadge)
        assertEquals(AdminVerification(true, "trust_score", "Trust Score 5.3 erfüllt Schwellenwert (5.0)."), v)
        assertTrue(v.shouldPublishClaim)

        val re = verifier.reVerifyAdmin(oneBadge)
        assertEquals(v, re.verification)
        assertTrue(re.profile.adminViaVouch)
        assertFalse(re.profile.adminViaSeed)
        assertTrue(re.profile.isAdminVerified)
        assertEquals(re.profile, profiles.load())
    }

    @Test
    fun `Trust Score unter Schwellenwert - not_admin, ohne Badges wird nicht gerechnet`() = runTest {
        val below = verifier(
            NpubOnlySigner(freshNpub()),
            registry(prefs(), noRelay),
            calculator = FakeTrustScoreCalculator(totalScore = 9.0, meets = false),
        )
        assertEquals(AdminVerification.NOT_ADMIN, below.verifyAdminStatus(oneBadge))

        val empty = verifier(
            NpubOnlySigner(freshNpub()),
            registry(prefs(), noRelay),
            calculator = FakeTrustScoreCalculator(totalScore = 9.0, meets = true),
        )
        assertEquals(AdminVerification.NOT_ADMIN, empty.verifyAdminStatus(emptyList()))
    }
}
