package space.einundzwanzig.meetup.data.trust

import java.time.ZoneId

/**
 * The device time zone, injectable for deterministic tests. Dart reads the local zone each
 * time a local `DateTime` is broken down, so implementations should not cache it.
 */
fun interface ZoneIdProvider {
    /** The zone to use right now. */
    fun zone(): ZoneId

    companion object {
        /** The JVM default zone, read on every call. */
        val SYSTEM: ZoneIdProvider = ZoneIdProvider { ZoneId.systemDefault() }
    }
}
