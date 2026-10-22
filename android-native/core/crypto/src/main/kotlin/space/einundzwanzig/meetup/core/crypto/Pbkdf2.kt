package space.einundzwanzig.meetup.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

/**
 * PBKDF2-HMAC-SHA256, byte-identical to `lib/services/pbkdf2/pbkdf2_dart.dart`.
 *
 * Takes raw password bytes (Dart passes `utf8.encode(password)`); `SecretKeyFactory` is avoided
 * on purpose because its char→byte conversion is provider specific. The backup format uses
 * [BACKUP_ITERATIONS] — several seconds on low-end devices: never call on the main thread.
 */
object Pbkdf2 {
    /** `BackupService._pbkdf2Iterations` (enc_v2). */
    const val BACKUP_ITERATIONS = 600_000

    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(iterations >= 1) { "iterations muss >= 1 sein" }
        require(keyBytes >= 1) { "keyBytes muss >= 1 sein" }
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password, salt, iterations)
        return (generator.generateDerivedParameters(keyBytes * 8) as KeyParameter).key
    }

    /** `pbkdf2DartFromPassword`: the password is UTF-8 encoded like Dart's `utf8.encode`. */
    fun hmacSha256(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray =
        hmacSha256(DartUtf8.encode(password), salt, iterations, keyBytes)
}
