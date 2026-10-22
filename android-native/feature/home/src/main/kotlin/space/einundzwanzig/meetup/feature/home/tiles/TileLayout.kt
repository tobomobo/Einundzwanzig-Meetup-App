package space.einundzwanzig.meetup.feature.home.tiles

/**
 * Dashboard tile definitions in the exact Flutter `_tileDefs` order (spec 12 §2.5.6).
 *
 * [span] is in thirds of the row width. [removable] = `false` hides the pin button in edit mode.
 * `trust_score` and `portal_connect` are dead tiles: they are not in [TileLayoutEngine.DEFAULT_ORDER]
 * and are never drawn, but their ids stay reserved because they are persisted in `tile_hidden`
 * (spec 12 §14 risk 3).
 */
enum class TileId(val id: String, val span: Int, val removable: Boolean = true) {
    TrustScore("trust_score", 2, removable = false),
    HomeMeetup("home_meetup", 3, removable = false),
    EventChats("event_chats", 3, removable = false),
    Reputation("reputation", 1),
    Community("community", 2),
    TrustNetwork("trust_network", 2),
    Events("events", 1),
    Shoutout("shoutout", 1),
    Podcast("podcast", 1),
    SatoshiDuell("satoshiduell", 2),
    PortalArea("portal_area", 2),
    PlebRap("plebrap", 3),
    Nostr("nostr", 1),
    PortalConnect("portal_connect", 2),
    Converter("converter", 1),
    BtcDashboard("btc_dashboard", 2),
    News("news", 2),
    Portal("portal", 2),
    Organisator("organisator", 3),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        /** The tile with this persisted id, or `null` for unknown/obsolete ids. */
        fun fromId(id: String): TileId? = byId[id]
    }
}

/** Persisted layout: tile order and the "available" (un-pinned) set. */
data class TileLayout(val order: List<String>, val hidden: Set<String>)

/**
 * The pure tile engine of the dashboard (spec 12 §2.5.6, §2.5.7, §2.5.9, §8.1). Every function
 * reproduces the Dart algorithm exactly, including its quirks (greedy order-preserving rows, the
 * available header counting rows).
 */
object TileLayoutEngine {

    /** Flutter `_defaultOrder`: only these ids can ever be drawn. */
    val DEFAULT_ORDER: List<String> = listOf(
        "home_meetup", "event_chats", "reputation", "trust_network", "community", "nostr", "converter",
        "btc_dashboard", "news", "portal", "events", "shoutout", "podcast", "satoshiduell", "portal_area",
        "plebrap", "organisator",
    )

    /** Flutter `_defaultHidden`. */
    val DEFAULT_HIDDEN: Set<String> = linkedSetOf(
        "news", "shoutout", "podcast", "nostr", "portal", "events", "satoshiduell", "portal_area", "plebrap",
    )

    /** Tiles the customize sheet shows with a lock (`_requiredTiles`, not the same as `removable`). */
    val SHEET_REQUIRED: Set<String> = setOf("home_meetup", "reputation")

    /** Minimum height of a pinned row (dp). */
    const val MIN_ROW_HEIGHT: Float = 120f

    /** Gap between rows and tiles (dp, `kTileGap`). */
    const val GAP: Float = 10f

    /** Height of an available row (dp). */
    const val AVAILABLE_ROW_HEIGHT: Float = 124f

    /** Space kept below the pinned block so the "Verfügbar" header peeks in (dp). */
    const val AVAILABLE_PEEK: Float = 34f

    /**
     * §8.1: keeps the known ids of [saved] in their order and inserts every missing default tile
     * right after its nearest predecessor in [DEFAULT_ORDER] (or at the front).
     */
    fun mergeOrder(saved: List<String>): List<String> {
        val known = saved.filter { it in DEFAULT_ORDER }.toMutableList()
        for (id in DEFAULT_ORDER) {
            if (id in known) continue
            val idx = DEFAULT_ORDER.indexOf(id)
            var pos = 0
            for (i in idx - 1 downTo 0) {
                val at = known.indexOf(DEFAULT_ORDER[i])
                if (at >= 0) {
                    pos = at + 1
                    break
                }
            }
            known.add(pos, id)
        }
        return known
    }

    /**
     * `_buildTileRows`: the tiles of [order] that exist, are visible and are pinned ([pinned]) or
     * available (not [pinned]), packed greedily into rows of at most three thirds without
     * reordering. A span-3 tile always gets its own row.
     */
    fun packRows(
        order: List<String>,
        hidden: Set<String>,
        pinned: Boolean,
        isVisible: (TileId) -> Boolean,
        excludeHomeMeetup: Boolean = true,
    ): List<List<TileId>> {
        val visible = order
            .mapNotNull { TileId.fromId(it) }
            .filter { isVisible(it) && ((it.id in hidden) != pinned) }
            .filter { !excludeHomeMeetup || it != TileId.HomeMeetup }
        val rows = mutableListOf<List<TileId>>()
        var i = 0
        while (i < visible.size) {
            val tile = visible[i]
            if (tile.span == 3) {
                rows += listOf(tile)
                i++
                continue
            }
            val row = mutableListOf(tile)
            var rowSpan = tile.span
            while (i + row.size < visible.size && rowSpan < 3) {
                val next = visible[i + row.size]
                if (next.span == 3) break
                if (rowSpan + next.span > 3) break
                row += next
                rowSpan += next.span
            }
            rows += row
            i += row.size
        }
        return rows
    }

    /** `_buildScaledTileBlock`: every pinned row gets this height (≥ [MIN_ROW_HEIGHT]). */
    fun rowHeight(targetHeight: Float, rows: Int, gap: Float = GAP, min: Float = MIN_ROW_HEIGHT): Float {
        if (rows <= 0) return min
        val perRow = (targetHeight - (rows - 1) * gap) / rows
        return if (perRow >= min) perRow else min
    }

    /**
     * Height of the pinned block in a tile area of [maxHeight]: everything when nothing is
     * available, else everything minus [AVAILABLE_PEEK], clamped like Flutter (guard against a
     * shrinking area).
     */
    fun pinnedHeight(maxHeight: Float, hasAvailable: Boolean): Float {
        if (!hasAvailable) return maxHeight
        val minPinned = if (maxHeight < MIN_ROW_HEIGHT) maxHeight else MIN_ROW_HEIGHT
        return (maxHeight - AVAILABLE_PEEK).coerceIn(minPinned, maxHeight)
    }

    /**
     * `_moveTile`: [dragged] goes to the index [target] had in the ORIGINAL order. No change if
     * they are equal or one of them is unknown.
     */
    fun moveTile(order: List<String>, dragged: String, target: String): List<String> {
        if (dragged == target) return order
        val from = order.indexOf(dragged)
        val to = order.indexOf(target)
        if (from < 0 || to < 0) return order
        val list = order.toMutableList()
        list.removeAt(from)
        list.add(to, dragged)
        return list
    }

    /** `_togglePin`: no-op for non-removable tiles, else toggles membership in [hidden]. */
    fun togglePin(hidden: Set<String>, tile: TileId): Set<String> {
        if (!tile.removable) return hidden
        return if (tile.id in hidden) LinkedHashSet(hidden - tile.id) else LinkedHashSet(hidden + tile.id)
    }

    /**
     * Locked rows of the customize sheet (lock icon instead of a switch): [SHEET_REQUIRED] plus
     * the non-removable tiles. Flutter locked only [SHEET_REQUIRED], so `event_chats` could be
     * hidden there and never pinned again (no pin button, not listed as available); spec 12 §14
     * risk 4 recommends this fix.
     */
    fun sheetLocked(id: String): Boolean = id in SHEET_REQUIRED || TileId.fromId(id)?.removable == false

    /** Ids of the customize sheet's "AKTIV" list: order entries with a definition that are not hidden. */
    fun sheetActive(order: List<String>, hidden: Set<String>, isVisible: (TileId) -> Boolean): List<String> =
        order.filter { id -> TileId.fromId(id)?.let(isVisible) == true && id !in hidden }

    /** Ids of the customize sheet's "VERFÜGBAR" list: visible, removable and hidden, in definition order. */
    fun sheetAvailable(hidden: Set<String>, isVisible: (TileId) -> Boolean): List<String> =
        TileId.entries.filter { isVisible(it) && it.removable && it.id in hidden }.map { it.id }

    /**
     * Customize sheet reorder (`onReorderItem`): [oldIndex]/[newIndex] are positions in [visible]
     * (newIndex already adjusted for the removal); the item moves to the order position of the
     * tile at [newIndex].
     */
    fun sheetReorder(order: List<String>, visible: List<String>, oldIndex: Int, newIndex: Int): List<String> {
        if (oldIndex == newIndex) return order
        if (oldIndex !in visible.indices || newIndex !in visible.indices) return order
        val list = order.toMutableList()
        val oldOrderIdx = list.indexOf(visible[oldIndex])
        val newOrderIdx = list.indexOf(visible[newIndex])
        if (oldOrderIdx < 0 || newOrderIdx < 0) return order
        val item = list.removeAt(oldOrderIdx)
        list.add(newOrderIdx, item)
        return list
    }
}
