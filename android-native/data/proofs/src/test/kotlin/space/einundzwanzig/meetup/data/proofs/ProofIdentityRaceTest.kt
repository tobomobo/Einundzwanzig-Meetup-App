package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.internal.DefaultHumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.internal.DefaultPlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.internal.ProofIdentityChangeHook
import space.einundzwanzig.meetup.data.proofs.internal.ProofOwnership
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver

/** Deterministic handoffs at the transaction boundary, after the old npub check has passed. */
class ProofIdentityRaceTest {
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class PausedStore(val backing: InMemoryKeyValueStore) : KeyValueStore by backing {
        var nextEdit: Gate? = null
        override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean {
            val gate = nextEdit
            nextEdit = null // identity cleanup must still be able to commit while this writer waits
            if (gate != null) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            return backing.edit(block)
        }
    }

    private class Fixture {
        val backing = InMemoryKeyValueStore(mapOf(
            RelayConfig.KEY_DISABLED_DEFAULTS to PrefValue.StringListValue(RelayConfig.DEFAULT_RELAYS),
            RelayConfig.KEY_CUSTOM_RELAYS to PrefValue.StringListValue(listOf("wss://r1")),
        ))
        val store = PausedStore(backing)
        val identity = FakeIdentity()
        val next = KeyPair.generate()
        val relays = FakeRelays()
        val time = FakeTime()
        val resolver = object : ZapProviderResolver {
            override suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String) = true
            override suspend fun providerPubkeyFor(recipientPubkey: String): String? = null
            override suspend fun providerPubkeyFromLnurlp(url: String): String? = null
        }
        fun platform(asIdentity: FakeIdentity = identity) = DefaultPlatformProofRepository(store, asIdentity, time, log, testDispatcher)
        fun humanity(asIdentity: FakeIdentity = identity) = DefaultHumanityProofRepository(
            store, RelayConfig(store, relays.connector), RelayClient(relays.connector), resolver, asIdentity, time, log, testDispatcher,
        )
        val hook = ProofIdentityChangeHook(store)
        val backup = PlatformProof("telegram", "backup", "ab".repeat(64), 1L).toStorageJson()
        val humanityBackup = jsonObjectOf("verified" to true, "receipt_event_id" to "ab".repeat(32), "first_zap_at" to 1L)

        suspend fun switch() {
            hook.beforeIdentityChange(identity.npub()!!, next.npub)
            identity.npubOverride = next.npub // key persistence follows the synchronous hook
        }

        suspend fun saveFreshNextProofs(): Map<String, PrefValue> {
            assertEquals(next.npub, identity.npub())
            assertTrue(platform(FakeIdentity(next)).createProof("telegram", "fresh-B") is ProofCreateResult.Success)
            relays.add("wss://r1", receiptWithSender(KeyPair.generate(), next, KeyPair.generate().publicKeyHex))
            assertTrue(humanity().checkForZaps().found)
            assertFreshNextProofs()
            return store.getAll()
        }

        suspend fun assertFreshNextProofs() {
            // New repository instances, UI flows, backup/QR and publishing must all see B.
            assertEquals(listOf("fresh-B"), platform().getSavedProofs().map { it.username })
            assertEquals(listOf("fresh-B"), platform().getProofsForPublishing().values.map { it.username })
            assertEquals(listOf("fresh-B"), platform().proofs.first().map { it.username })
            assertTrue(humanity().getStatus().verified)
            assertFalse(humanity().getStatus().needsReverification)
            assertTrue(humanity().status.first().verified)
            assertNotNull(humanity().getProofForPublishing())
            assertEquals(next.npub, store.getString(ProofOwnership.PLATFORM_OWNER))
            assertEquals(next.npub, store.getString(ProofOwnership.HUMANITY_OWNER))
        }
        fun pauseNextEdit() = Gate().also { store.nextEdit = it }

        suspend fun assertNoProofs() {
            // All entry points used by UI, backup/QR, and a publisher started under the new key.
            assertTrue(platform().getSavedProofs().isEmpty())
            assertTrue(platform().getProofsForPublishing().isEmpty())
            assertTrue(platform().proofs.first().isEmpty())
            assertFalse(humanity().getStatus().verified)
            assertEquals("", humanity().getStatus().receiptEventId)
            assertFalse(humanity().status.first().verified)
            assertNull(humanity().getProofForPublishing())
        }
    }

    @Test fun proofCommitPausedAfterIdentityCheckCannotOverwriteFreshNextProofs() = runTest {
        val f = Fixture()
        val gate = f.pauseNextEdit()
        val create = async { f.platform().createProof("telegram", "old") }
        gate.entered.await()
        f.switch()
        val fresh = f.saveFreshNextProofs()
        gate.release.complete(Unit)
        assertTrue(create.await() is ProofCreateResult.Failure)
        assertEquals(fresh, f.store.getAll())
        f.assertFreshNextProofs()
    }

    @Test fun createStartedBetweenCleanupAndKeyPersistenceCannotOverwriteFreshNextProofs() = runTest {
        val f = Fixture()
        f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
        val gate = f.pauseNextEdit()
        val create = async { f.platform().createProof("telegram", "old") }
        gate.entered.await()
        f.identity.npubOverride = f.next.npub
        val fresh = f.saveFreshNextProofs()
        gate.release.complete(Unit)
        assertTrue(create.await() is ProofCreateResult.Failure)
        assertEquals(fresh, f.store.getAll())
        f.assertFreshNextProofs()
    }

    @Test fun replaceRestoreCannotOverwriteFreshNextProofsEvenWhenStartedAfterCleanup() = runTest {
        for (afterCleanup in listOf(false, true)) {
            val f = Fixture()
            if (afterCleanup) f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
            val gate = f.pauseNextEdit()
            val restore = async { f.platform().replaceAllRaw(listOf(f.backup)) }
            gate.entered.await()
            if (afterCleanup) f.identity.npubOverride = f.next.npub else f.switch()
            val fresh = f.saveFreshNextProofs()
            gate.release.complete(Unit)
            assertFalse(restore.await())
            assertEquals(fresh, f.store.getAll())
            f.assertFreshNextProofs()
        }
    }

    @Test fun additiveRestoreCannotOverwriteFreshNextProofsEvenWhenStartedAfterCleanup() = runTest {
        for (afterCleanup in listOf(false, true)) {
            val f = Fixture()
            if (afterCleanup) f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
            val gate = f.pauseNextEdit()
            val restore = async { f.platform().unionRaw(listOf(f.backup)) }
            gate.entered.await()
            if (afterCleanup) f.identity.npubOverride = f.next.npub else f.switch()
            val fresh = f.saveFreshNextProofs()
            gate.release.complete(Unit)
            assertFalse(restore.await())
            assertEquals(fresh, f.store.getAll())
            f.assertFreshNextProofs()
        }
    }

    @Test fun removalCannotDeleteFreshNextProofsEvenWhenStartedAfterCleanup() = runTest {
        for (afterCleanup in listOf(false, true)) {
            val f = Fixture()
            assertTrue(f.platform().createProof("telegram", "old") is ProofCreateResult.Success)
            if (afterCleanup) f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
            val gate = f.pauseNextEdit()
            val remove = async { f.platform().removeProof("telegram") }
            gate.entered.await()
            if (afterCleanup) f.identity.npubOverride = f.next.npub else f.switch()
            val fresh = f.saveFreshNextProofs()
            gate.release.complete(Unit)
            assertFalse(remove.await())
            assertEquals(fresh, f.store.getAll())
            f.assertFreshNextProofs()
        }
    }

    @Test fun humanityRestoreCannotOverwriteFreshNextProofsEvenWhenStartedAfterCleanup() = runTest {
        for (afterCleanup in listOf(false, true)) for (additive in listOf(false, true)) {
            val f = Fixture()
            if (afterCleanup) f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
            val gate = f.pauseNextEdit()
            val restore = async { f.humanity().restoreFromBackup(f.humanityBackup, additive) }
            gate.entered.await()
            if (afterCleanup) f.identity.npubOverride = f.next.npub else f.switch()
            val fresh = f.saveFreshNextProofs()
            gate.release.complete(Unit)
            assertFalse(restore.await())
            assertEquals(fresh, f.store.getAll())
            f.assertFreshNextProofs()
        }
    }

    @Test fun humanityCommitCannotOverwriteFreshNextProofsEvenWhenStartedAfterCleanup() = runTest {
        for (afterCleanup in listOf(false, true)) {
            val f = Fixture()
            f.store.setBool(HumanityKeys.VERIFIED, false)
            if (afterCleanup) f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
            f.relays.add("wss://r1", receiptWithSender(KeyPair.generate(), f.identity.keyPair!!, KeyPair.generate().publicKeyHex))
            val gate = f.pauseNextEdit()
            val search = async { f.humanity().checkForZaps() }
            gate.entered.await()
            if (afterCleanup) f.identity.npubOverride = f.next.npub else f.switch()
            val fresh = f.saveFreshNextProofs()
            gate.release.complete(Unit)
            assertEquals(HumanityOutcome.ERROR, search.await().outcome)
            assertEquals(fresh, f.store.getAll())
            f.assertFreshNextProofs()
        }
    }

    @Test fun destructiveCleanupCannotDeleteFreshNextProofsEvenWhenStartedAfterCleanup() = runTest {
        for (afterCleanup in listOf(false, true)) for (operation in listOf("platform-clear", "humanity-reset", "identity-clear")) {
            val f = Fixture()
            if (afterCleanup) f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
            val gate = f.pauseNextEdit()
            val clear = async {
                when (operation) {
                    "platform-clear" -> assertFalse(f.platform().clear())
                    "humanity-reset" -> f.humanity().reset()
                    else -> assertFalse(f.humanity().clearForIdentitySwitch())
                }
            }
            gate.entered.await()
            if (afterCleanup) f.identity.npubOverride = f.next.npub else f.switch()
            val fresh = f.saveFreshNextProofs()
            gate.release.complete(Unit)
            clear.await()
            assertEquals(fresh, f.store.getAll())
            f.assertFreshNextProofs()
        }
    }

    @Test fun failedCleanupKeepsOldIdentityProofsAndGenerationConsistent() = runTest {
        val f = Fixture()
        assertTrue(f.platform().createProof("telegram", "old") is ProofCreateResult.Success)
        f.store.setBool(HumanityKeys.VERIFIED, true)
        val before = f.store.getAll()
        val previous = f.identity.npub()
        f.backing.failingKeys = setOf(HumanityKeys.VERIFIED)
        assertTrue(runCatching { f.switch() }.isFailure)
        assertEquals(previous, f.identity.npub())
        assertEquals(before, f.store.getAll())
        assertEquals(listOf("old"), f.platform().getSavedProofs().map { it.username })
        assertNotNull(f.humanity().getProofForPublishing())
        f.backing.failingKeys = emptySet()
        assertTrue(f.platform().createProof("nostr", "still-old") is ProofCreateResult.Success)
    }

    @Test fun allTransitionWindowWritersAreRejectedBeforeNextKeyIsActive() = runTest {
        val f = Fixture()
        f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
        val cleared = f.store.getAll()
        assertTrue(f.platform().createProof("telegram", "old") is ProofCreateResult.Failure)
        assertFalse(f.platform().removeProof("telegram"))
        assertFalse(f.platform().replaceAllRaw(listOf(f.backup)))
        assertFalse(f.platform().unionRaw(listOf(f.backup)))
        assertFalse(f.platform().clear())
        assertFalse(f.humanity().restoreFromBackup(f.humanityBackup, additive = false))
        assertFalse(f.humanity().restoreFromBackup(f.humanityBackup, additive = true))
        assertEquals(HumanityOutcome.ERROR, f.humanity().checkForZaps().outcome) // checked_at only
        f.relays.add("wss://r1", receiptWithSender(KeyPair.generate(), f.identity.keyPair!!, KeyPair.generate().publicKeyHex))
        assertEquals(HumanityOutcome.ERROR, f.humanity().checkForZaps().outcome) // found receipt
        f.humanity().reset()
        assertFalse(f.humanity().clearForIdentitySwitch())
        assertEquals(cleared, f.store.getAll())
        f.identity.npubOverride = f.next.npub
        f.assertNoProofs()
        f.saveFreshNextProofs()
    }

    @Test fun laterBadgeSyncCleanupRetainsTheIncomingIdentityFence() = runTest {
        val f = Fixture()
        f.hook.beforeIdentityChange(f.identity.npub()!!, f.next.npub)
        f.store.edit { HumanityKeys.clearIdentityProofs(this); setBool("badge_restore_proofs", true) }
        val cleared = f.store.getAll()
        assertTrue(f.platform().createProof("telegram", "old") is ProofCreateResult.Failure)
        assertFalse(f.humanity().restoreFromBackup(f.humanityBackup, additive = false))
        assertEquals(cleared, f.store.getAll())
        f.identity.npubOverride = f.next.npub
        f.saveFreshNextProofs()
        assertTrue(f.humanity().clearForIdentitySwitch())
        assertEquals(f.next.npub, f.store.getString(ProofOwnership.IDENTITY))
    }

    @Test fun incomingKeyDoesNotAdoptForeignStateLeftByAnOlderRepository() = runTest {
        // Older native versions could persist departing-key-owned proofs after cleanup.
        for (remove in listOf(false, true)) {
            val f = Fixture()
            val departing = f.identity.npub()!!
            f.switch()
            f.store.edit {
                setString(PlatformProof.STORAGE_KEY, DartJson.encode(JsonArray(listOf(f.backup))))
                setString(ProofOwnership.PLATFORM_OWNER, departing)
                setBool(HumanityKeys.VERIFIED, true)
                setString(HumanityKeys.RECEIPT_ID, "foreign")
                setString(ProofOwnership.HUMANITY_OWNER, departing)
            }
            f.assertNoProofs()
            if (remove) {
                assertTrue(f.platform().removeProof("nostr"))
                assertTrue(f.platform().getSavedProofs().isEmpty())
            } else {
                val incoming = PlatformProof("nostr", "incoming", "cd".repeat(64), 2L).toStorageJson()
                assertTrue(f.platform().unionRaw(listOf(incoming)))
                assertEquals(listOf("incoming"), f.platform().getSavedProofs().map { it.username })
            }
            assertEquals(HumanityOutcome.NOT_FOUND, f.humanity().checkForZaps().outcome)
            assertFalse(f.humanity().getStatus().verified)
            assertEquals("", f.humanity().getStatus().receiptEventId)
            assertTrue(f.humanity().restoreFromBackup(f.humanityBackup, additive = true))
            assertTrue(f.humanity().getStatus().needsReverification)
        }
    }

    @Test fun foreignOwnerDoesNotPreventTheIncomingKeyCreatingItsOwnProof() = runTest {
        val f = Fixture()
        val departing = f.identity.npub()!!
        f.switch()
        f.store.edit {
            setString(PlatformProof.STORAGE_KEY, DartJson.encode(JsonArray(listOf(f.backup))))
            setString(ProofOwnership.PLATFORM_OWNER, departing)
        }
        assertTrue(f.platform(FakeIdentity(f.next)).createProof("nostr", "incoming") is ProofCreateResult.Success)
        assertEquals(listOf("incoming"), f.platform().getSavedProofs().map { it.username })
    }

    @Test fun logoutFencesTheDepartingKeyAndFirstKeyAfterLogoutCanWrite() = runTest {
        val f = Fixture()
        f.hook.beforeIdentityChange(f.identity.npub()!!, null)
        val cleared = f.store.getAll()
        assertEquals("", f.store.getString(ProofOwnership.IDENTITY))
        assertTrue(f.platform().createProof("telegram", "departing") is ProofCreateResult.Failure)
        assertFalse(f.humanity().restoreFromBackup(f.humanityBackup, additive = false))
        assertEquals(cleared, f.store.getAll())
        val noKey = FakeIdentity(keyPair = null)
        assertTrue(f.platform(noKey).replaceAllRaw(listOf(f.backup)))
        f.hook.beforeIdentityChange("", f.next.npub)
        f.identity.npubOverride = f.next.npub
        assertEquals(listOf("backup"), f.platform().getSavedProofs().map { it.username })
        f.saveFreshNextProofs()
    }

    @Test fun signerReadyRefreshesActiveProofUiWhenOnlyTheSecureKeyChanged() = runTest {
        val f = Fixture()
        assertTrue(f.platform().createProof("telegram", "old") is ProofCreateResult.Success)
        f.relays.add("wss://r1", receiptWithSender(KeyPair.generate(), f.identity.keyPair!!, KeyPair.generate().publicKeyHex))
        assertTrue(f.humanity().checkForZaps().found)
        var platformCount = -1
        var verified = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.platform().proofs.collect { platformCount = it.size }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.humanity().status.collect { verified = it.verified }
        }
        testScheduler.runCurrent()
        assertEquals(1, platformCount)
        assertTrue(verified)
        f.identity.npubOverride = f.next.npub
        f.identity.signerReadyEvents.emit(Unit)
        testScheduler.runCurrent()
        assertEquals(0, platformCount)
        assertFalse(verified)
    }

    @Test fun legacyMigratedProofsAndExplicitBackupKeepTheirOriginalWireFormat() = runTest {
        val f = Fixture()
        val legacy = DartJson.encode(JsonArray(listOf(f.backup)))
        f.store.setString(PlatformProof.STORAGE_KEY, legacy)
        f.store.setBool(HumanityKeys.VERIFIED, true)
        assertEquals(listOf(f.backup), f.platform().getSavedProofs().map { it.toStorageJson() })
        assertNotNull(f.humanity().getProofForPublishing())
        f.switch()
        f.assertNoProofs()
        assertTrue(f.platform().replaceAllRaw(listOf(f.backup)))
        assertEquals(legacy, f.store.getString(PlatformProof.STORAGE_KEY))
        assertEquals(listOf(f.backup), f.platform().getProofsForPublishing().values.map { it.toStorageJson() })
        assertTrue(f.humanity().restoreFromBackup(f.humanityBackup, additive = false))
        assertTrue(f.humanity().getStatus().needsReverification)
        assertNull(f.humanity().getProofForPublishing())
    }
}
