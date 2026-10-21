package space.einundzwanzig.meetup.data.identity.internal

import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.SecureStorageUnreadableException

/** Dart `_storage.read`: value, `null` if absent, throws if present-but-unreadable (resetOnError = false). */
internal suspend fun SecureStore.readOrThrow(key: String): String? = when (val r = readChecked(key)) {
    is SecureRead.Present -> r.value
    SecureRead.Absent -> null
    is SecureRead.Unreadable -> throw SecureStorageUnreadableException(key, r.isPermanent, r.cause)
}

internal suspend fun SecureStore.writeOrThrow(key: String, value: String) {
    if (!write(key, value)) throw IdentityStorageException("Sicherer Speicher: $key konnte nicht gespeichert werden.")
}

internal suspend fun SecureStore.writeAllOrThrow(entries: Map<String, String>) {
    if (!writeAll(entries)) {
        throw IdentityStorageException("Sicherer Speicher: ${entries.keys.joinToString()} konnten nicht gespeichert werden.")
    }
}

internal suspend fun SecureStore.deleteOrThrow(key: String) {
    if (!delete(key)) throw IdentityStorageException("Sicherer Speicher: $key konnte nicht gelöscht werden.")
}

internal suspend fun KeyValueStore.setStringOrThrow(key: String, value: String) {
    if (!setString(key, value)) throw IdentityStorageException("Einstellung $key konnte nicht gespeichert werden.")
}

internal suspend fun KeyValueStore.removeOrThrow(key: String) {
    if (!remove(key)) throw IdentityStorageException("Einstellung $key konnte nicht gelöscht werden.")
}
