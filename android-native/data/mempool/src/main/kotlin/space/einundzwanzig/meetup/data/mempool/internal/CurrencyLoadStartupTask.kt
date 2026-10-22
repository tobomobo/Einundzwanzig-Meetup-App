package space.einundzwanzig.meetup.data.mempool.internal

import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import javax.inject.Inject

/** Loads the display currency at start (Dart `CurrencyService.load()` in `main`). */
internal class CurrencyLoadStartupTask @Inject constructor(
    private val currency: CurrencyRepository,
) : AppStartupTask {
    override val name: String = "CurrencyLoad"

    /** Early: screens read the currency on their first frame. */
    override val order: Int = 0

    override suspend fun run() = currency.load()
}
