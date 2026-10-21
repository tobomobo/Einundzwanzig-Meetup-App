package space.einundzwanzig.meetup.data.mempool.widget

import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyFormat
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import java.time.ZoneId

/**
 * The string values `WidgetService.updateBitcoin` writes into `HomeWidgetPreferences`
 * (spec 13 §4.3). [toMap] uses the exact Flutter key names.
 */
data class BitcoinWidgetValues(
    val block: String,
    val priceFiat: String,
    val priceSymbol: String,
    val priceEur: String,
    val moscow: String,
    val fees: String,
    val hashrate: String,
    val supply: String,
    val difficulty: String,
    val updated: String,
) {
    /** Key → value with the widget's key names (`block`, `priceFiat`, ..., `updated`). */
    fun toMap(): Map<String, String> = linkedMapOf(
        KEY_BLOCK to block,
        KEY_PRICE_FIAT to priceFiat,
        KEY_PRICE_SYMBOL to priceSymbol,
        KEY_PRICE_EUR to priceEur,
        KEY_MOSCOW to moscow,
        KEY_FEES to fees,
        KEY_HASHRATE to hashrate,
        KEY_SUPPLY to supply,
        KEY_DIFFICULTY to difficulty,
        KEY_UPDATED to updated,
    )

    /** Key names and the mapping. */
    companion object {
        const val KEY_BLOCK: String = "block"
        const val KEY_PRICE_FIAT: String = "priceFiat"
        const val KEY_PRICE_SYMBOL: String = "priceSymbol"
        const val KEY_PRICE_EUR: String = "priceEur"
        const val KEY_MOSCOW: String = "moscow"
        const val KEY_FEES: String = "fees"
        const val KEY_HASHRATE: String = "hashrate"
        const val KEY_SUPPLY: String = "supply"
        const val KEY_DIFFICULTY: String = "difficulty"
        const val KEY_UPDATED: String = "updated"

        /**
         * Maps [d] for the widget: prices in [currency], `updated` = `HH:mm` of [nowMillis]
         * (the provider renders `"Stand $updated"`).
         */
        fun of(
            d: BitcoinDashboardData,
            currency: String,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): BitcoinWidgetValues = BitcoinWidgetValues(
            block = BtcFormat.intOrDash(d.blockHeight.toLong()),
            priceFiat = BtcFormat.priceOrDash(d.priceIn(currency)),
            priceSymbol = CurrencyFormat.symbol(currency),
            priceEur = BtcFormat.priceOrDash(d.priceEur),
            moscow = d.moscowTime,
            fees = BtcFormat.fees(d),
            hashrate = BtcFormat.hashrate(d.hashrateEhs),
            supply = BtcFormat.intOrDash(d.supply),
            difficulty = BtcFormat.difficultyWidget(d),
            updated = BtcFormat.hhmm(nowMillis, zone),
        )
    }
}
