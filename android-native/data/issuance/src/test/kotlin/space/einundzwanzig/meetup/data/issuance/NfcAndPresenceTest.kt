package space.einundzwanzig.meetup.data.issuance

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.data.issuance.nfc.IssuanceFeatures
import space.einundzwanzig.meetup.data.issuance.nfc.NfcBadgePayload
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.nfc.NfcWriteError
import space.einundzwanzig.meetup.data.issuance.scan.PresenceCheck
import space.einundzwanzig.meetup.data.issuance.scan.PresenceResult
import space.einundzwanzig.meetup.data.issuance.scan.ReferenceSource

/** NFC NDEF text record wire format (spec 02 §2.1, §2.4) and the presence check (§2.1 steps 4–6, §8.6). */
class NfcAndPresenceTest {

    private val baseJson = Fixtures.str(Fixtures.obj("compactBadge"), "base_payload_json")

    @Test
    fun `nfc stays disabled like kNfcEnabled`() {
        assertEquals(false, IssuanceFeatures.NFC_ENABLED)
    }

    @Test
    fun `tag json is the base payload byte for byte`() {
        val base = DartJson.decode(baseJson) as JsonObject
        assertEquals(baseJson, NfcBadgePayload.tagJson(base))
    }

    @Test
    fun `text record payload is 02 e n plus utf8 json and round trips`() {
        val payload = NfcBadgePayload.encodeTextPayload(baseJson)
        assertArrayEquals(byteArrayOf(0x02, 0x65, 0x6E), payload.copyOfRange(0, 3))
        assertArrayEquals(DartUtf8.encode(baseJson), payload.copyOfRange(3, payload.size))
        val decoded = NfcBadgePayload.decodeTextPayload(payload)
        assertEquals(NfcBadgePayload.Result.Text(baseJson), decoded)
        assertEquals(0x54.toByte(), NfcBadgePayload.RECORD_TYPE_TEXT)
    }

    @Test
    fun `decoder honours the language length and ignores the utf16 flag`() {
        // Status byte 0x85: UTF-16 flag set (ignored by Dart), language length 5.
        val payload = byteArrayOf(0x85.toByte()) + "de-AT".toByteArray() + DartUtf8.encode("{\"t\":\"B\"}")
        assertEquals(NfcBadgePayload.Result.Text("{\"t\":\"B\"}"), NfcBadgePayload.decodeTextPayload(payload))
    }

    @Test
    fun `decoder errors`() {
        assertEquals(
            NfcBadgePayload.Result.Error(NfcPayloadError.PayloadEmpty),
            NfcBadgePayload.decodeTextPayload(ByteArray(0)),
        )
        // Payload ends right after the language code: payload.length <= 1 + langLen.
        assertEquals(
            NfcBadgePayload.Result.Error(NfcPayloadError.InvalidFormat),
            NfcBadgePayload.decodeTextPayload(byteArrayOf(0x02, 0x65, 0x6E)),
        )
        assertEquals(
            NfcBadgePayload.Result.Error(NfcPayloadError.InvalidFormat),
            NfcBadgePayload.decodeTextPayload(byteArrayOf(0x05, 0x65)),
        )
        val invalidUtf8 = NfcBadgePayload.decodeTextPayload(byteArrayOf(0x02, 0x65, 0x6E, 0xC3.toByte(), 0x28))
        assertTrue(invalidUtf8 is NfcBadgePayload.Result.Error && invalidUtf8.error is NfcPayloadError.ReadError)
    }

    @Test
    fun `writer sizes like Dart`() {
        assertEquals(baseJson.length + 7, NfcBadgePayload.actualSize(baseJson))
        assertTrue(NfcBadgePayload.isTooLarge(baseJson, 137)) // NTAG213
        assertEquals(false, NfcBadgePayload.isTooLarge(baseJson, 496)) // NTAG215
        val m = "bad-neuenahr-de"
        val estimated = "{\"v\":2,\"t\":\"B\",\"m\":\"$m\",\"b\":000000,\"x\":0000000000,\"c\":0000000000," +
            "\"p\":\"${"0".repeat(64)}\",\"s\":\"${"0".repeat(128)}\"}"
        assertEquals(estimated.length + 10, NfcBadgePayload.estimatedSize("Bad Neuenahr", "DE"))
    }

    @Test
    fun `writer error mapping`() {
        assertEquals(NfcWriteError.TAG_REMOVED_EARLY, NfcBadgePayload.classifyWriteError("java.io.IOException: closed"))
        assertEquals(NfcWriteError.TAG_LOST, NfcBadgePayload.classifyWriteError("android.nfc.TagLostException"))
        assertEquals(NfcWriteError.OTHER, NfcBadgePayload.classifyWriteError("boom"))
    }

    @Test
    fun `reference order signed, scanner meetup, directory`() = runTest {
        var lookups = 0
        val dir: suspend () -> space.einundzwanzig.meetup.data.meetups.model.Meetup? = {
            lookups++
            meetup("1", "Wien", "AT", 48.2, 16.37)
        }
        val signed = PresenceCheck.reference(1.0, 2.0, 3.0, 4.0, dir)!!
        assertEquals(ReferenceSource.SIGNED, signed.source)
        assertEquals(PresenceCheck.PARTICIPANT_RADIUS_KM, signed.allowedKm, 0.0)
        val scanner = PresenceCheck.reference(0.0, 0.0, 3.0, 4.0, dir)!!
        assertEquals(ReferenceSource.OPENED_FOR_MEETUP, scanner.source)
        assertEquals(PresenceCheck.DERIVED_RADIUS_KM, scanner.allowedKm, 0.0)
        assertEquals(0, lookups)
        val directory = PresenceCheck.reference(0.0, 0.0, 0.0, 0.0, dir)!!
        assertEquals(ReferenceSource.DIRECTORY, directory.source)
        assertEquals(1, lookups)
        assertNull(PresenceCheck.reference(0.0, 0.0, 0.0, 0.0) { meetup("2", "Nowhere") })
        assertNull(PresenceCheck.reference(0.0, 0.0, 0.0, 0.0) { null })
    }

    @Test
    fun `directory lookup matches the city before the comma both ways and skips missing coordinates`() {
        val noCoords = meetup("1", "Wien", "AT")
        val wien = meetup("2", "Wien", "AT", 48.2, 16.37)
        val badNeuenahr = meetup("3", "Bad Neuenahr-Ahrweiler", "DE", 50.54, 7.11)
        val list = listOf(noCoords, wien, badNeuenahr)
        assertSame(wien, PresenceCheck.findReferenceMeetup("Wien, AT", list))
        assertSame(wien, PresenceCheck.findReferenceMeetup("  WIEN  ", list))
        // city contained in the directory city
        assertSame(badNeuenahr, PresenceCheck.findReferenceMeetup("Bad Neuenahr, DE", list))
        // directory city contained in the badge city
        assertSame(wien, PresenceCheck.findReferenceMeetup("Wien Innere Stadt", list))
        assertNull(PresenceCheck.findReferenceMeetup(", DE", list))
        assertNull(PresenceCheck.findReferenceMeetup("Graz", list))
    }

    @Test
    fun `evaluate uses geolocator haversine and a strict greater-than`() {
        val ref = space.einundzwanzig.meetup.data.issuance.scan.PresenceReference(48.2, 16.37, ReferenceSource.DIRECTORY)
        assertEquals(
            PresenceResult.Unverified(PresenceResult.Unverified.Reason.NO_LOCATION),
            PresenceCheck.evaluate(false, 48.2, 16.37, ref),
        )
        assertEquals(
            PresenceResult.Unverified(PresenceResult.Unverified.Reason.NO_REFERENCE),
            PresenceCheck.evaluate(true, 48.2, 16.37, null),
        )
        val same = PresenceCheck.evaluate(true, 48.2, 16.37, ref)
        assertEquals(PresenceResult.Verified(0.0, 50.0), same)
        // ~0.4° latitude ≈ 44.5 km (R = 6378137 m) → inside 50 km.
        val near = PresenceCheck.evaluate(true, 48.6, 16.37, ref) as PresenceResult.Verified
        assertEquals(6378.137 * Math.toRadians(0.4), near.distanceKm, 1e-6)
        val far = PresenceCheck.evaluate(true, 48.7, 16.37, ref)
        assertTrue(far is PresenceResult.TooFar && far.allowedKm == 50.0)
    }
}
