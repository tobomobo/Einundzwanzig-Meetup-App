package space.einundzwanzig.meetup.core.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Non-secret app preferences with Flutter `SharedPreferences` semantics.
 *
 * There is exactly one store per app process, backed by the Preferences DataStore file
 * `app_prefs`. **Key names are the Flutter key names without the `flutter.` prefix**, so the
 * key tables of every porting spec apply verbatim and the Flutter migration is a generic copy.
 *
 * Semantics:
 * - Every key holds at most one value of one [PrefValue] type. Writing a value of another type
 *   replaces the old value.
 * - Typed getters return `null` when the key is absent **or holds a different type** (Dart would
 *   throw a `TypeError`). Use [get] when a key may legitimately hold several types (for example
 *   `badge_restore_expect`, which tolerates a legacy String instead of a list).
 * - Writes return `true` once the change is durably persisted and `false` when the disk write
 *   failed (the failure is logged; the store is unchanged). This mirrors Dart's
 *   `Future<bool> setX(...)`. A "commit" in the Dart sense (write succeeded AND re-read equals
 *   the value) is `setString(k, v) && getString(k) == v`.
 * - Reads always see the latest committed state (there is no `reload()`).
 * - Several keys that must change together go through [edit], which applies all changes in one
 *   atomic transaction or none of them.
 *
 * Use `InMemoryKeyValueStore` (package `testing`) in unit tests.
 */
interface KeyValueStore {

    /** The value stored under [key], or `null` if the key is absent. */
    suspend fun get(key: String): PrefValue?

    /** A consistent snapshot of every entry. */
    suspend fun getAll(): Map<String, PrefValue>

    /** Whether [key] holds a value of any type (Dart `prefs.containsKey`). */
    suspend fun contains(key: String): Boolean = get(key) != null

    /** All key names currently stored (Dart `prefs.getKeys()`). */
    suspend fun keys(): Set<String> = getAll().keys

    /** Dart `prefs.getString`; `null` if absent or not a string. */
    suspend fun getString(key: String): String? = get(key).asString

    /** Dart `prefs.getBool`; `null` if absent or not a boolean. */
    suspend fun getBool(key: String): Boolean? = get(key).asBool

    /** Dart `prefs.getInt` (64-bit); `null` if absent or not an integer. */
    suspend fun getInt(key: String): Long? = get(key).asInt

    /** Dart `prefs.getDouble`; `null` if absent or not a double. */
    suspend fun getDouble(key: String): Double? = get(key).asDouble

    /** Dart `prefs.getStringList`; order and duplicates preserved; `null` if absent or not a list. */
    suspend fun getStringList(key: String): List<String>? = get(key).asStringList

    /** Stores [value] under [key], replacing any previous value of any type. */
    suspend fun set(key: String, value: PrefValue): Boolean = edit { set(key, value) }

    /** Dart `prefs.setString`. */
    suspend fun setString(key: String, value: String): Boolean = set(key, PrefValue.StringValue(value))

    /** Dart `prefs.setBool`. */
    suspend fun setBool(key: String, value: Boolean): Boolean = set(key, PrefValue.BoolValue(value))

    /** Dart `prefs.setInt`. */
    suspend fun setInt(key: String, value: Long): Boolean = set(key, PrefValue.IntValue(value))

    /** Dart `prefs.setDouble`. */
    suspend fun setDouble(key: String, value: Double): Boolean = set(key, PrefValue.DoubleValue(value))

    /** Dart `prefs.setStringList`. */
    suspend fun setStringList(key: String, value: List<String>): Boolean =
        set(key, PrefValue.StringListValue(value))

    /** Dart `prefs.remove`. Removing an absent key succeeds. */
    suspend fun remove(key: String): Boolean = edit { remove(key) }

    /** Removes every key that starts with [prefix], atomically. */
    suspend fun removeByPrefix(prefix: String): Boolean = edit { removeByPrefix(prefix) }

    /** Removes every key (Dart `prefs.clear()`, which only ever saw this app's keys). */
    suspend fun clear(): Boolean = edit { clear() }

    /**
     * Applies [block] as one atomic transaction.
     *
     * Reads inside [block] see the current state including the block's own earlier writes.
     * If [block] throws, nothing is written and the exception propagates. If persisting fails,
     * nothing is written and `false` is returned. [block] must not suspend or call back into
     * this store (that would deadlock); compute anything expensive before calling [edit].
     */
    suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean

    /** Emits the current value of [key] and every subsequent change (distinct, `null` = absent). */
    fun observe(key: String): Flow<PrefValue?>

    /** Emits the full store content now and after every change. */
    fun observeAll(): Flow<Map<String, PrefValue>>

    /** [observe] narrowed to strings; other types read as `null`. */
    fun observeString(key: String): Flow<String?> = observe(key).map { it.asString }.distinctUntilChanged()

    /** [observe] narrowed to booleans; other types read as `null`. */
    fun observeBool(key: String): Flow<Boolean?> = observe(key).map { it.asBool }.distinctUntilChanged()

    /** [observe] narrowed to integers; other types read as `null`. */
    fun observeInt(key: String): Flow<Long?> = observe(key).map { it.asInt }.distinctUntilChanged()

    /** [observe] narrowed to doubles; other types read as `null`. */
    fun observeDouble(key: String): Flow<Double?> = observe(key).map { it.asDouble }.distinctUntilChanged()

    /** [observe] narrowed to string lists; other types read as `null`. */
    fun observeStringList(key: String): Flow<List<String>?> =
        observe(key).map { it.asStringList }.distinctUntilChanged()
}

/**
 * Mutable view of a [KeyValueStore] inside one [KeyValueStore.edit] transaction.
 *
 * All calls are synchronous; changes become visible to other readers only when the
 * transaction commits.
 */
interface KeyValueEditor {

    /** The value of [key] in this transaction, or `null`. */
    operator fun get(key: String): PrefValue?

    /** All key names in this transaction. */
    fun keys(): Set<String>

    /** Stores [value] under [key], replacing any previous value of any type. */
    operator fun set(key: String, value: PrefValue)

    /** Removes [key] if present. */
    fun remove(key: String)

    /** Removes every key in this transaction. */
    fun clear()

    /** Whether [key] holds a value of any type. */
    fun contains(key: String): Boolean = get(key) != null

    /** Removes every key that starts with [prefix]. */
    fun removeByPrefix(prefix: String) {
        keys().filter { it.startsWith(prefix) }.forEach(::remove)
    }

    /** String value of [key], or `null` if absent or of another type. */
    fun getString(key: String): String? = get(key).asString

    /** Boolean value of [key], or `null` if absent or of another type. */
    fun getBool(key: String): Boolean? = get(key).asBool

    /** Integer value of [key], or `null` if absent or of another type. */
    fun getInt(key: String): Long? = get(key).asInt

    /** Double value of [key], or `null` if absent or of another type. */
    fun getDouble(key: String): Double? = get(key).asDouble

    /** List value of [key], or `null` if absent or of another type. */
    fun getStringList(key: String): List<String>? = get(key).asStringList

    /** Stores a string. */
    fun setString(key: String, value: String) = set(key, PrefValue.StringValue(value))

    /** Stores a boolean. */
    fun setBool(key: String, value: Boolean) = set(key, PrefValue.BoolValue(value))

    /** Stores a 64-bit integer. */
    fun setInt(key: String, value: Long) = set(key, PrefValue.IntValue(value))

    /** Stores a double. */
    fun setDouble(key: String, value: Double) = set(key, PrefValue.DoubleValue(value))

    /** Stores an ordered string list (duplicates allowed). */
    fun setStringList(key: String, value: List<String>) = set(key, PrefValue.StringListValue(value))
}
