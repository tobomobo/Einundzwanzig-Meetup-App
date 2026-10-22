package space.einundzwanzig.meetup.data.identity

import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.identity.internal.*
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys

class ParitySafetyTest {
    @Test fun overflowRecoveryCopiesAreIndividuallyExportedDiscardedAndReset() = runBlocking {
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        val id = TestIdentity(secureInit = mapOf(
            "legacy_recovered/nostr_nsec_key" to Nip19.encodeNsec(a.privateKeyHex),
            "legacy_recovered/nostr_nsec_key#2" to Nip19.encodeNsec(b.privateKeyHex),
            "legacy_recovered/nostr_npub_key#2" to b.npub,
            "legacy_recovered/badge_encryption_key#2" to "badge-key",
        ))
        val candidates = id.recovery.candidates()
        assertEquals(listOf(1, 2), candidates.map { it.slot })
        assertEquals(Nip19.encodeNsec(b.privateKeyHex), id.recovery.exportNsec(candidates[1]))
        id.recovery.discard(candidates[1])
        assertEquals(a.npub, id.recovery.candidates().single().npub)
        assertEquals("badge-key", id.secure.read("legacy_recovered/badge_encryption_key#2"))
        id.secure.write("legacy_esp/nostr_nsec_key#16", "unreadable")
        id.secure.failRead = { it.endsWith("#16") }
        id.recovery.discardAll()
        assertNull(id.secure.entries.value["legacy_esp/nostr_nsec_key#16"])
    }

    @Test fun independentlyAllocatedSlotsDoNotDiscardAnotherPrivateIdentity() = runBlocking {
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        val id = TestIdentity(secureInit = mapOf(
            "legacy_recovered/nostr_nsec_key#2" to Nip19.encodeNsec(a.privateKeyHex),
            "legacy_recovered/nostr_priv_hex#2" to b.privateKeyHex,
        ))
        val candidates = id.recovery.candidates()
        assertEquals(setOf(a.npub, b.npub), candidates.map { it.npub }.toSet())
        id.recovery.discard(candidates.first { it.npub == a.npub })
        assertEquals(b.npub, id.recovery.candidates().single().npub)
        assertEquals(Nip19.encodeNsec(b.privateKeyHex), id.recovery.exportNsec(id.recovery.candidates().single()))
    }

    @Test fun scryptOomIsTypedAndDoesNotChangeTheActiveIdentity() = runBlocking {
        val id = TestIdentity()
        val active = id.keys.generateKeyPair()
        id.keys.decryptKey = { _, _ -> throw OutOfMemoryError("simulated scrypt allocation") }
        val failure = runCatching { id.keys.importNcryptsec("ncryptsec1dummy", "pw") }.exceptionOrNull()
        assertEquals(IdentityError.MEMORY, (failure as KeyOperationException).reason)
        assertEquals(active.npub, id.keyStore.getNpub())
    }

    @Test fun changedKeyIsInvisibleUntilProofCleanupCompletesAndFailureKeepsOldKey() = runTest {
        val old = KeyPair.generate()
        val next = KeyPair.generate()
        val prefs = InMemoryKeyValueStore()
        val secure = InMemorySecureStore(mapOf(IdentityKeys.NPUB to old.npub))
        prefs.setString("platform_proofs", "old proof")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fail = true
        val barrier = IdentitySwitchBarrier(prefs, secure, Provider { setOf(IdentityChangeHook { previous, current ->
            assertEquals(old.npub, previous)
            assertEquals(next.npub, current)
            started.complete(Unit)
            release.await()
            if (fail) error("cleanup failed")
            prefs.remove("platform_proofs")
        }) })
        val keys = DefaultSecureKeyStore(secure, prefs, IdentityChanges(), barrier)
        val attempt = async { runCatching { keys.saveKeys(Nip19.encodeNsec(next.privateKeyHex), next.npub, next.privateKeyHex) } }
        started.await()
        assertEquals(old.npub, secure.read(IdentityKeys.NPUB))
        release.complete(Unit)
        assertTrue(attempt.await().isFailure)
        assertEquals(old.npub, secure.read(IdentityKeys.NPUB))
        fail = false
        keys.saveKeys(Nip19.encodeNsec(next.privateKeyHex), next.npub, next.privateKeyHex)
        assertNull(prefs.getString("platform_proofs"))
        assertEquals(next.npub, secure.read(IdentityKeys.NPUB))
    }

    @Test fun amberIdentityAndReadySignalWaitForProofCleanup() = runTest {
        val id = TestIdentity()
        val old = id.keys.generateKeyPair()
        val next = KeyPair.generate()
        id.nip55.installed = true
        id.nip55.publicKey = { next.publicKeyHex }
        id.prefs.setString("platform_proofs", "old-proof")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val barrier = IdentitySwitchBarrier(id.prefs, id.secure, Provider { setOf(IdentityChangeHook { _, _ ->
            started.complete(Unit)
            release.await()
            id.prefs.remove("platform_proofs")
        }) })
        val service = DefaultSigningService(id.prefs, id.keyStore, id.relayConfig, id.nip55, id.clientFactory, id.time, Dispatchers.Unconfined, barrier)
        var ready = false
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            service.signerReady.collect {
                assertNull(id.prefs.getString("platform_proofs"))
                assertEquals(next.npub, service.npub())
                ready = true
            }
        }
        val result = async { service.connectAmber() }
        started.await()
        assertEquals(old.npub, service.npub())
        assertFalse(ready)
        release.complete(Unit)
        assertTrue(result.await() is AmberConnectResult.Success)
        testScheduler.runCurrent()
        assertTrue(ready)
    }

    @Test fun firstKeyAndFirstKeyAfterLogoutWaitForTheProofBinding() = runTest {
        for (afterLogout in listOf(false, true)) {
            val next = KeyPair.generate()
            val old = if (afterLogout) KeyPair.generate() else null
            val prefs = InMemoryKeyValueStore()
            val secure = InMemorySecureStore(old?.let { mapOf(IdentityKeys.NPUB to it.npub) } ?: emptyMap())
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<Pair<String, String?>>()
            val barrier = IdentitySwitchBarrier(prefs, secure, Provider { setOf(IdentityChangeHook { previous, current ->
                calls += previous to current
                if (current != null) {
                    started.complete(Unit)
                    release.await()
                }
            }) })
            val keys = DefaultSecureKeyStore(secure, prefs, IdentityChanges(), barrier)
            if (afterLogout) keys.deleteKeys()
            val create = async { keys.saveKeys(Nip19.encodeNsec(next.privateKeyHex), next.npub, next.privateKeyHex) }
            started.await()
            assertNull(secure.read(IdentityKeys.NPUB))
            assertEquals("" to next.npub, calls.last())
            if (afterLogout) assertEquals(old!!.npub to null, calls.first())
            release.complete(Unit)
            create.await()
            assertEquals(next.npub, secure.read(IdentityKeys.NPUB))
        }
    }

    @Test fun failedFirstKeyBindingAndMissingHookBothBlockPersistence() = runTest {
        for (missingHook in listOf(false, true)) {
            val next = KeyPair.generate()
            val prefs = InMemoryKeyValueStore()
            val secure = InMemorySecureStore()
            val barrier = IdentitySwitchBarrier(prefs, secure, Provider {
                if (missingHook) emptySet() else setOf(IdentityChangeHook { previous, current ->
                    assertEquals("", previous)
                    assertEquals(next.npub, current)
                    error("binding failed")
                })
            })
            val keys = DefaultSecureKeyStore(secure, prefs, IdentityChanges(), barrier)
            assertTrue(runCatching { keys.saveKeys(Nip19.encodeNsec(next.privateKeyHex), next.npub, next.privateKeyHex) }.isFailure)
            assertNull(secure.read(IdentityKeys.NPUB))
            assertNull(secure.read(IdentityKeys.NSEC))
            assertNull(secure.read(IdentityKeys.PRIV_HEX))
        }
    }

    @Test fun kind21000CompatibilityFacadeUsesOriginalEventCoordinates() = runBlocking {
        val id = TestIdentity()
        id.keys.generateKeyPair()
        val api = LegacyNostrService(id.signing, id.time)
        val badge = api.signBadge("wien", "Wien 🧡", "2026-10-22", 900001)
        assertTrue(api.verify(badge["content"] as String, badge["signature"] as String, badge["pubkey"] as String,
            badge["event_id"] as String, badge["created_at"] as Long,
            listOf(listOf("meetup", "wien"), listOf("block", "900001"))))
        assertFalse(api.verify("tampered", badge["signature"] as String, badge["pubkey"] as String,
            badge["event_id"] as String, badge["created_at"] as Long))
    }
}
