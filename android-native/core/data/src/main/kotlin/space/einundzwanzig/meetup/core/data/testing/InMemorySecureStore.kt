package space.einundzwanzig.meetup.core.data.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import java.io.IOException

/**
 * In-memory [SecureStore] for unit tests of other modules (the counterpart of Dart's
 * `FlutterSecureStorage.setMockInitialValues` / `TestFlutterSecureStoragePlatform`).
 *
 * Failure injection mirrors the Dart test platforms that throw `PlatformException` per key
 * (`_BrokenRead`, `_CopyUnreadable`, `_CopyUnwritable`, `_CopyUndeletable`):
 * - [failRead]: [readChecked] reports [SecureRead.Unreadable] with [failReadReason] (and [read]
 *   returns `null`) for matching keys, **present or not**, like Dart `_BrokenRead`, whose `read`
 *   throws for every key. Like the Dart test platform, [readAll] and [containsKey] are not
 *   affected unless [failReadAll] is set.
 * - [failReadAll]: the whole store cannot be read: [readAllChecked] and [containsKeyChecked]
 *   fail, so the lenient [readAll] / [containsKey] answer "empty" / `false`.
 * - [failWrite] / [failDelete]: the operation returns `false` and changes nothing; a
 *   [writeAll] containing one failing key writes none of its entries.
 */
class InMemorySecureStore(initial: Map<String, String> = emptyMap()) : SecureStore {

    private val state = MutableStateFlow(initial.toMap())

    /** Keys whose reads fail. */
    @Volatile
    var failRead: (key: String) -> Boolean = { false }

    /** Keys whose writes fail. */
    @Volatile
    var failWrite: (key: String) -> Boolean = { false }

    /** Keys whose deletes fail (also makes [deleteAll] fail if such a key is stored). */
    @Volatile
    var failDelete: (key: String) -> Boolean = { false }

    /** The [SecureRead.Unreadable.reason] reported for [failRead] keys (default: transient I/O). */
    @Volatile
    var failReadReason: SecureRead.Unreadable.Reason = SecureRead.Unreadable.Reason.STORAGE_IO

    /** When `true`, the store as a whole cannot be read ([readAllChecked], [containsKeyChecked] fail). */
    @Volatile
    var failReadAll: Boolean = false

    /** Raw stored entries (including ones [failRead] hides), for assertions. */
    val entries: StateFlow<Map<String, String>> = state.asStateFlow()

    override suspend fun readChecked(key: String): SecureRead {
        if (failRead(key)) return SecureRead.Unreadable(IOException("Injected read failure for '$key'"), failReadReason)
        val value = state.value[key] ?: return SecureRead.Absent
        return SecureRead.Present(value)
    }

    override suspend fun write(key: String, value: String): Boolean = writeAll(mapOf(key to value))

    override suspend fun writeAll(entries: Map<String, String>): Boolean {
        if (entries.keys.any(failWrite)) return false
        state.update { it + entries }
        return true
    }

    override suspend fun delete(key: String): Boolean {
        if (failDelete(key)) return false
        state.update { it - key }
        return true
    }

    override suspend fun deleteAll(): Boolean {
        if (state.value.keys.any(failDelete)) return false
        state.value = emptyMap()
        return true
    }

    override suspend fun containsKeyChecked(key: String): Result<Boolean> =
        if (failReadAll) Result.failure(IOException("Injected store read failure")) else Result.success(key in state.value)

    override suspend fun readAllChecked(): Result<Map<String, String>> =
        if (failReadAll) {
            Result.failure(IOException("Injected store read failure"))
        } else {
            Result.success(LinkedHashMap(state.value.toSortedMap()))
        }
}
