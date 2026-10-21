package space.einundzwanzig.meetup.core.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * Lazily loaded libsecp256k1 (JNI).
 *
 * On a device the Android AAR provides the library; in JVM unit tests its loader falls back to
 * `secp256k1-kmp-jni-jvm`, which therefore must be a `testImplementation` of every module whose
 * JVM tests reach this code.
 */
internal object Secp256k1Native {
    val lib: Secp256k1 by lazy { Secp256k1.get() }
}
