package space.einundzwanzig.meetup.data.trust

import java.time.ZoneId

/**
 * The device time zone, injectable for deterministic tests. Dart reads the local zone each
 * time a local `DateTime` is broken down, so implementations should not cache it.
 *
 * Scope: the trust calculator uses it for the week-cap anchor, the co-attestor key and the
 * sybil day buckets. It does NOT reach `BadgeIdentity.identityKey` (`:data:badges`), whose
 * de-duplication fallback for local-dated badges reads `ZoneId.systemDefault()`. A test that
 * injects a zone must therefore also pin the JVM default zone to the same value
 * (`TimeZone.setDefault`), otherwise de-duplication and week cap use different calendars
 * (spec 04 R-11: tests must pin the default time zone). In production both are the system zone.
 *
 * Binding: `TrustModule` (`:data:trust`) is the only Hilt provider, bound to [SYSTEM]. Other
 * modules inject it by depending on `:data:trust` and must not add a second `@Provides`
 * (Hilt duplicate binding). Planned move: `:core:common` next to `TimeSource`, with a typealias here.
 */
fun interface ZoneIdProvider {
    /** The zone to use right now. */
    fun zone(): ZoneId

    companion object {
        /** The JVM default zone, read on every call. */
        val SYSTEM: ZoneIdProvider = ZoneIdProvider { ZoneId.systemDefault() }
    }
}
