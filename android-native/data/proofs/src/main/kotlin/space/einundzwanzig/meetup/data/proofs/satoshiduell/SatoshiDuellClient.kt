package space.einundzwanzig.meetup.data.proofs.satoshiduell

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Open SatoshiDuell games of the user (Dart `DuellStatus`).
 *
 * @property myTurn games where the user has to play (incl. challenges targeted at the user).
 * @property waiting games where the opponent is due (or the user's own open game).
 * @property lobby foreign open games the user could join (a challenge to the user counts here too).
 */
data class DuellStatus(val myTurn: Int, val waiting: Int, val lobby: Int) {
    /** Any count > 0. */
    val hasAny: Boolean get() = myTurn > 0 || waiting > 0 || lobby > 0

    /** Number on the home tile / hub chip: `myTurn + lobby`. */
    val actionCount: Int get() = myTurn + lobby

    companion object {
        /** Nothing to show (also on every error without a cached value). */
        val EMPTY: DuellStatus = DuellStatus(0, 0, 0)
    }
}

/**
 * Read-only client of the SatoshiDuell Supabase REST API (Dart `SatoshiDuellService`, spec 06
 * §5.3, §8.7) for the home tile and the community-hub card. Never surfaces errors.
 */
interface SatoshiDuellClient {

    /**
     * Three sequential PostgREST requests (profile → my duels → open lobby), cached in memory for
     * [CACHE_TTL] unless [forceRefresh]. No npub or no SatoshiDuell account → [DuellStatus.EMPTY]
     * (not cached). Any error → the last cached status, else [DuellStatus.EMPTY].
     */
    suspend fun fetchStatus(forceRefresh: Boolean = false): DuellStatus

    /** Dart `openDuelCount()`: `myTurn + lobby` of [fetchStatus]. */
    suspend fun openDuelCount(forceRefresh: Boolean = false): Int

    /** Web app link for the external browser: `https://satoshiduell.de/?npub=<npub>` or `https://satoshiduell.de/`. */
    fun webAppUrl(npub: String?): String =
        if (npub.isNullOrEmpty()) "$WEB_APP_URL/" else "$WEB_APP_URL/?npub=$npub"

    companion object {
        /** PostgREST base URL. */
        const val BASE_URL: String = "https://uydjemquyogdemjtxyyv.supabase.co/rest/v1"

        /**
         * Default of the public Supabase "publishable" client key (the one the web app ships,
         * limited by RLS; not a secret). A build overrides it without a code change via the Gradle
         * property `satoshiduellPublishableKey` (BuildConfig field `SATOSHIDUELL_PUBLISHABLE_KEY`,
         * spec 06 §14 R16). A rejected key (401) behaves like any other failure.
         */
        const val PUBLISHABLE_KEY: String = "sb_publishable_RESZrjWqb-l_AIiqZcE7-g_JenFWO_d"

        /** The web app. */
        const val WEB_APP_URL: String = "https://satoshiduell.de"

        /** Per-request timeout. */
        val REQUEST_TIMEOUT: Duration = 8.seconds

        /** In-memory cache lifetime. */
        val CACHE_TTL: Duration = 2.minutes
    }
}
