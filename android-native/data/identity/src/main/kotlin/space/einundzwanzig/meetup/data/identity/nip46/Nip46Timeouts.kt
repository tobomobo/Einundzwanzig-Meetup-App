package space.einundzwanzig.meetup.data.identity.nip46

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Deadlines of the NIP-46 client (spec 01 §6.2). Long, because a human confirms in another app.
 *
 * @property connect `connect` and `get_public_key` (75 s).
 * @property pairing waiting for the `nostrconnect://` secret echo (120 s).
 * @property sign `sign_event`, `nip44_encrypt`, `nip44_decrypt` (60 s).
 * @property ping `ping` (15 s).
 * @property relayOpen per-relay WebSocket handshake (10 s).
 * @property relayCooldown pause for a relay that answered `blocked:`/`banned:`/`rate-limited:` (2 min).
 */
data class Nip46Timeouts(
    val connect: Duration = 75.seconds,
    val pairing: Duration = 120.seconds,
    val sign: Duration = 60.seconds,
    val ping: Duration = 15.seconds,
    val relayOpen: Duration = 10.seconds,
    val relayCooldown: Duration = 2.minutes,
) {
    companion object {
        /** Dart `debugTimeout`: one short limit for every RPC and the pairing (relay open stays 10 s). */
        fun uniform(limit: Duration): Nip46Timeouts =
            Nip46Timeouts(connect = limit, pairing = limit, sign = limit, ping = limit)
    }
}
