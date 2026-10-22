package space.einundzwanzig.meetup.feature.wallet

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.feature.wallet.domain.ReputationShare
import space.einundzwanzig.meetup.core.l10n.R as L10nR

class WalletViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeBadgeRepository()
    private val publisher = FakePublisher()
    private val profiles = FakeProfiles()

    private fun vm() = WalletViewModel(repo, publisher, profiles, FakeTime())

    private fun six(name: String = "Ulm") = (1..6).map { badge("$name$it", name = name, meetupEventId = "$name-$it") }

    @Test
    fun `state mirrors the repository and groups the badges`() {
        repo.set(listOf(badge("1", name = "Ulm"), badge("2", name = "Berlin"), badge("3", name = "Ulm", meetupEventId = "u2")))
        val s = vm().state.value
        assertTrue(s.loaded)
        assertEquals(3, s.count)
        assertEquals(listOf("Ulm", "Berlin"), s.groups.map { it.key })
        assertTrue(s.showMap && s.showShare)
        assertFalse(s.showSearch)
        assertFalse(s.showCleanup)
    }

    @Test
    fun `loads the store when nothing was read yet`() {
        val unloaded = FakeBadgeRepository(loaded = false)
        val vm = WalletViewModel(unloaded, publisher, profiles, FakeTime())
        assertEquals(1, unloaded.loadCalls)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun `empty and unreadable flags`() {
        val unreadable = FakeBadgeRepository(unreadable = true)
        val s = WalletViewModel(unreadable, publisher, profiles, FakeTime()).state.value
        assertTrue(s.isEmptyWallet)
        assertTrue(s.unreadable)
    }

    @Test
    fun `query filters groups and is cleared when the search field disappears`() {
        repo.set(six("Ulm") + badge("b", name = "Berlin"))
        val vm = vm()
        vm.onAction(WalletAction.QueryChanged("berl"))
        assertEquals(listOf("Berlin"), vm.state.value.groups.map { it.key })
        vm.onAction(WalletAction.QueryChanged("nope"))
        assertTrue(vm.state.value.groups.isEmpty())
        repo.set(listOf(badge("b", name = "Berlin")))
        assertEquals("", vm.state.value.query)
        assertEquals(1, vm.state.value.groups.size)
    }

    @Test
    fun `grouping, compact and collapse toggles`() {
        repo.set(six())
        val vm = vm()
        vm.onAction(WalletAction.SetGroupByMeetup(false))
        assertEquals(listOf("2026"), vm.state.value.groups.map { it.key })
        vm.onAction(WalletAction.ToggleCompact)
        assertTrue(vm.state.value.compact)
        vm.onAction(WalletAction.ToggleCollapsed("2026"))
        assertEquals(setOf("2026"), vm.state.value.collapsed)
        vm.onAction(WalletAction.ToggleCollapsed("2026"))
        assertTrue(vm.state.value.collapsed.isEmpty())
    }

    @Test
    fun `share text emits the exact summary with the profile npub`() = runTest {
        repo.set(listOf(badge("1")))
        val vm = vm()
        vm.onAction(WalletAction.OpenShareSheet)
        assertTrue(vm.state.value.shareSheetOpen)
        vm.events.test {
            vm.onAction(WalletAction.ShareText)
            val event = awaitItem() as WalletEvent.ShareText
            assertEquals(ReputationShare.TEXT_SUBJECT, event.subject)
            assertEquals(
                ReputationShare.summaryText(repo.badges.value, "npub1xyz", DartDateTime.fromMillisecondsSinceEpoch(NOW_MILLIS)),
                event.text,
            )
            assertEquals(L10nR.string.walletReputationCopied, event.fallback.text)
            assertEquals(Tone.Warning, event.fallback.tone)
        }
        assertFalse(vm.state.value.shareSheetOpen)
    }

    @Test
    fun `json export emits the v4 export`() = runTest {
        repo.set(listOf(badge("1")))
        val vm = vm()
        vm.events.test {
            vm.onAction(WalletAction.ExportJson)
            val event = awaitItem() as WalletEvent.ShareText
            assertEquals(ReputationShare.JSON_SUBJECT, event.subject)
            assertTrue(event.text.contains("\"version\": \"4.0\""))
            assertTrue(event.text.contains("\"nostr_npub\": \"npub1xyz\""))
            assertEquals(L10nR.string.walletJsonCopied, event.fallback.text)
        }
    }

    @Test
    fun `former collections none shows a snackbar`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onAction(WalletAction.OpenFormer)
            assertEquals(L10nR.string.walletFormerNone, (awaitItem() as WalletEvent.Snackbar).message.text)
        }
        assertNull(vm.state.value.formerCollections)
    }

    @Test
    fun `merge passes the stored index, counts added badges and republishes`() = runTest {
        val existing = badge("a", meetupEventId = "m1")
        repo.set(listOf(existing))
        val entry = FormerCollection(index = 3, at = DartDateTime.fromMillisecondsSinceEpoch(0), reason = "replaced", npub = "", badges = listOf(badge("b")))
        repo.formers = listOf(entry)
        val merged = listOf(existing, badge("b", meetupEventId = "m2"), badge("c", meetupEventId = "m3"))
        repo.mergeResult = merged
        val vm = vm()
        vm.onAction(WalletAction.OpenFormer)
        assertEquals(listOf(entry), vm.state.value.formerCollections)
        vm.onAction(WalletAction.RequestMerge(entry))
        assertNull(vm.state.value.formerCollections)
        assertSame(entry, vm.state.value.mergeCandidate)
        vm.events.test {
            vm.onAction(WalletAction.ConfirmMerge)
            val msg = (awaitItem() as WalletEvent.Snackbar).message
            assertEquals(L10nR.string.walletFormerMerged, msg.text)
            assertEquals(listOf<Any>(2), msg.args)
            assertEquals(Tone.Success, msg.tone)
        }
        assertEquals(listOf(3), repo.mergedIndices)
        assertEquals(listOf(merged), publisher.background)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `failed merge shows the error snackbar and publishes nothing`() = runTest {
        repo.mergeResult = null
        val entry = FormerCollection(0, DartDateTime.fromMillisecondsSinceEpoch(0), "rotated", "", emptyList())
        val vm = vm()
        vm.onAction(WalletAction.RequestMerge(entry))
        vm.events.test {
            vm.onAction(WalletAction.ConfirmMerge)
            val msg = (awaitItem() as WalletEvent.Snackbar).message
            assertEquals(L10nR.string.walletFormerMergeFailed, msg.text)
            assertEquals(Tone.Error, msg.tone)
        }
        assertTrue(publisher.background.isEmpty())
    }

    @Test
    fun `cleanup removes exactly the duplicates and republishes`() = runTest {
        val a = badge("a", meetupEventId = "m1")
        val dup = badge("a", meetupEventId = "m1")
        val b = badge("b", name = "Ulm", meetupEventId = "m2")
        repo.set(listOf(a, b, dup))
        val vm = vm()
        assertTrue(vm.state.value.showCleanup)
        vm.onAction(WalletAction.OpenCleanup)
        val dialog = vm.state.value.cleanup
        assertNotNull(dialog)
        assertEquals(listOf("Aschaffenburg" to 1), dialog!!.summary)
        vm.events.test {
            vm.onAction(WalletAction.ConfirmCleanup)
            val msg = (awaitItem() as WalletEvent.Snackbar).message
            assertEquals(L10nR.string.walletCleanupDone, msg.text)
            assertEquals(listOf<Any>(1), msg.args)
        }
        val kept = repo.badges.value
        assertEquals(2, kept.size)
        assertSame(a, kept[0]); assertSame(b, kept[1])
        assertEquals(listOf(kept), publisher.background)
        assertFalse(vm.state.value.showCleanup)
    }

    @Test
    fun `cleanup save failure changes nothing and shows badgesUnreadableShort`() = runTest {
        val a = badge("a", meetupEventId = "m1")
        val dup = badge("a", meetupEventId = "m1")
        repo.set(listOf(a, dup))
        repo.failWrites = true
        val vm = vm()
        vm.onAction(WalletAction.OpenCleanup)
        vm.events.test {
            vm.onAction(WalletAction.ConfirmCleanup)
            val msg = (awaitItem() as WalletEvent.Snackbar).message
            assertEquals(L10nR.string.badgesUnreadableShort, msg.text)
            assertEquals(Tone.Error, msg.tone)
        }
        assertEquals(2, repo.badges.value.size)
        assertTrue(publisher.background.isEmpty())
    }

    @Test
    fun `cleanup without duplicates shows walletCleanupNone`() = runTest {
        repo.set(listOf(badge("a")))
        val vm = vm()
        vm.events.test {
            vm.onAction(WalletAction.OpenCleanup)
            assertEquals(L10nR.string.walletCleanupNone, (awaitItem() as WalletEvent.Snackbar).message.text)
        }
        assertNull(vm.state.value.cleanup)
    }

    @Test
    fun `cleanup after the list was replaced by copies removes nothing and says so`() = runTest {
        val a = badge("a", meetupEventId = "m1")
        val dup = badge("a", meetupEventId = "m1")
        repo.set(listOf(a, dup))
        val vm = vm()
        vm.onAction(WalletAction.OpenCleanup)
        // A reload / sync merge emits new instances between opening and confirming.
        val copies = listOf(badge("a", meetupEventId = "m1"), badge("a", meetupEventId = "m1"))
        repo.set(copies)
        repo.saved.clear()
        vm.events.test {
            vm.onAction(WalletAction.ConfirmCleanup)
            assertEquals(L10nR.string.walletCleanupNone, (awaitItem() as WalletEvent.Snackbar).message.text)
        }
        assertSame(copies, repo.badges.value)
        assertTrue(repo.saved.isEmpty())
        assertTrue(publisher.background.isEmpty())
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `clearing the ViewModel runs the cleared listener once`() {
        val vm = vm()
        var calls = 0
        vm.setOnClearedListener { calls++ }
        val store = androidx.lifecycle.ViewModelStore()
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        )[WalletViewModel::class.java]
        store.clear()
        assertEquals(1, calls)
    }

    @Test
    fun `navigation actions close the share sheet`() {
        repo.set(listOf(badge("a")))
        val vm = vm()
        vm.onAction(WalletAction.OpenShareSheet)
        vm.onAction(WalletAction.ShowQr)
        assertFalse(vm.state.value.shareSheetOpen)
    }
}
