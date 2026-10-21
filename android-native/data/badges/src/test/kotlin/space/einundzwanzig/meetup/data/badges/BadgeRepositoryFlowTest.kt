package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.review.BadgeReviewDemo

/**
 * Native-only behaviour of the repository that replaces Dart globals: the `badges` StateFlow
 * (Dart `myBadges`) and `savedEvents` (Dart `onBadgesSaved`), plus the review-demo hook
 * (`app_review_demo_test.dart` cases).
 */
class BadgeRepositoryFlowTest {

    private fun badge(id: String) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
    )

    @Test
    fun `save, clear and promote emit savedEvents and update the state`() = runTest {
        val h = StoreHarness()
        val events = async(start = CoroutineStart.UNDISPATCHED) { h.repo.savedEvents.take(3).toList() }

        assertTrue(h.repo.saveBadges(listOf(badge("a"))))
        assertEquals(listOf("a"), h.repo.badges.value.map { it.id })
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        assertTrue(h.repo.promoteIncoming())
        assertEquals(listOf("b"), h.repo.badges.value.map { it.id })
        assertTrue(h.repo.saveBadges(emptyList()))
        assertTrue(h.repo.badges.value.isEmpty())

        assertEquals(3, events.await().size)
    }

    @Test
    fun `a refused save emits nothing`() = runTest {
        val h = StoreHarness()
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.repo.savedEvents.collect { events += it } }

        h.prefs.failAllWrites = true
        assertFalse(h.repo.saveBadges(listOf(badge("a"))))
        assertTrue(events.isEmpty())
        assertTrue(h.repo.badges.value.isEmpty())

        // Positive control: the collector does see a successful save.
        h.prefs.failAllWrites = false
        assertTrue(h.repo.saveBadges(listOf(badge("a"))))
        assertEquals(1, events.size)
    }

    @Test
    fun `loaded tells not-loaded from an empty wallet`() = runTest {
        val h = StoreHarness()
        assertFalse(h.repo.loaded.value)
        assertTrue(h.repo.loadBadges().isEmpty())
        assertTrue(h.repo.loaded.value)
        assertFalse(h.repo.badgesUnreadable.value)
    }

    @Test
    fun `update is an atomic read-modify-write`() = runTest {
        val h = StoreHarness()
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.repo.savedEvents.collect { events += it } }

        assertTrue(h.repo.update { it + badge("a") })
        assertTrue(h.repo.update { it + badge("b") })
        assertEquals(listOf("a", "b"), h.repo.loadBadges().map { it.id })
        assertEquals(2, events.size)

        // null and the same instance mean "no change": nothing written, no event.
        assertTrue(h.repo.update { null })
        assertTrue(h.repo.update { it })
        assertEquals(2, events.size)

        // Concurrent writers do not lose each other's badges.
        val jobs = (1..20).map { i -> async { h.repo.update { it + badge("c$i") } } }
        assertTrue(jobs.awaitAll().all { it })
        assertEquals(22, h.repo.loadBadges().size)
    }

    @Test
    fun `review demo payload is recognised before any parsing`() {
        assertTrue(BadgeReviewDemo.isReviewDemoPayload("21review:einundzwanzig-meetup-demo"))
        assertTrue(BadgeReviewDemo.isReviewDemoPayload("  21review:einundzwanzig-meetup-demo \n"))
        assertFalse(BadgeReviewDemo.isReviewDemoPayload("21:something"))
        assertFalse(BadgeReviewDemo.isReviewDemoPayload(null))
    }
}
