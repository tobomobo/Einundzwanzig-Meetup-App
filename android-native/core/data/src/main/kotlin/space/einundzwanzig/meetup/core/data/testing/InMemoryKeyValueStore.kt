package space.einundzwanzig.meetup.core.data.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue

/**
 * In-memory [KeyValueStore] for unit tests of other modules (the counterpart of Dart's
 * `SharedPreferences.setMockInitialValues`). Same semantics as the production store, plus
 * write-failure injection to exercise "commit failed" paths.
 *
 * ```
 * val prefs = InMemoryKeyValueStore(mapOf("signing_mode" to PrefValue.StringValue("nip46")))
 * ```
 */
class InMemoryKeyValueStore(initial: Map<String, PrefValue> = emptyMap()) : KeyValueStore {

    private val state = MutableStateFlow(initial.mapValues { (_, v) -> v.frozen() })
    private val mutex = Mutex()

    /** When `true`, every write fails (returns `false`) and leaves the store unchanged. */
    @Volatile
    var failAllWrites: Boolean = false

    /** A transaction that writes or removes any of these keys fails atomically. */
    @Volatile
    var failingKeys: Set<String> = emptySet()

    /** Live view of the stored entries, for assertions. */
    val entries: StateFlow<Map<String, PrefValue>> = state.asStateFlow()

    override suspend fun get(key: String): PrefValue? = state.value[key]

    override suspend fun getAll(): Map<String, PrefValue> = state.value

    override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean = mutex.withLock {
        val editor = MapEditor(LinkedHashMap(state.value))
        editor.block()
        if (failAllWrites || editor.touched.any { it in failingKeys }) return@withLock false
        state.value = editor.map.toMap()
        true
    }

    override fun observe(key: String): Flow<PrefValue?> = state.map { it[key] }.distinctUntilChanged()

    override fun observeAll(): Flow<Map<String, PrefValue>> = state

    private class MapEditor(val map: MutableMap<String, PrefValue>) : KeyValueEditor {
        val touched = mutableSetOf<String>()

        override fun get(key: String): PrefValue? = map[key]

        override fun keys(): Set<String> = map.keys.toSet()

        override fun set(key: String, value: PrefValue) {
            touched += key
            map[key] = value.frozen()
        }

        override fun remove(key: String) {
            touched += key
            map.remove(key)
        }

        override fun clear() {
            touched += map.keys
            map.clear()
        }
    }
}

/** Defensive copy so a caller mutating its list afterwards cannot change stored state. */
private fun PrefValue.frozen(): PrefValue =
    if (this is PrefValue.StringListValue) PrefValue.StringListValue(value.toList()) else this
