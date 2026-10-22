package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.internal.ProofIdentityChangeHook
import space.einundzwanzig.meetup.data.proofs.internal.ProofOwnership
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof

class ProofIdentityChangeHookTest {
    @Test fun changedIdentityClearsProofsAndBindsTheGenerationBeforeReturning() = runTest {
        val store = InMemoryKeyValueStore()
        store.setString(PlatformProof.STORAGE_KEY, "old proof")
        store.setBool(HumanityKeys.VERIFIED, true)
        ProofIdentityChangeHook(store).beforeIdentityChange("previous", "next")
        assertNull(store.getString(PlatformProof.STORAGE_KEY))
        assertEquals(false, store.getBool(HumanityKeys.VERIFIED))
        assertEquals(1L, store.getInt(ProofOwnership.GENERATION))
        assertEquals("next", store.getString(ProofOwnership.IDENTITY))
    }

    @Test fun failedCleanupBlocksIdentityChange() = runTest {
        val store = InMemoryKeyValueStore()
        store.setString(PlatformProof.STORAGE_KEY, "old proof")
        val before = store.getAll()
        store.failingKeys = setOf(ProofOwnership.IDENTITY)
        assertTrue(runCatching { ProofIdentityChangeHook(store).beforeIdentityChange("previous", "next") }.exceptionOrNull() is IllegalStateException)
        assertEquals(before, store.getAll()) // clearing, generation and binding are atomic
    }

    @Test fun unchangedOrAbsentIdentityDoesNotWrite() = runTest {
        val store = InMemoryKeyValueStore().apply { failAllWrites = true }
        val hook = ProofIdentityChangeHook(store)
        hook.beforeIdentityChange("same", "same")
        hook.beforeIdentityChange("", null)
        assertTrue(store.getAll().isEmpty())
    }

    @Test fun firstIdentityBindsWithoutClearingUnownedMigratedProofs() = runTest {
        val store = InMemoryKeyValueStore()
        store.setString(PlatformProof.STORAGE_KEY, "migrated proof")
        store.setBool(HumanityKeys.VERIFIED, true)
        ProofIdentityChangeHook(store).beforeIdentityChange("", "first")
        assertEquals("migrated proof", store.getString(PlatformProof.STORAGE_KEY))
        assertEquals(true, store.getBool(HumanityKeys.VERIFIED))
        assertNull(store.getString(ProofOwnership.PLATFORM_OWNER))
        assertNull(store.getString(ProofOwnership.HUMANITY_OWNER))
        assertEquals(1L, store.getInt(ProofOwnership.GENERATION))
        assertEquals("first", store.getString(ProofOwnership.IDENTITY))
    }

    @Test fun failedFirstIdentityBindingLeavesLegacyProofsUntouched() = runTest {
        val store = InMemoryKeyValueStore()
        store.setString(PlatformProof.STORAGE_KEY, "migrated proof")
        val before = store.getAll()
        store.failingKeys = setOf(ProofOwnership.IDENTITY)
        assertTrue(runCatching { ProofIdentityChangeHook(store).beforeIdentityChange("", "first") }.isFailure)
        assertEquals(before, store.getAll())
    }

    @Test fun firstIdentityDoesNotReassignPreviouslyOwnedProofs() = runTest {
        val store = InMemoryKeyValueStore()
        store.setString(PlatformProof.STORAGE_KEY, "foreign proof")
        store.setString(ProofOwnership.PLATFORM_OWNER, "foreign")
        ProofIdentityChangeHook(store).beforeIdentityChange("", "first")
        assertEquals("foreign", store.getString(ProofOwnership.PLATFORM_OWNER))
    }

    @Test fun logoutHasAnExplicitEmptyBinding() = runTest {
        val store = InMemoryKeyValueStore()
        ProofIdentityChangeHook(store).beforeIdentityChange("previous", null)
        assertEquals("", store.getString(ProofOwnership.IDENTITY))
        assertEquals(1L, store.getInt(ProofOwnership.GENERATION))
    }
}
