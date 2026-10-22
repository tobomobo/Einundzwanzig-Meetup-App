package space.einundzwanzig.meetup.feature.home.tiles

import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence of the dashboard layout (`tile_order`, `tile_hidden`) with the four one-time
 * migrations of `_loadTileOrder` (spec 12 §4). Uses the shared `KeyValueStore` with the Flutter
 * key names; `:data:migration` copied the Flutter values there.
 */
@Singleton
class TileOrderRepository @Inject constructor(private val store: KeyValueStore) {

    /**
     * Runs the pending migrations (each persists `tile_hidden` right away) and returns the layout
     * to show: the merged saved order with the saved hidden set, or the in-memory defaults when no
     * order was ever saved (Flutter ignores the migrated hidden set in that branch).
     */
    suspend fun load(): TileLayout {
        val saved = store.getStringList(KEY_ORDER)?.toMutableList()
        val savedHidden: MutableSet<String> = store.getStringList(KEY_HIDDEN)?.let { LinkedHashSet(it) }
            ?: LinkedHashSet(TileLayoutEngine.DEFAULT_HIDDEN)

        if (store.getBool(MIG_HIDE_EVENTS) != true) {
            savedHidden += "events"
            store.setBool(MIG_HIDE_EVENTS, true)
            store.setStringList(KEY_HIDDEN, savedHidden.toList())
        }
        if (store.getBool(MIG_TRUST_HEADER) != true) {
            savedHidden += "trust_score"
            saved?.remove("trust_score")
            store.setBool(MIG_TRUST_HEADER, true)
            store.setStringList(KEY_HIDDEN, savedHidden.toList())
            if (saved != null) store.setStringList(KEY_ORDER, saved)
        }
        if (store.getBool(MIG_HIDE_NEW_TILES) != true) {
            savedHidden += listOf("satoshiduell", "portal_area", "plebrap")
            store.setBool(MIG_HIDE_NEW_TILES, true)
            store.setStringList(KEY_HIDDEN, savedHidden.toList())
        }
        if (store.getBool(MIG_HIDE_PLEBRAP) != true) {
            savedHidden += "plebrap"
            store.setBool(MIG_HIDE_PLEBRAP, true)
            store.setStringList(KEY_HIDDEN, savedHidden.toList())
        }
        return if (!saved.isNullOrEmpty()) {
            TileLayout(TileLayoutEngine.mergeOrder(saved), savedHidden)
        } else {
            TileLayout(TileLayoutEngine.DEFAULT_ORDER, LinkedHashSet(TileLayoutEngine.DEFAULT_HIDDEN))
        }
    }

    /** `_saveTileOrder`: writes both lists (hidden in insertion order). */
    suspend fun save(layout: TileLayout): Boolean = store.edit {
        setStringList(KEY_ORDER, layout.order)
        setStringList(KEY_HIDDEN, layout.hidden.toList())
    }

    companion object {
        const val KEY_ORDER = "tile_order"
        const val KEY_HIDDEN = "tile_hidden"
        const val MIG_HIDE_EVENTS = "mig_hide_events_v1"
        const val MIG_TRUST_HEADER = "mig_trust_header_v1"
        const val MIG_HIDE_NEW_TILES = "mig_hide_new_tiles_v131"
        const val MIG_HIDE_PLEBRAP = "mig_hide_plebrap_v1"
    }
}
