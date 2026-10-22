package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of the bech32 part of test/zap_receipt_validator_test.dart (`descriptionHashOf`). */
class Bolt11Test {
    private fun bolt11WithHash(hashHex: String): String {
        val hWords = Bech32.convertBits(hashHex.hexToBytes().toUnsignedInts(), 8, 5, pad = true)
        val data = IntArray(7) { 1 } + intArrayOf(23, (hWords.size shr 5) and 31, hWords.size and 31) + hWords + IntArray(104)
        return Bech32.encode("lnbc210n", data, 4096)
    }

    @Test
    fun readsTheDescriptionHash() {
        val h = "ab".repeat(32)
        val invoice = bolt11WithHash(h)
        // Same string as the Dart test helper produces (Bech32Encoder, max 4096).
        assertEquals(
            "lnbc210n1ppppppphp54w46h2at4w46h2at4w46h2at4w46h2at4w46h2at4w46h2at4w4sqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
                "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqah5xns",
            invoice,
        )
        assertEquals(h, Bolt11.descriptionHashHex(invoice))
        assertEquals(Sha256.hexOfUtf8("zap"), Bolt11.descriptionHashHex(bolt11WithHash(Sha256.hexOfUtf8("zap"))))
        assertEquals(null, Bolt11.descriptionHashHex("lnbc1kaputt"))
        assertEquals(null, Bolt11.descriptionHashHex(Nip19.encodeNpub("ab".repeat(32))))
    }
}
