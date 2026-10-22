package space.einundzwanzig.meetup.core.crypto

/** Minimal BOLT-11 field access (`ZapReceiptValidator.descriptionHashOf`). */
object Bolt11 {
    private const val MAX_LENGTH = 4096
    private const val TIMESTAMP_WORDS = 7
    private const val SIGNATURE_WORDS = 104
    private const val TAG_DESCRIPTION_HASH = 23

    /**
     * Description hash (tagged field `h`, type 23, 52 words) as lowercase hex, or null when the
     * invoice is not decodable, not `ln…`, or carries no `h` field. The signature is not checked.
     */
    fun descriptionHashHex(bolt11: String): String? {
        val decoded = runCatching { Bech32.decode(bolt11.trim(), MAX_LENGTH) }.getOrNull() ?: return null
        if (!decoded.hrp.lowercase().startsWith("ln")) return null
        val data = decoded.words
        if (data.size < TIMESTAMP_WORDS + SIGNATURE_WORDS) return null
        var i = TIMESTAMP_WORDS
        val end = data.size - SIGNATURE_WORDS
        while (i + 3 <= end) {
            val type = data[i]
            val len = (data[i + 1] shl 5) or data[i + 2]
            i += 3
            if (i + len > end) return null
            if (type == TAG_DESCRIPTION_HASH && len == 52) {
                val bytes = Bech32.convertBitsUnchecked(data.copyOfRange(i, i + len), 5, 8)
                if (bytes.size < 32) return null
                return ByteArray(32) { bytes[it].toByte() }.toHex()
            }
            i += len
        }
        return null
    }
}
