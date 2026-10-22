package space.einundzwanzig.meetup.data.identity.internal

import space.einundzwanzig.meetup.core.crypto.Nip49Exception
import space.einundzwanzig.meetup.data.identity.IdentityError
import space.einundzwanzig.meetup.data.identity.KeyOperationException

/** A single boundary for the 64 MiB scrypt allocation, including recovery imports. */
internal inline fun <T> keyCrypto(block: () -> T): T = try {
    block()
} catch (e: OutOfMemoryError) {
    throw KeyOperationException(IdentityError.MEMORY, e)
} catch (e: Nip49Exception) {
    throw KeyOperationException(IdentityError.ENCRYPTED_KEY, e)
}
