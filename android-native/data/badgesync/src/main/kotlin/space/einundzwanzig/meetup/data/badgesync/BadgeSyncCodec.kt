package space.einundzwanzig.meetup.data.badgesync

import space.einundzwanzig.meetup.core.crypto.DartUtf8
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * gzip facade of the badge sync (Dart `BadgeSyncCodec`, `badge_sync_codec_io.dart`).
 *
 * The NIP-44 plaintext of a sync chunk is `base64(deflate(envelopeJson))`. Both directions use
 * standard RFC 1952 gzip, so chunks written by the Flutter app (dart:io or the web `archive`
 * encoder) decode here and vice versa. The exact gzip bytes (header mtime/OS byte, compression
 * level) differ between implementations; only decodability matters.
 */
object BadgeSyncCodec {

    /** Dart `deflate(text)` = `gzip.encode(utf8.encode(text))`. */
    fun deflate(plaintext: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(DartUtf8.encode(plaintext)) }
        return out.toByteArray()
    }

    /**
     * Dart `inflate(data)` = `utf8.decode(gzip.decode(data))` (strict UTF-8).
     *
     * @throws java.io.IOException for data that is not gzip (or truncated), and
     *   [java.nio.charset.CharacterCodingException] (also an `IOException`) for bytes that are
     *   not valid UTF-8, and also an `IOException` if the decompressed output exceeds
     *   [MAX_INFLATED_BYTES] (gzip bomb; native guard, Dart had no limit).
     */
    fun inflate(data: ByteArray): String {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_INFLATED_BYTES) throw IOException("gzip output too large")
                out.write(buffer, 0, n)
            }
        }
        return DartUtf8.decodeStrict(out.toByteArray())
    }

    /**
     * Upper bound for one decompressed chunk (8 MiB). A legitimate chunk is at most
     * [BadgeSyncFormat.CHUNK_PAYLOAD_CHARS] of NIP-44 payload, far below this.
     */
    const val MAX_INFLATED_BYTES: Int = 8 * 1024 * 1024
}
