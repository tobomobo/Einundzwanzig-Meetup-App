package space.einundzwanzig.meetup.data.mempool.widget

import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData

/**
 * Feeds dashboard data to every [BitcoinWidgetSink] (Dart `WidgetService.updateBitcoin` /
 * `refreshBitcoin`, spec 13 §8.12). The price uses the **stored** display currency.
 */
interface BitcoinWidgetBridge {

    /**
     * Pushes [d] unless it is dead (no source answered), so a failed refresh never overwrites
     * good widget values. Used by the home tile after every refresh and by currency changes.
     *
     * @return `true` if the sinks were called.
     */
    suspend fun publish(d: BitcoinDashboardData): Boolean

    /**
     * Widget refresh button: fetches the dashboard and pushes it unless dead (then it only
     * logs `Bitcoin-Widget nicht aktualisiert — ...`). Never throws.
     *
     * @return `true` if the sinks were called.
     */
    suspend fun refreshBitcoin(): Boolean
}
