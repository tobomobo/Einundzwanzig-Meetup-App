package space.einundzwanzig.meetup.data.badges

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys

/**
 * Port of `test/badge_storage_copy_test.dart`: the badge file also lives in the secure store,
 * next to its key. Prefs stay the first place and are never cleared because of it. Every version
 * that opens counts.
 *
 * Dart's `SharedPreferences.setMockInitialValues({})` ("new app container") maps to a fresh
 * [InMemoryKeyValueStore] with the same secure store; the throwing test platforms
 * (`_CopyUnreadable`, `_CopyUnwritable`, `_CopyUndeletable`) map to the failure injection of
 * [InMemorySecureStore].
 */
class BadgeStorageCopyTest {

    private val blobKey = BadgeStoreKeys.PRIMARY

    /** One event per id: the union works per event (`unionByIdentity`), not per id. */
    private fun badge(id: String) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
    )

    private fun ids(list: List<MeetupBadge>?) = list?.map { it.id }

    @Test
    fun `saving writes the same file to both places`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(badge("a"))))
        val inPrefs = h.prefs.getString(blobKey)
        assertNotNull(inPrefs)
        assertEquals("second copy is the same blob", inPrefs, h.secure.read(blobKey))
    }

    @Test
    fun `iOS reinstall - prefs empty, keychain full`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a"), badge("b")))
        val fresh = StoreHarness(prefs = InMemoryKeyValueStore(), secure = h.secure, time = h.time)

        assertEquals(listOf("a", "b"), ids(fresh.repo.loadBadges()))
        val primary = fresh.prefs.getString(blobKey)
        assertNotNull(primary)
        assertEquals(h.secure.read(blobKey), primary)
    }

    @Test
    fun `second copy unreadable - load from prefs, nothing cleared`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        h.secure.failRead = { it == blobKey }

        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
        assertFalse(h.repo.badgesUnreadable.value)
        assertNotNull(h.prefs.getString(blobKey))
    }

    @Test
    fun `second copy unwritable - saving succeeds in prefs`() = runTest {
        val h = StoreHarness()
        h.secure.failWrite = { it == blobKey }

        assertTrue(h.repo.saveBadges(listOf(badge("a"))))
        assertNotNull(h.prefs.getString(blobKey))
        assertNull("no plaintext", h.prefs.getStringList(BadgeStoreKeys.LEGACY))
        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
    }

    @Test
    fun `reset clears both places`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        h.repo.resetAll()

        assertNull(h.prefs.getString(blobKey))
        assertNull(h.secure.read(blobKey))
        assertTrue(h.repo.loadBadges().isEmpty())
    }

    @Test
    fun `second copy unwritable - promotion still succeeds`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        h.secure.failWrite = { it == blobKey }

        assertTrue(h.repo.promoteIncoming(formerNpub = "npub1alt"))
        assertEquals(listOf("b"), ids(h.repo.loadBadges()))
        assertNull(h.prefs.getString(BadgeStoreKeys.INCOMING))
        assertEquals("npub1alt", h.repo.formerCollections().single().npub)
    }

    @Test
    fun `reset with a stuck keychain does not bring the collection back`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        h.secure.failDelete = { it == blobKey }

        h.repo.resetAll()
        assertNotNull("delete is stuck", h.secure.entries.value[blobKey])
        assertTrue("the surviving copy stays locked", h.repo.loadBadges().isEmpty())

        // The keychain works again: the next start cleans up.
        h.secure.failDelete = { false }
        assertTrue(h.repo.loadBadges().isEmpty())
        assertNull(h.secure.read(blobKey))
        assertNull(h.secure.read(BadgeStoreKeys.RESET_PENDING))
    }

    @Test
    fun `clearing with a stuck keychain deletes nothing`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        val blob = h.prefs.getString(blobKey)
        h.secure.failDelete = { it == blobKey }

        assertFalse(h.repo.saveBadges(emptyList()))
        assertEquals(blob, h.prefs.getString(blobKey))
        assertEquals(blob, h.secure.read(blobKey))
    }

    @Test
    fun `clearing with a stuck keychain leaves the collection readable`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        h.secure.failDelete = { it == blobKey }

        assertFalse(h.repo.saveBadges(emptyList()))
        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
    }

    @Test
    fun `older install without second copy reads as before`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        h.secure.delete(blobKey)

        assertEquals(listOf("a"), ids(h.repo.loadBadges()))
    }

    @Test
    fun `staging does not change the active collection, promotion does`() = runTest {
        val h = StoreHarness()
        assertTrue(h.repo.saveBadges(listOf(badge("bleibt"))))
        h.prefs.setString(BadgeStoreKeys.PRESERVED, "alt:blob")
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        assertEquals(listOf("bleibt"), ids(h.repo.loadBadges()))
        assertTrue(h.repo.promoteIncoming(formerNpub = "npub1alt"))
        assertEquals(listOf("neu"), ids(h.repo.loadBadges()))
        assertNull(h.prefs.getString(BadgeStoreKeys.INCOMING))
        assertNull(h.prefs.getString(BadgeStoreKeys.PRESERVED))
        // One version for what opens, the unreadable blob separately.
        val former = h.repo.formerCollections()
        assertEquals(listOf("replaced", "replaced"), former.map { it.reason })
        val readable = former.single { it.badges != null }
        assertEquals("npub1alt", readable.npub)
        assertEquals(listOf("bleibt"), ids(readable.badges))
        assertEquals(1, former.count { it.badges == null })
    }

    @Test
    fun `promotion after a crash does not label the new collection as the old one`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        assertTrue(h.repo.promoteIncoming(formerNpub = "npub1alt"))
        // The process died before the staging slot was removed.
        h.prefs.setString(BadgeStoreKeys.INCOMING, h.prefs.getString(blobKey)!!)

        assertTrue(h.repo.promoteIncoming(formerNpub = "npub1alt"))
        assertNull(h.prefs.getString(BadgeStoreKeys.INCOMING))
        assertEquals(listOf("b"), ids(h.repo.loadBadges()))
        assertEquals(listOf("a"), ids(h.repo.formerCollections().single().badges))
    }

    @Test
    fun `archive - a former collection can be united again`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        assertTrue(h.repo.promoteIncoming())
        assertEquals(listOf("b"), ids(h.repo.loadBadges()))

        val former = h.repo.formerCollections()
        assertEquals(listOf("a"), ids(former.single().badges))
        assertTrue(h.repo.mergeFormer(former.single().index))
        assertTrue(ids(h.repo.loadBadges())!!.containsAll(listOf("a", "b")))
        // The archive stays; uniting consumes nothing.
        assertEquals(1, h.repo.formerCollections().size)
    }

    @Test
    fun `archive - clearing and the third rotation land there`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        // Three deliberate replacements: a moves preserved -> older -> archive.
        h.repo.saveBadges(listOf(badge("b")), replaceUnreadable = true)
        h.repo.saveBadges(listOf(badge("c")), replaceUnreadable = true)
        h.repo.saveBadges(listOf(badge("d")), replaceUnreadable = true)
        var former = h.repo.formerCollections()
        assertEquals(listOf("rotated"), former.map { it.reason })
        assertEquals(listOf("a"), ids(former.single().badges))

        // Clearing on an identity switch archives the active file.
        assertTrue(h.repo.saveBadges(emptyList(), discardLocked = true))
        former = h.repo.formerCollections()
        assertEquals("cleared", former.first().reason)
        assertTrue(h.repo.loadBadges().isEmpty())
    }

    @Test
    fun `archive - second copy in the keychain survives empty prefs`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        assertTrue(h.repo.promoteIncoming())
        assertNotNull(h.secure.read(BadgeStoreKeys.FORMER))
        val fresh = StoreHarness(prefs = InMemoryKeyValueStore(), secure = h.secure, time = h.time)

        val former = fresh.repo.formerCollections()
        assertEquals(listOf("a"), ids(former.single().badges))
        assertNotNull("comes back into prefs", fresh.prefs.getString(BadgeStoreKeys.FORMER))
    }

    @Test
    fun `reset clears the archive in both places`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        assertTrue(h.repo.promoteIncoming())
        assertEquals(1, h.repo.formerCollections().size)

        h.repo.resetAll()

        assertTrue(h.repo.formerCollections().isEmpty())
        assertNull(h.secure.read(BadgeStoreKeys.FORMER))
        assertTrue(h.repo.loadBadges().isEmpty())
    }

    @Test
    fun `clearing without reset does not delete the archive but fills it`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.repo.stageIncoming(listOf(badge("b"))))
        assertTrue(h.repo.promoteIncoming())
        assertEquals(1, h.repo.formerCollections().size)

        // An empty backup without identity, as restoreBadges writes it.
        assertTrue(h.repo.saveBadges(emptyList(), replaceUnreadable = true))

        val former = h.repo.formerCollections()
        assertEquals(listOf("cleared", "replaced"), former.map { it.reason })
        assertEquals(listOf("b"), ids(former.first().badges))
    }

    @Test
    fun `plaintext from old installs goes into the archive`() = runTest {
        val h = StoreHarness()
        h.repo.saveBadges(listOf(badge("bleibt")))
        h.prefs.setStringList(BadgeStoreKeys.LEGACY, listOf(badge("klar").toJsonString()))

        assertTrue(h.repo.saveBadges(emptyList()))

        val former = h.repo.formerCollections()
        assertTrue(ids(former.single().badges)!!.containsAll(listOf("bleibt", "klar")))
        assertNull(h.prefs.getStringList(BadgeStoreKeys.LEGACY))
    }

    @Test
    fun `plaintext without a key is not deleted`() = runTest {
        val h = StoreHarness()
        h.prefs.setStringList(BadgeStoreKeys.LEGACY, listOf(badge("klar").toJsonString()))
        // Native writes load first (Dart relied on main() doing so), and that load migrates the
        // plaintext with a new key. "Without a key" therefore means: no key can be created.
        h.secure.failWrite = { it == BadgeStoreKeys.KEY }

        assertFalse(h.repo.saveBadges(emptyList()))
        assertNotNull(h.prefs.getStringList(BadgeStoreKeys.LEGACY))
    }

    @Test
    fun `plaintext cleared before any load is migrated and archived, not deleted`() = runTest {
        val h = StoreHarness()
        h.prefs.setStringList(BadgeStoreKeys.LEGACY, listOf(badge("klar").toJsonString()))

        assertTrue(h.repo.saveBadges(emptyList()))

        assertEquals(listOf("klar"), ids(h.repo.formerCollections().single().badges))
        assertNull(h.prefs.getStringList(BadgeStoreKeys.LEGACY))
    }
}
