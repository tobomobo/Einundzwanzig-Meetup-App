package space.einundzwanzig.meetup.feature.reputation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05DomainType
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.trust.layers.Nip05DomainKind
import space.einundzwanzig.meetup.data.trust.layers.TrustLayersEvaluator
import space.einundzwanzig.meetup.data.trust.layers.TrustSignalKind
import space.einundzwanzig.meetup.feature.reputation.common.LayerLoad
import space.einundzwanzig.meetup.feature.reputation.common.LiveLayers
import space.einundzwanzig.meetup.feature.reputation.common.qrVerifyMessageRes
import space.einundzwanzig.meetup.feature.reputation.common.shortenNpub
import space.einundzwanzig.meetup.feature.reputation.common.shortenNpubVerify
import space.einundzwanzig.meetup.feature.reputation.person.NpubExtractor
import space.einundzwanzig.meetup.feature.reputation.scan.GalleryOutcome
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanLogic
import space.einundzwanzig.meetup.feature.reputation.scan.ScanHit
import space.einundzwanzig.meetup.feature.reputation.verify.LayersMapping
import java.util.Base64

/** Spec 04 §10.4 `NpubExtractTest`. */
class NpubExtractorTest {
    private val npub = Vectors.signerNpub

    @Test
    fun `raw npub and nostr prefix are lower-cased`() {
        assertEquals(npub, NpubExtractor.extract(npub))
        assertEquals(npub, NpubExtractor.extract("  nostr:${npub.uppercase()}  "))
        assertEquals(npub, NpubExtractor.extract("NOSTR:$npub"))
    }

    @Test
    fun `short npub is rejected`() {
        assertNull(NpubExtractor.extract("npub1" + "q".repeat(45))) // 50 chars
        assertEquals("npub1" + "q".repeat(46), NpubExtractor.extract("npub1" + "q".repeat(46)))
    }

    @Test
    fun `JSON with id np is accepted, other JSON and text are not`() {
        assertEquals(npub, NpubExtractor.extract("""{"id":{"np":"${npub.uppercase()}"}}"""))
        assertNull(NpubExtractor.extract("""{"id":{"np":"nsec1abc"}}"""))
        assertNull(NpubExtractor.extract("""{"id":"npub1x"}"""))
        assertNull(NpubExtractor.extract("""[1,2]"""))
        assertNull(NpubExtractor.extract("hello"))
    }

    @Test
    fun `21v3 reputation QR yields the verified signer (R-21 fix)`() {
        assertEquals(npub, NpubExtractor.extract(Vectors.qrString))
        assertNull(NpubExtractor.extract(Vectors.tamperedQrString))
    }

    @Test
    fun `legacy codes read id np from the base64 payload`() {
        assertEquals(npub, NpubExtractor.extract(Vectors.unsignedQr))
        val b64 = Base64.getEncoder().encodeToString("""{"id":{"np":"$npub"}}""".toByteArray())
        assertEquals(npub, NpubExtractor.extract("21v2:$b64.sig.pub"))
        assertNull(NpubExtractor.extract("21v2:###.a"))
    }
}

class ReputationScanLogicTest {
    @Test
    fun `camera classification`() {
        assertEquals(ScanHit.Demo, ReputationScanLogic.classify(" 21review:einundzwanzig-meetup-demo "))
        assertEquals(ScanHit.Reputation("21v3:x"), ReputationScanLogic.classify("21v3:x"))
        assertEquals(ScanHit.Reputation("21v2:x"), ReputationScanLogic.classify("21v2:x"))
        assertEquals(ScanHit.Reputation("21:x"), ReputationScanLogic.classify("21:x"))
        assertEquals(ScanHit.Ignore, ReputationScanLogic.classify("""{"t":"badge"}"""))
        assertEquals(ScanHit.Ignore, ReputationScanLogic.classify("21x:abc"))
    }

    @Test
    fun `gallery outcomes`() {
        assertEquals(GalleryOutcome.NoCode, ReputationScanLogic.classifyGallery(emptyList()))
        assertEquals(GalleryOutcome.NotEinundzwanzig, ReputationScanLogic.classifyGallery(listOf("https://x")))
        assertEquals(
            GalleryOutcome.Hit(ScanHit.Reputation("21v3:a")),
            ReputationScanLogic.classifyGallery(listOf("other", "21v3:a", "21:b")),
        )
        assertEquals(
            GalleryOutcome.Hit(ScanHit.Demo),
            ReputationScanLogic.classifyGallery(listOf("21review:einundzwanzig-meetup-demo", "21v3:a")),
        )
    }
}

class LayersMappingTest {
    @Test
    fun `maps event counts, platform usernames and live inputs`() {
        val rep = reputationEvent(total = 8, bound = 7).copy(
            humanityVerified = true,
            platformProofs = mapOf(
                "telegram" to buildJsonObject { put("username", "hal") },
                "robosats" to buildJsonObject { put("proof_sig", "x") },
            ),
        )
        val input = LayersMapping.input(
            rep,
            ZapStats(12, 5, 4, 3, 6, true),
            SocialAnalysis("h", 1, 1, iFollow = true, followsMe = true, isMutual = true, commonContactCount = 8, orgFollowerCount = 2, hops = 1),
            Nip05Result(true, "hal@einundzwanzig.space", "einundzwanzig.space", Nip05DomainType.COMMUNITY),
        )
        assertEquals(8L, input.badgeCount)
        assertEquals(7L, input.boundBadges)
        assertEquals(2L, input.platformProofCount)
        assertEquals(mapOf("telegram" to "hal", "robosats" to ""), input.platformProofs)
        assertTrue(input.humanityVerified)
        assertEquals(Nip05DomainKind.COMMUNITY, input.nip05!!.domainKind)
        assertEquals(17L, input.zaps!!.totalCount)
        assertEquals(4, TrustLayersEvaluator.activeLayerCount(input))
    }

    @Test
    fun `spec L1 vector - badges only is BUILDING`() {
        val rep = reputationEvent(total = 8, bound = 7).copy(meetupCount = 4, signerCount = 3, accountAgeDays = 120)
        val input = LayersMapping.input(rep, null, null, null)
        assertEquals(TrustSignalKind.BUILDING, TrustLayersEvaluator.signal(input.copy(platformProofs = null, platformProofCount = null)))
    }

    @Test
    fun `without reputation every count is null`() {
        val input = LayersMapping.input(null, null, null, null)
        assertNull(input.badgeCount)
        assertNull(input.platformProofs)
        assertFalse(input.humanityVerified)
    }
}

class PresentationTest {
    @Test
    fun `qr verify messages map to native strings`() {
        assertEquals(R.string.native_qrVerifyOk, qrVerifyMessageRes("Schnorr-Signatur verifiziert ✓"))
        assertEquals(R.string.native_qrVerifyInvalid, qrVerifyMessageRes("Fehler: Invalid event"))
        assertEquals(R.string.native_qrVerifyMissing, qrVerifyMessageRes("Fehlende Signatur-Daten"))
        assertNull(qrVerifyMessageRes("something else"))
    }

    @Test
    fun `npub shortening`() {
        val n = Vectors.signerNpub
        assertEquals(n.take(12) + "..." + n.takeLast(8), shortenNpub(n))
        assertEquals("npub1short", shortenNpub("npub1short"))
        assertEquals("npub1abc", shortenNpubVerify("npub1abc"))
        assertEquals(n.take(12) + "..." + n.takeLast(8), shortenNpubVerify(n))
    }

    @Test
    fun `live layers report the first pending loader`() {
        assertFalse(LiveLayers().isLoading)
        val l = LiveLayers(pending = setOf(LayerLoad.NIP05, LayerLoad.LIGHTNING))
        assertTrue(l.isLoading)
        assertEquals(LayerLoad.LIGHTNING, l.currentLoad)
    }
}
