package space.einundzwanzig.meetup.core.data.prefs

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour every [KeyValueStore] implementation must share (SharedPreferences semantics).
 * Run against the DataStore store (Robolectric) and the in-memory test double.
 */
abstract class KeyValueStoreContract {

    /** A fresh, empty store. */
    protected abstract fun newStore(): KeyValueStore

    @Test
    fun allTypesRoundTrip() = runTest {
        val store = newStore()
        assertTrue(store.setString("nickname", "Satoshi"))
        assertTrue(store.setBool("haptic_enabled", false))
        assertTrue(store.setInt("rqr_session_start", 1_786_000_000L))
        assertTrue(store.setDouble("rqr_session_lat", 49.97654))
        assertTrue(store.setStringList("custom_relays", listOf("wss://b", "wss://a")))

        assertEquals("Satoshi", store.getString("nickname"))
        assertEquals(false, store.getBool("haptic_enabled"))
        assertEquals(1_786_000_000L, store.getInt("rqr_session_start"))
        assertEquals(49.97654, store.getDouble("rqr_session_lat")!!, 0.0)
        assertEquals(listOf("wss://b", "wss://a"), store.getStringList("custom_relays"))

        assertEquals(PrefValue.StringValue("Satoshi"), store.get("nickname"))
        assertEquals(PrefValue.BoolValue(false), store.get("haptic_enabled"))
        assertEquals(PrefValue.IntValue(1_786_000_000L), store.get("rqr_session_start"))
        assertEquals(PrefValue.DoubleValue(49.97654), store.get("rqr_session_lat"))
        assertEquals(PrefValue.StringListValue(listOf("wss://b", "wss://a")), store.get("custom_relays"))
    }

    @Test
    fun intsAre64Bit() = runTest {
        val store = newStore()
        store.setInt("max", Long.MAX_VALUE)
        store.setInt("min", Long.MIN_VALUE)
        store.setInt("zero", 0)
        assertEquals(Long.MAX_VALUE, store.getInt("max"))
        assertEquals(Long.MIN_VALUE, store.getInt("min"))
        assertEquals(0L, store.getInt("zero"))
    }

    @Test
    fun specialDoublesRoundTrip() = runTest {
        val store = newStore()
        store.setDouble("nan", Double.NaN)
        store.setDouble("inf", Double.NEGATIVE_INFINITY)
        store.setDouble("zero", 0.0)
        assertTrue(store.getDouble("nan")!!.isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, store.getDouble("inf")!!, 0.0)
        assertEquals(0.0, store.getDouble("zero")!!, 0.0)
    }

    @Test
    fun stringListKeepsOrderAndDuplicates() = runTest {
        val store = newStore()
        val list = listOf("c", "a", "b", "a", "", "a")
        store.setStringList("badge_restore_expect", list)
        assertEquals(list, store.getStringList("badge_restore_expect"))
    }

    @Test
    fun emptyStringAndEmptyListAreValuesNotAbsence() = runTest {
        val store = newStore()
        store.setString("badge_restore_from", "")
        store.setStringList("disabled_default_relays", emptyList())
        assertEquals("", store.getString("badge_restore_from"))
        assertEquals(emptyList<String>(), store.getStringList("disabled_default_relays"))
        assertTrue(store.contains("badge_restore_from"))
        assertTrue(store.contains("disabled_default_relays"))
    }

    @Test
    fun unicodeAndControlCharactersRoundTrip() = runTest {
        val store = newStore()
        val text = "Grüße aus Österreich ₿ 🧡 \u0000 tab\tnewline\n\"quote\""
        store.setString("s", text)
        store.setStringList("l", listOf(text, "[\"not\",\"a\",\"list\"]"))
        assertEquals(text, store.getString("s"))
        assertEquals(listOf(text, "[\"not\",\"a\",\"list\"]"), store.getStringList("l"))
    }

    @Test
    fun typedGettersReturnNullForOtherTypes() = runTest {
        val store = newStore()
        store.setString("s", "1")
        store.setInt("i", 1)
        store.setStringList("l", listOf("x"))
        assertNull(store.getInt("s"))
        assertNull(store.getBool("s"))
        assertNull(store.getStringList("s"))
        assertNull(store.getString("i"))
        assertNull(store.getDouble("i"))
        assertNull(store.getString("l"))
        assertEquals(PrefValue.StringValue("1"), store.get("s"))
    }

    @Test
    fun stringsThatLookLikeEncodedListsStayStrings() = runTest {
        val store = newStore()
        val flutterListPrefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![\"a\"]"
        store.setString("a", "[\"a\",\"b\"]")
        store.setString("b", flutterListPrefix)
        assertEquals("[\"a\",\"b\"]", store.getString("a"))
        assertNull(store.getStringList("a"))
        assertEquals(flutterListPrefix, store.getString("b"))
    }

    @Test
    fun writingAnotherTypeReplacesTheValue() = runTest {
        val store = newStore()
        store.setString("badge_restore_expect", "npub1legacy")
        store.setStringList("badge_restore_expect", listOf("npub1a", "npub1b"))
        assertNull(store.getString("badge_restore_expect"))
        assertEquals(listOf("npub1a", "npub1b"), store.getStringList("badge_restore_expect"))
        assertEquals(setOf("badge_restore_expect"), store.keys())

        store.setBool("badge_restore_expect", true)
        assertNull(store.getStringList("badge_restore_expect"))
        assertEquals(true, store.getBool("badge_restore_expect"))
    }

    @Test
    fun overwriteKeepsLatestValue() = runTest {
        val store = newStore()
        store.setString("badges_encrypted", "iv1:ct1")
        store.setString("badges_encrypted", "iv2:ct2")
        assertEquals("iv2:ct2", store.getString("badges_encrypted"))
    }

    @Test
    fun removeAndContains() = runTest {
        val store = newStore()
        assertFalse(store.contains("k"))
        assertNull(store.get("k"))
        store.setBool("k", false)
        assertTrue(store.contains("k"))
        assertTrue(store.remove("k"))
        assertFalse(store.contains("k"))
        assertNull(store.getBool("k"))
        assertTrue("removing an absent key succeeds", store.remove("k"))
    }

    @Test
    fun removeByPrefixRemovesOnlyMatchingKeys() = runTest {
        val store = newStore()
        store.setInt("rqr_session_start", 1)
        store.setInt("rqr_session_expires", 2)
        store.setString("rqr_session_meetup_id", "m")
        store.setDouble("rqr_session_lat", 1.5)
        store.setString("rqr_sessionX", "keep")
        store.setString("badges_encrypted", "keep")
        assertTrue(store.removeByPrefix("rqr_session_"))
        assertEquals(setOf("rqr_sessionX", "badges_encrypted"), store.keys())
    }

    @Test
    fun clearRemovesEverything() = runTest {
        val store = newStore()
        store.setString("a", "1")
        store.setStringList("b", listOf("2"))
        assertTrue(store.clear())
        assertEquals(emptySet<String>(), store.keys())
        assertEquals(emptyMap<String, PrefValue>(), store.getAll())
    }

    @Test
    fun getAllReturnsTypedSnapshot() = runTest {
        val store = newStore()
        store.setString("s", "x")
        store.setInt("i", 7)
        store.setStringList("l", listOf("b", "a"))
        assertEquals(
            mapOf(
                "s" to PrefValue.StringValue("x"),
                "i" to PrefValue.IntValue(7),
                "l" to PrefValue.StringListValue(listOf("b", "a")),
            ),
            store.getAll(),
        )
    }

    @Test
    fun editAppliesAllChangesAndSeesItsOwnWrites() = runTest {
        val store = newStore()
        store.setString("badges_encrypted", "old")
        store.setString("badges_encrypted_preserved", "older")
        store.setStringList("badges", listOf("{}"))

        val ok = store.edit {
            // Rotate like the badge store's stashBlob: preserved -> older, primary -> preserved.
            val preserved = getString("badges_encrypted_preserved")!!
            setString("badges_encrypted_preserved_older", preserved)
            setString("badges_encrypted_preserved", getString("badges_encrypted")!!)
            setString("badges_encrypted", "new")
            assertEquals("new", getString("badges_encrypted"))
            remove("badges")
            assertFalse(contains("badges"))
        }

        assertTrue(ok)
        assertEquals("new", store.getString("badges_encrypted"))
        assertEquals("old", store.getString("badges_encrypted_preserved"))
        assertEquals("older", store.getString("badges_encrypted_preserved_older"))
        assertNull(store.getStringList("badges"))
    }

    @Test
    fun editThatThrowsWritesNothing() = runTest {
        val store = newStore()
        store.setString("a", "before")
        val outcome = runCatching {
            store.edit {
                setString("a", "after")
                setInt("b", 1)
                error("boom")
            }
        }
        assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        assertEquals("before", store.getString("a"))
        assertFalse(store.contains("b"))
    }

    @Test
    fun largeValuesRoundTrip() = runTest {
        val store = newStore()
        // badges_former may hold up to 500 KiB of JSON; keep generous headroom.
        val big = buildString { repeat(1024 * 1024 / 16) { append("0123456789abcdef") } }
        val bigList = List(5_000) { "entry-$it-" + "x".repeat(it % 50) }
        assertTrue(store.setString("badges_former", big))
        assertTrue(store.setStringList("badge_sync_chunk_hashes", bigList))
        assertEquals(big, store.getString("badges_former"))
        assertEquals(bigList, store.getStringList("badge_sync_chunk_hashes"))
    }

    @Test
    fun observeEmitsCurrentValueAndDistinctChanges() = runTest {
        val store = newStore()
        store.setString("app_locale", "de")
        store.observeString("app_locale").test {
            assertEquals("de", awaitItem())
            store.setString("app_locale", "en")
            assertEquals("en", awaitItem())
            store.setString("app_locale", "en") // same value: no emission
            store.setBool("unrelated", true) // other key: no emission
            store.remove("app_locale")
            assertNull(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTypedFlows() = runTest {
        val store = newStore()
        store.observeStringList("custom_relays").test {
            assertNull(awaitItem())
            store.setStringList("custom_relays", listOf("wss://a", "wss://a"))
            assertEquals(listOf("wss://a", "wss://a"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.observeBool("haptic_enabled").test {
            assertNull(awaitItem())
            store.setBool("haptic_enabled", true)
            assertEquals(true, awaitItem())
            store.setString("haptic_enabled", "true") // other type reads as null
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.observeInt("n").test {
            assertNull(awaitItem())
            store.setInt("n", 5)
            assertEquals(5L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.observeDouble("d").test {
            assertNull(awaitItem())
            store.setDouble("d", 0.5)
            assertEquals(0.5, awaitItem()!!, 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeAllEmitsSnapshots() = runTest {
        val store = newStore()
        store.observeAll().test {
            assertEquals(emptyMap<String, PrefValue>(), awaitItem())
            store.edit {
                setString("a", "1")
                setInt("b", 2)
            }
            assertEquals(mapOf("a" to PrefValue.StringValue("1"), "b" to PrefValue.IntValue(2)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
