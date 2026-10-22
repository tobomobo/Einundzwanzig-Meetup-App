package space.einundzwanzig.meetup.core.nostr.relay

import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.crypto.toHex

/**
 * Unguessable subscription ids, as the Dart services build them:
 * `'<prefix>-' + 8 CSPRNG bytes as zero-padded lowercase hex` (e.g. `names-0a1b2c3d4e5f6071`).
 */
object SubscriptionIds {

    /** Random bytes per id (16 hex characters). */
    const val RANDOM_BYTES: Int = 8

    /** 16 lowercase hex characters from the CSPRNG. */
    fun randomHex(bytes: Int = RANDOM_BYTES): String = CryptoRandom.bytes(bytes).toHex()

    /** `"$prefix-<16 hex>"`. */
    fun secure(prefix: String): String = "$prefix-${randomHex()}"
}
