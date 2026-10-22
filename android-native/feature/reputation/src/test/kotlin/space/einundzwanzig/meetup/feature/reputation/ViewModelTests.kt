package space.einundzwanzig.meetup.feature.reputation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.qr.QrParseResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardAction
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardViewModel
import space.einundzwanzig.meetup.feature.reputation.data.ReputationCardData
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonAction
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonError
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonViewModel
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrAction
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrEvent
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrViewModel
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanResultViewModel
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyAction
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyViewModel

abstract class MainDispatcherTest {
    protected val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()
}

class ReputationQrViewModelTest : MainDispatcherTest() {

    @Test
    fun `loads the content once with the card and stats figures`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = ReputationQrViewModel(source)
        assertTrue(vm.state.value.isLoading)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.isLoading)
        assertTrue(s.hasIdentity)
        assertEquals(2, s.platformProofCount)
        val c = s.content!!
        assertEquals("Satoshi", c.nickname)
        assertEquals(12.3, c.score, 0.0)
        assertEquals("AKTIV", c.level)
        assertEquals(4, c.verifiedBadgeCount)
        assertEquals(3, c.uniqueMeetups)
        assertEquals(2, c.boundBadgeCount)
        assertEquals(ReputationQr.ProofStatus.PARTLY_BOUND, c.proofStatus)
        assertTrue(c.isSigned)
        assertEquals(1, c.organizerMeetupCount)
    }

    @Test
    fun `empty wallet shows the empty view, unreadable disables the card action`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply {
            qr = { ReputationQr.Empty(UserProfile(), 0) }
            unreadable = true
        }
        val vm = ReputationQrViewModel(source)
        advanceUntilIdle()
        assertTrue(vm.state.value.isEmpty)
        assertTrue(vm.state.value.badgesUnreadable)
        assertFalse(vm.state.value.canOpenCard)
        assertFalse(vm.state.value.hasIdentity)
    }

    @Test
    fun `empty nickname falls back to Anon`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply { qr = { qrContent(user = UserProfile(nickname = "")) } }
        val vm = ReputationQrViewModel(source)
        advanceUntilIdle()
        assertEquals("Anon", vm.state.value.content!!.nickname)
    }

    @Test
    fun `signer failure shows the unsigned QR and emits SignFailed`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply { qr = { qrContent(signError = IllegalStateException("rejected")) } }
        val vm = ReputationQrViewModel(source)
        advanceUntilIdle()
        assertFalse(vm.state.value.content!!.isSigned)
        assertEquals(ReputationQrEvent.SignFailed, vm.events.first())
    }

    @Test
    fun `load failure shows an error and retry loads again`() = runTest(dispatcher) {
        var fail = true
        val source = FakeReputationDataSource().apply {
            qr = { if (fail) error("store") else qrContent() }
        }
        val vm = ReputationQrViewModel(source)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        fail = false
        vm.onAction(ReputationQrAction.Retry)
        advanceUntilIdle()
        assertFalse(vm.state.value.loadFailed)
        assertNotNull(vm.state.value.content)
    }

    @Test
    fun `publish is forced once, shows progress, result and event`() = runTest(dispatcher) {
        val gate = CompletableDeferred<PublishResult>()
        val source = FakeReputationDataSource().apply { publishResult = { gate.await() } }
        val vm = ReputationQrViewModel(source)
        advanceUntilIdle()
        vm.onAction(ReputationQrAction.Publish)
        vm.onAction(ReputationQrAction.Publish)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPublishing)
        assertEquals(1, source.publishCalls)
        gate.complete(PublishResult.NoRelay("e"))
        advanceUntilIdle()
        assertFalse(vm.state.value.isPublishing)
        assertEquals(PublishResult.NoRelay("e"), vm.state.value.lastPublish)
        assertEquals(ReputationQrEvent.Published(PublishResult.NoRelay("e")), vm.events.first())
    }

    @Test
    fun `publish exception becomes an Error result`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply { publishResult = { throw IllegalStateException("boom") } }
        val vm = ReputationQrViewModel(source)
        advanceUntilIdle()
        vm.onAction(ReputationQrAction.Publish)
        advanceUntilIdle()
        assertTrue(vm.state.value.lastPublish is PublishResult.Error)
    }
}

class ReputationCardViewModelTest : MainDispatcherTest() {

    @Test
    fun `display name prefers nickname, then full name, then member label`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        source.card = { ReputationCardData(UserProfile(nickname = "Satoshi", fullName = "S N"), trustScore()) }
        var vm = ReputationCardViewModel(source)
        advanceUntilIdle()
        assertEquals("Satoshi", vm.state.value.info!!.displayName)

        source.card = { ReputationCardData(UserProfile(nickname = "", fullName = "S N"), trustScore()) }
        vm = ReputationCardViewModel(source)
        advanceUntilIdle()
        assertEquals("S N", vm.state.value.info!!.displayName)

        source.card = { ReputationCardData(UserProfile(nickname = "", fullName = ""), trustScore()) }
        vm = ReputationCardViewModel(source)
        advanceUntilIdle()
        assertNull(vm.state.value.info!!.displayName)
    }

    @Test
    fun `figures and no-data variant come from the score`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        source.card = { ReputationCardData(UserProfile(), trustScore(total = 0.0, badges = 0)) }
        val vm = ReputationCardViewModel(source)
        advanceUntilIdle()
        assertTrue(vm.state.value.info!!.hasNoData)

        source.card = { ReputationCardData(UserProfile(), trustScore(total = 7.25, badges = 5, cities = 2, ageDays = 30)) }
        val vm2 = ReputationCardViewModel(source)
        advanceUntilIdle()
        val info = vm2.state.value.info!!
        assertFalse(info.hasNoData)
        assertEquals(5, info.totalBadges)
        assertEquals(2, info.uniqueCities)
        assertEquals(30, info.accountAgeDays)
    }

    @Test
    fun `load failure and retry`() = runTest(dispatcher) {
        var fail = true
        val source = FakeReputationDataSource()
        source.card = { if (fail) error("io") else ReputationCardData(UserProfile(), trustScore()) }
        val vm = ReputationCardViewModel(source)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        fail = false
        vm.onAction(ReputationCardAction.Retry)
        advanceUntilIdle()
        assertNotNull(vm.state.value.info)
    }
}

class ReputationScanResultViewModelTest : MainDispatcherTest() {

    @Test
    fun `verified fixture QR loads the layers for the claimed npub`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = ReputationScanResultViewModel(Vectors.qrString, source)
        val result = vm.state.value.result as QrParseResult.Verified
        assertEquals(Vectors.signerNpub, result.signerNpub)
        assertFalse(result.identityMismatch)
        advanceUntilIdle()
        assertEquals(listOf("h:${Vectors.signerNpub}"), source.layerHexes)
        val layers = vm.state.value.layers
        assertFalse(layers.isLoading)
        assertNotNull(layers.social)
        assertNotNull(layers.zaps)
        assertNotNull(layers.nip05)
    }

    @Test
    fun `layers report loading until every loader finished, errors stay null`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply {
            layerGate = CompletableDeferred()
            social = null
        }
        val vm = ReputationScanResultViewModel(Vectors.qrString, source)
        advanceUntilIdle()
        assertTrue(vm.state.value.layers.isLoading)
        source.layerGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.layers.isLoading)
        assertNull(vm.state.value.layers.social)
        assertNotNull(vm.state.value.layers.zaps)
    }

    @Test
    fun `S-8 code signed by another key loads the layers for the signer, not the claimed npub`() = runTest(dispatcher) {
        val (code, keyB) = mismatchQr()
        val source = FakeReputationDataSource()
        val vm = ReputationScanResultViewModel(code, source)
        val result = vm.state.value.result as QrParseResult.Verified
        assertTrue(result.identityMismatch)
        advanceUntilIdle()
        assertEquals(listOf("h:$keyB"), source.layerHexes)
    }

    @Test
    fun `tampered and legacy codes are invalid and load nothing`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val tampered = ReputationScanResultViewModel(Vectors.tamperedQrString, source)
        val legacy = ReputationScanResultViewModel(Vectors.unsignedQr, source)
        val unknown = ReputationScanResultViewModel("21v3:abc", source)
        advanceUntilIdle()
        assertTrue(tampered.state.value.result is QrParseResult.SignatureInvalid)
        assertTrue(legacy.state.value.result is QrParseResult.SignatureInvalid)
        assertEquals(QrParseResult.UnknownFormat, unknown.state.value.result)
        assertTrue(source.layerHexes.isEmpty())
    }
}

class ReputationVerifyViewModelTest : MainDispatcherTest() {

    @Test
    fun `initial input is verified at once and loads the layers`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = ReputationVerifyViewModel(Vectors.signerNpub, source)
        assertEquals(Vectors.signerNpub, vm.state.value.input)
        advanceUntilIdle()
        assertEquals(listOf(Vectors.signerNpub), source.verifyInputs)
        assertEquals(VerifyLevel.PARTIAL, vm.state.value.result!!.level)
        assertEquals(listOf("h:${Vectors.signerNpub}"), source.layerHexes)
        val input = vm.state.value.layersInput!!
        assertEquals(8L, input.badgeCount)
        assertEquals(true, input.social?.isMutual)
    }

    @Test
    fun `blank input does nothing, input is trimmed`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = ReputationVerifyViewModel(null, source)
        vm.onAction(ReputationVerifyAction.InputChanged("   "))
        vm.onAction(ReputationVerifyAction.Verify)
        advanceUntilIdle()
        assertTrue(source.verifyInputs.isEmpty())
        vm.onAction(ReputationVerifyAction.InputChanged("  ${Vectors.signerNpub} \n"))
        vm.onAction(ReputationVerifyAction.Verify)
        advanceUntilIdle()
        assertEquals(listOf(Vectors.signerNpub), source.verifyInputs)
    }

    @Test
    fun `invalid result loads no layers and shows no widget`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply {
            verify = { ProofVerifyResult(VerifyLevel.INVALID, VerifyMessage.BadFormat) }
        }
        val vm = ReputationVerifyViewModel("garbage", source)
        advanceUntilIdle()
        assertEquals(VerifyLevel.INVALID, vm.state.value.result!!.level)
        assertTrue(source.layerHexes.isEmpty())
        assertNull(vm.state.value.layersInput)
    }

    @Test
    fun `paste during a running verification replaces it with the pasted input`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeReputationDataSource().apply {
            verify = { input ->
                if (input == "slow") gate.await()
                ProofVerifyResult(VerifyLevel.PARTIAL, VerifyMessage.NpubWithBindings, npub = input, reputation = reputationEvent())
            }
        }
        val vm = ReputationVerifyViewModel("slow", source)
        advanceUntilIdle()
        assertTrue(vm.state.value.isVerifying)
        vm.onAction(ReputationVerifyAction.Paste(Vectors.signerNpub))
        advanceUntilIdle()
        assertEquals(listOf("slow", Vectors.signerNpub), source.verifyInputs)
        assertEquals(Vectors.signerNpub, vm.state.value.result!!.npub)
        assertFalse(vm.state.value.isVerifying)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Vectors.signerNpub, vm.state.value.result!!.npub)
    }

    @Test
    fun `paste sets the text and verifies, empty clipboard is ignored`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = ReputationVerifyViewModel(null, source)
        vm.onAction(ReputationVerifyAction.Paste(null))
        vm.onAction(ReputationVerifyAction.Paste(""))
        advanceUntilIdle()
        assertTrue(source.verifyInputs.isEmpty())
        vm.onAction(ReputationVerifyAction.Paste(Vectors.signerNpub))
        advanceUntilIdle()
        assertEquals(Vectors.signerNpub, vm.state.value.input)
        assertEquals(1, source.verifyInputs.size)
    }
}

class VerifyPersonViewModelTest : MainDispatcherTest() {

    @Test
    fun `invalid npub shows the error and does not check`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = VerifyPersonViewModel(null, source)
        vm.onAction(VerifyPersonAction.InputChanged("npub1nope"))
        vm.onAction(VerifyPersonAction.Check)
        advanceUntilIdle()
        assertEquals(VerifyPersonError.INVALID_NPUB, vm.state.value.error)
        assertTrue(source.presenceCalls.isEmpty())
    }

    @Test
    fun `input is trimmed, lower-cased and nostr stripped before the check`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = VerifyPersonViewModel(null, source)
        vm.onAction(VerifyPersonAction.InputChanged("  NOSTR:${OTHER_NPUB.uppercase()} "))
        vm.onAction(VerifyPersonAction.Check)
        advanceUntilIdle()
        assertEquals(listOf(Vectors.signerNpub to OTHER_NPUB), source.presenceCalls)
        assertTrue(vm.state.value.result!!.isDirect)
        assertFalse(vm.state.value.checking)
    }

    @Test
    fun `failure maps to CHECK_FAILED`() = runTest(dispatcher) {
        val source = FakeReputationDataSource().apply { presence = { _, _ -> error("relay") } }
        val vm = VerifyPersonViewModel(OTHER_NPUB, source)
        advanceUntilIdle()
        assertEquals(VerifyPersonError.CHECK_FAILED, vm.state.value.error)
        assertNull(vm.state.value.result)
    }

    @Test
    fun `scanned npub sets the text and checks`() = runTest(dispatcher) {
        val source = FakeReputationDataSource()
        val vm = VerifyPersonViewModel(null, source)
        vm.onAction(VerifyPersonAction.Scanned(OTHER_NPUB))
        advanceUntilIdle()
        assertEquals(OTHER_NPUB, vm.state.value.input)
        assertEquals(1, source.presenceCalls.size)
    }

    @Test
    fun `scan during a pending check gives the result for the scanned npub`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeReputationDataSource().apply {
            presence = { _, target ->
                if (target == Vectors.signerNpub) gate.await()
                PresenceCheck(target, 1, listOf(Vectors.signerNpub, target), emptySet(), true, 0)
            }
        }
        val vm = VerifyPersonViewModel(Vectors.signerNpub, source)
        advanceUntilIdle()
        assertTrue(vm.state.value.checking)
        vm.onAction(VerifyPersonAction.Scanned(OTHER_NPUB))
        advanceUntilIdle()
        assertEquals(OTHER_NPUB, vm.state.value.input)
        assertEquals(OTHER_NPUB, vm.state.value.result!!.targetNpub)
        assertFalse(vm.state.value.checking)
        assertEquals(2, source.presenceCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OTHER_NPUB, vm.state.value.result!!.targetNpub)
    }

    @Test
    fun `normalize replaces the first nostr anywhere (parity)`() {
        assertEquals("abcnpub1x", VerifyPersonViewModel.normalize(" abcNOSTR:npub1x "))
    }
}
