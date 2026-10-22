package space.einundzwanzig.meetup.core.data.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore

class GatedStoresTest {
    private class Gate : MigrationGate {
        val ready = CompletableDeferred<Unit>()
        override val isComplete get() = ready.isCompleted
        override suspend fun await() = ready.await()
    }

    @Test fun readersObserversAndWritersWaitForImport() = runTest {
        val gate = Gate()
        val raw = InMemoryKeyValueStore()
        val secureRaw = InMemorySecureStore()
        val prefs = GatedKeyValueStore(raw) { gate }
        val secrets = GatedSecureStore(secureRaw, { gate }, { LegacySecretStatus { emptySet() } })
        val read = async { prefs.getString("nickname") }
        val observation = async { prefs.observeString("nickname").first() }
        val write = async { secrets.write("new", "native") }
        runCurrent()
        assertFalse(read.isCompleted)
        assertFalse(observation.isCompleted)
        assertFalse(write.isCompleted)
        assertTrue(secureRaw.entries.value.isEmpty())
        raw.setString("nickname", "Flutter nickname") // The migration uses the raw store.
        gate.ready.complete(Unit)
        assertEquals("Flutter nickname", read.await())
        assertEquals("Flutter nickname", observation.await())
        assertTrue(write.await())
    }

    @Test fun pendingLegacyKeyCannotBecomeAnAbsentOrReplacementKey() = runTest {
        val gate = Gate().apply { ready.complete(Unit) }
        var pending = setOf("nostr_nsec_key", "badge_encryption_key")
        val raw = InMemorySecureStore()
        val store = GatedSecureStore(raw, { gate }, { LegacySecretStatus { pending } })
        val unreadable = store.readChecked("nostr_nsec_key") as SecureRead.Unreadable
        assertEquals(SecureRead.Unreadable.Reason.PENDING_MIGRATION, unreadable.reason)
        assertFalse(unreadable.isPermanent)
        assertTrue(store.containsKeyChecked("nostr_nsec_key").getOrThrow())
        assertFalse(store.write("nostr_nsec_key", "replacement"))
        assertFalse(store.writeAll(mapOf("other" to "value", "badge_encryption_key" to "replacement")))
        assertFalse(store.delete("nostr_nsec_key"))
        assertFalse(store.deleteAll())
        assertTrue(raw.entries.value.isEmpty())
        raw.write("nostr_nsec_key", "recovered")
        assertTrue(store.readChecked("nostr_nsec_key") is SecureRead.Unreadable)
        pending = emptySet() // Successful retry or the confirmed give-up action.
        assertEquals("recovered", store.read("nostr_nsec_key"))
        assertTrue(store.write("badge_encryption_key", "approved new key"))
    }
}
