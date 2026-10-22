package space.einundzwanzig.meetup.core.common

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Injectable wall clock so time-dependent rules (badge windows, rolling QR) are testable. */
interface TimeSource {
    /** Milliseconds since the Unix epoch. */
    fun nowMillis(): Long

    /** Whole seconds since the Unix epoch. */
    fun nowSeconds(): Long = nowMillis() / 1000

    /**
     * Microseconds since the Unix epoch, the resolution of Dart's `DateTime.now()` on the VM.
     * Defaults to [nowMillis] × 1000 for fakes that only know milliseconds.
     */
    fun nowMicros(): Long = Math.multiplyExact(nowMillis(), 1000L)
}

/** The system wall clock. */
@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()

    /** Microseconds from [Instant.now] (as fine as the platform clock provides). */
    override fun nowMicros(): Long =
        Instant.now().let { Math.addExact(Math.multiplyExact(it.epochSecond, 1_000_000L), it.nano / 1000L) }
}
