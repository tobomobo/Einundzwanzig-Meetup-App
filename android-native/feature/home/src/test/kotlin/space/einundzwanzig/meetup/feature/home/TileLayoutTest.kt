package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.tiles.TileLayout
import space.einundzwanzig.meetup.feature.home.tiles.TileLayoutEngine
import space.einundzwanzig.meetup.feature.home.tiles.TileOrderRepository

/** Spec 12 §10 "new native unit tests" items 1–3 plus the edit/customize rules (§2.5.7, §2.5.9). */
class TileLayoutTest {

    private val defaultHidden = TileLayoutEngine.DEFAULT_HIDDEN + "trust_score"

    @Test
    fun `merge inserts missing defaults after their predecessor (vector 1)`() {
        val merged = TileLayoutEngine.mergeOrder(listOf("community", "reputation", "home_meetup", "trust_score", "x_unknown"))
        assertEquals(
            listOf(
                "community", "nostr", "converter", "btc_dashboard", "news", "portal", "events", "shoutout", "podcast",
                "satoshiduell", "portal_area", "plebrap", "organisator", "reputation", "trust_network", "home_meetup",
                "event_chats",
            ),
            merged,
        )
    }

    @Test
    fun `merge keeps user order at the front (vector 2)`() {
        assertEquals(
            listOf(
                "organisator", "home_meetup", "event_chats", "reputation", "trust_network", "community", "nostr", "converter",
                "btc_dashboard", "news", "portal", "events", "shoutout", "podcast", "satoshiduell", "portal_area", "plebrap",
            ),
            TileLayoutEngine.mergeOrder(listOf("organisator", "home_meetup")),
        )
    }

    @Test
    fun `merge of a single default tile gives the default order (vector 3)`() {
        assertEquals(TileLayoutEngine.DEFAULT_ORDER, TileLayoutEngine.mergeOrder(listOf("btc_dashboard")))
    }

    @Test
    fun `pinned rows for an admin (vector 2)`() {
        val rows = TileLayoutEngine.packRows(TileLayoutEngine.DEFAULT_ORDER, defaultHidden, pinned = true, isVisible = { true })
        assertEquals(
            listOf(
                listOf(TileId.EventChats),
                listOf(TileId.Reputation, TileId.TrustNetwork),
                listOf(TileId.Community, TileId.Converter),
                listOf(TileId.BtcDashboard),
                listOf(TileId.Organisator),
            ),
            rows,
        )
    }

    @Test
    fun `pinned rows without admin drop the organizer row`() {
        val rows = TileLayoutEngine.packRows(
            TileLayoutEngine.DEFAULT_ORDER,
            defaultHidden,
            pinned = true,
            isVisible = { it != TileId.Organisator },
        )
        assertEquals(4, rows.size)
        assertEquals(listOf(TileId.BtcDashboard), rows.last())
    }

    @Test
    fun `available rows count rows, not tiles`() {
        val rows = TileLayoutEngine.packRows(TileLayoutEngine.DEFAULT_ORDER, defaultHidden, pinned = false, isVisible = { true })
        assertEquals(
            listOf(
                listOf(TileId.Nostr, TileId.News),
                listOf(TileId.Portal, TileId.Events),
                listOf(TileId.Shoutout, TileId.Podcast),
                listOf(TileId.SatoshiDuell),
                listOf(TileId.PortalArea),
                listOf(TileId.PlebRap),
            ),
            rows,
        )
        assertEquals(6, rows.size)
    }

    @Test
    fun `home meetup is excluded and dead tiles are never drawn`() {
        val rows = TileLayoutEngine.packRows(
            listOf("home_meetup", "trust_score", "portal_connect", "reputation"),
            emptySet(),
            pinned = true,
            isVisible = { true },
        )
        // trust_score and portal_connect are defined, so they would pack if present in the order;
        // they never are (mergeOrder drops them). Here only home_meetup is excluded. 2 + 2 > 3 breaks the row.
        assertEquals(listOf(listOf(TileId.TrustScore), listOf(TileId.PortalConnect, TileId.Reputation)), rows)
        assertFalse(TileLayoutEngine.mergeOrder(listOf("trust_score", "portal_connect")).contains("trust_score"))
    }

    @Test
    fun `row height fills the target with a 120 dp minimum`() {
        assertEquals(195f, TileLayoutEngine.rowHeight(400f, 2), 0.001f)
        assertEquals(120f, TileLayoutEngine.rowHeight(300f, 5), 0.001f)
        assertEquals(500f, TileLayoutEngine.pinnedHeight(500f, hasAvailable = false), 0.001f)
        assertEquals(466f, TileLayoutEngine.pinnedHeight(500f, hasAvailable = true), 0.001f)
        assertEquals(120f, TileLayoutEngine.pinnedHeight(130f, hasAvailable = true), 0.001f)
        assertEquals(80f, TileLayoutEngine.pinnedHeight(80f, hasAvailable = true), 0.001f)
    }

    @Test
    fun `move puts the dragged tile at the target's original index`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), TileLayoutEngine.moveTile(order, "a", "c"))
        assertEquals(listOf("a", "d", "b", "c"), TileLayoutEngine.moveTile(order, "d", "b"))
        assertEquals(order, TileLayoutEngine.moveTile(order, "a", "a"))
        assertEquals(order, TileLayoutEngine.moveTile(order, "x", "a"))
    }

    @Test
    fun `pin toggles removable tiles only`() {
        val hidden = setOf("news")
        assertEquals(emptySet<String>(), TileLayoutEngine.togglePin(hidden, TileId.News))
        assertEquals(setOf("news", "nostr"), TileLayoutEngine.togglePin(hidden, TileId.Nostr))
        assertEquals(hidden, TileLayoutEngine.togglePin(hidden, TileId.EventChats))
    }

    @Test
    fun `customize sheet lists, locks and reorders`() {
        val order = TileLayoutEngine.DEFAULT_ORDER
        val active = TileLayoutEngine.sheetActive(order, defaultHidden, isVisible = { true })
        assertEquals(listOf("home_meetup", "event_chats", "reputation", "trust_network", "community"), active.take(5))
        assertTrue(TileLayoutEngine.sheetLocked("home_meetup"))
        assertTrue(TileLayoutEngine.sheetLocked("reputation"))
        assertTrue(TileLayoutEngine.sheetLocked("event_chats"))
        assertFalse(TileLayoutEngine.sheetLocked("news"))
        val available = TileLayoutEngine.sheetAvailable(defaultHidden, isVisible = { true })
        assertEquals(listOf("events", "shoutout", "podcast", "satoshiduell", "portal_area", "plebrap", "nostr", "news", "portal"), available)
        val moved = TileLayoutEngine.sheetReorder(order, active, 2, 0)
        assertEquals("reputation", moved.first())
        assertEquals(order, TileLayoutEngine.sheetReorder(order, active, 0, 99))
    }

    @Test
    fun `tile ids resolve`() {
        assertEquals(TileId.BtcDashboard, TileId.fromId("btc_dashboard"))
        assertNull(TileId.fromId("x_unknown"))
    }

    @Test
    fun `fresh install runs all migrations (vector 3)`() = runTest {
        val store = InMemoryKeyValueStore()
        val layout = TileOrderRepository(store).load()
        assertEquals(TileLayoutEngine.DEFAULT_ORDER, layout.order)
        assertEquals(TileLayoutEngine.DEFAULT_HIDDEN, layout.hidden)
        assertEquals(defaultHidden, store.getStringList(TileOrderRepository.KEY_HIDDEN)!!.toSet())
        assertNull(store.getStringList(TileOrderRepository.KEY_ORDER))
        for (key in listOf(
            TileOrderRepository.MIG_HIDE_EVENTS,
            TileOrderRepository.MIG_TRUST_HEADER,
            TileOrderRepository.MIG_HIDE_NEW_TILES,
            TileOrderRepository.MIG_HIDE_PLEBRAP,
        )) {
            assertEquals(true, store.getBool(key))
        }
    }

    @Test
    fun `saved order loses trust_score once and is merged`() = runTest {
        val store = InMemoryKeyValueStore()
        store.setStringList(TileOrderRepository.KEY_ORDER, listOf("trust_score", "reputation", "home_meetup"))
        store.setStringList(TileOrderRepository.KEY_HIDDEN, listOf("news"))
        val layout = TileOrderRepository(store).load()
        assertEquals(listOf("reputation", "home_meetup"), store.getStringList(TileOrderRepository.KEY_ORDER))
        assertEquals("reputation", layout.order.first())
        assertFalse("trust_score" in layout.order)
        assertEquals(
            setOf("news", "events", "trust_score", "satoshiduell", "portal_area", "plebrap"),
            layout.hidden,
        )
    }

    @Test
    fun `migrations run only once and save round-trips`() = runTest {
        val store = InMemoryKeyValueStore()
        val repo = TileOrderRepository(store)
        repo.load()
        repo.save(TileLayout(listOf("news", "home_meetup"), linkedSetOf("events")))
        val again = repo.load()
        assertEquals(setOf("events"), again.hidden)
        assertEquals("news", again.order.first())
    }
}
