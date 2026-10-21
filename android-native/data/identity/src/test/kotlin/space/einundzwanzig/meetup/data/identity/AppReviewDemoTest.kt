package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.internal.DefaultAppReviewSignIn
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadge
import space.einundzwanzig.meetup.data.identity.review.ReviewDemoBadgeWriter
import java.util.Optional

/** Port of `app_review_demo_test.dart` (fixture tables) plus the sign-in rules of spec 01 §8.7. */
class AppReviewDemoTest {

    private val fixture = Fixtures.identity["appReviewDemo"]!!.jsonObject

    @Test
    fun demoQrOnlyWithTheReviewPayload() {
        assertEquals(fixture["payload"]!!.jsonPrimitive.content, AppReviewDemo.PAYLOAD)
        for (case in fixture["matches"]!!.jsonArray) {
            val input = case.jsonObject["input"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("$input", case.jsonObject["expect"]!!.jsonPrimitive.boolean, AppReviewDemo.matches(input))
        }
    }

    @Test
    fun demoLoginOnlyWithTheReviewCredentials() {
        for (case in fixture["isDemoLogin"]!!.jsonArray) {
            val c = case.jsonObject
            assertEquals(
                c.toString(),
                c["expect"]!!.jsonPrimitive.boolean,
                AppReviewDemo.isDemoLogin(c["user"]!!.jsonPrimitive.content, c["pass"]!!.jsonPrimitive.content),
            )
        }
    }

    private class Recorder : ReviewDemoBadgeWriter {
        val badges = mutableListOf<ReviewDemoBadge>()
        override suspend fun saveDemoBadge(badge: ReviewDemoBadge) {
            badges += badge
        }
    }

    private fun signIn(id: TestIdentity, offered: Boolean, recorder: Recorder? = null) =
        DefaultAppReviewSignIn(offered, id.prefs, id.keyStore, id.easyAuth, id.profiles, Optional.ofNullable(recorder), id.time)

    @Test
    fun signInIsGatedByTheBuildFlag() = runBlocking {
        val id = TestIdentity()
        try {
            signIn(id, offered = false).signIn("AppReview", "ReviewDemo1")
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Demo sign-in is not available in this build.", e.message)
        }
        assertFalse(id.keyStore.hasKey())
    }

    @Test
    fun wrongCredentialsGiveNoHints() = runBlocking {
        try {
            signIn(TestIdentity(), offered = true).signIn("AppReview", "wrong")
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Unknown user name or password.", e.message)
        }
    }

    @Test
    fun neverOverARealIdentity() = runBlocking {
        val id = TestIdentity()
        id.keys.importNsec(KeyPair.generate().nsec)
        try {
            signIn(id, offered = true).signIn("AppReview", "ReviewDemo1")
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Demo sign-in is only available on a fresh install.", e.message)
        }
    }

    @Test
    fun freshInstallGetsTheDemoIdentityAndBadge() = runBlocking {
        val id = TestIdentity()
        val recorder = Recorder()
        signIn(id, offered = true, recorder).signIn(" AppReview ", "ReviewDemo1")
        val profile = id.profiles.load()
        assertEquals("AppReview", profile.nickname)
        assertTrue(profile.isReviewDemo)
        assertEquals("Berlin", profile.homeMeetupId)
        assertEquals(listOf("Berlin"), profile.favoriteMeetupIds)
        assertFalse(profile.isAdmin)
        assertEquals(true, id.prefs.getBool(AppReviewDemo.PREFS_FLAG))
        val badge = recorder.badges.single()
        assertEquals("review-demo-badge-1", badge.id)
        assertEquals(id.time.nowMicros() - 7L * 86_400_000_000L, badge.dateMicros)

        // Re-entry keeps the same key.
        val priv = id.keyStore.getPrivHex()
        signIn(id, offered = true, recorder).signIn("AppReview", "ReviewDemo1")
        assertEquals(priv, id.keyStore.getPrivHex())
        assertEquals(1, recorder.badges.size)
        assertEquals(PrefValue.BoolValue(true), id.prefs.get(IdentityKeys.APP_REVIEW_DEMO))
    }
}
