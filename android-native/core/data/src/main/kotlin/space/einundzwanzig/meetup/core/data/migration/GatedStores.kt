package space.einundzwanzig.meetup.core.data.migration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore

class GatedKeyValueStore(private val delegate: KeyValueStore, private val gate: () -> MigrationGate) : KeyValueStore {
    override suspend fun get(key: String): PrefValue? { gate().await(); return delegate.get(key) }
    override suspend fun getAll(): Map<String, PrefValue> { gate().await(); return delegate.getAll() }
    override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean { gate().await(); return delegate.edit(block) }
    override fun observe(key: String): Flow<PrefValue?> = flow { gate().await(); emitAll(delegate.observe(key)) }
    override fun observeAll(): Flow<Map<String, PrefValue>> = flow { gate().await(); emitAll(delegate.observeAll()) }
}

class GatedSecureStore(
    private val delegate: SecureStore,
    private val gate: () -> MigrationGate,
    private val legacy: () -> LegacySecretStatus,
) : SecureStore {
    private suspend fun pending(): Set<String> { gate().await(); return legacy().pendingLegacySecrets() }
    override suspend fun readChecked(key: String): SecureRead = if (key in pending()) {
        SecureRead.Unreadable(IllegalStateException("Legacy secret pending migration: $key"), SecureRead.Unreadable.Reason.PENDING_MIGRATION)
    } else delegate.readChecked(key)
    override suspend fun write(key: String, value: String): Boolean = writeAll(mapOf(key to value))
    override suspend fun writeAll(entries: Map<String, String>): Boolean =
        if (pending().any { it in entries }) false else delegate.writeAll(entries)
    override suspend fun delete(key: String): Boolean = if (key in pending()) false else delegate.delete(key)
    override suspend fun deleteAll(): Boolean = if (pending().isNotEmpty()) false else delegate.deleteAll()
    override suspend fun containsKeyChecked(key: String): Result<Boolean> =
        if (key in pending()) Result.success(true) else delegate.containsKeyChecked(key)
    override suspend fun readAllChecked(): Result<Map<String, String>> {
        val pending = pending()
        return delegate.readAllChecked().map { entries -> entries.filterKeys { it !in pending } }
    }
}
