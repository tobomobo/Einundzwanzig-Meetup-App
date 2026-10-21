package space.einundzwanzig.meetup.data.mempool.currency

import kotlinx.coroutines.flow.StateFlow

/**
 * App-wide display currency (Dart `CurrencyService`, Issue #66; key `fiat_currency`).
 * Applies to the converter, both home tiles, the dashboard price row and the home-screen widget.
 */
interface CurrencyRepository {

    /**
     * Current code (default `EUR`). Follows the store (migration, app reset) and changes
     * immediately on [set].
     */
    val current: StateFlow<String>

    /** Loads the stored code into [current]; call early at start so no `€` flashes. */
    suspend fun load()

    /** The stored code if supported, else `EUR` (also for widget/background paths). */
    suspend fun read(): String

    /**
     * Sets and persists [code]; unsupported codes are ignored. Then pushes the last dashboard
     * (if any) to the home-screen widget, fire and forget.
     */
    suspend fun set(code: String)

    /** App reset: back to `EUR`, key removed. */
    suspend fun reset()
}
