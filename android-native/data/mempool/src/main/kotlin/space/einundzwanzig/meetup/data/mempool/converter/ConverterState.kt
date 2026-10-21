package space.einundzwanzig.meetup.data.mempool.converter

import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import space.einundzwanzig.meetup.data.mempool.format.dartTryParseDouble

/**
 * Pure state and math of the converter screen (`lib/screens/converter_screen.dart`,
 * spec 13 §2.2, §8.8, §8.9). Immutable; every event returns a new state. Nothing is persisted
 * except the currency (the ViewModel calls `CurrencyRepository.set`).
 *
 * Text fields hold what the user typed; only the *other* field is rewritten by [recalculated].
 * `,` and `.` are both decimal separators (every `,` becomes `.` before parsing).
 *
 * @property currency selected fiat code.
 * @property cryptoIsBtc crypto unit: `true` = BTC, `false` = sats.
 * @property fiatIsInput the side edited last (the other side is computed).
 * @property premiumPercent premium/discount in percent (unclamped; the slider clamps to ±20).
 * @property prices BTC price per currency from the last successful load.
 * @property loading a price load is running.
 * @property error the last price load returned nothing (old [prices] are kept).
 * @property updatedAtMillis wall clock of the last successful price load.
 */
data class ConverterState(
    val currency: String,
    val cryptoIsBtc: Boolean = false,
    val fiatIsInput: Boolean = true,
    val fiatText: String = "",
    val cryptoText: String = "",
    val premiumText: String = "",
    val premiumPercent: Double = 0.0,
    val prices: Map<String, Double> = emptyMap(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val updatedAtMillis: Long? = null,
) {
    /** Price of one BTC in [currency], 0 when unknown. */
    val btcPrice: Double get() = prices[currency] ?: 0.0

    /** Fiat amount: the fiat field, else derived from the crypto field, else 0. */
    val currentFiat: Double
        get() {
            parse(fiatText)?.let { return it }
            val raw = parse(cryptoText)
            if (raw != null && btcPrice > 0) return toBtc(raw) * btcPrice
            return 0.0
        }

    /** [currentFiat] with the premium applied. */
    val fiatWithPremium: Double get() = currentFiat * (1 + premiumPercent / 100)

    /** Sats for [fiatWithPremium], 0 without a price (or for non-finite input). */
    val satsWithPremium: Long
        get() {
            if (btcPrice <= 0) return 0
            val sats = fiatWithPremium / btcPrice * BtcFormat.SATS_PER_BTC
            return if (sats.isFinite()) sats.dartRound() else 0
        }

    /** Slider position: [premiumPercent] clamped to −20..20. */
    val sliderValue: Double get() = premiumPercent.coerceIn(-PREMIUM_LIMIT, PREMIUM_LIMIT)

    /** Slider value-indicator label: `+2.5%`, `0.0%`, `-0.5%` (uses the unclamped value). */
    val premiumLabel: String get() = premiumLabel(premiumPercent)

    /** Premium result row: `"102.50 EUR"`. */
    val premiumResultText: String get() = "${fiatWithPremium.toStringAsFixed(2)} $currency"

    /** Premium sats row: `"107.895 sats"`. */
    val premiumSatsText: String get() = "${BtcFormat.fmtSats(satsWithPremium)} sats"

    /** Premium base row: `"100.00 EUR"`. */
    val premiumBaseText: String get() = "${currentFiat.toStringAsFixed(2)} $currency"

    /** Fiat field edited. */
    fun onFiatChanged(text: String): ConverterState = copy(fiatText = text, fiatIsInput = true).recalculated()

    /** Crypto field edited. */
    fun onCryptoChanged(text: String): ConverterState = copy(cryptoText = text, fiatIsInput = false).recalculated()

    /**
     * Toggles sats ⇄ BTC and converts the crypto text if it parses (`21000` sats → `0.00021000`,
     * `0,5` BTC → `50000000`). No recalculation; the fiat side stays.
     */
    fun swapUnit(): ConverterState {
        val toBtc = !cryptoIsBtc
        val raw = parse(cryptoText) ?: return copy(cryptoIsBtc = toBtc)
        val text = if (toBtc) {
            (raw / BtcFormat.SATS_PER_BTC).toStringAsFixed(8)
        } else {
            roundOrNull(raw * BtcFormat.SATS_PER_BTC)?.toString() ?: cryptoText
        }
        return copy(cryptoIsBtc = toBtc, cryptoText = text)
    }

    /** Currency picked in the sheet; the edited side is kept and the other one recomputed. */
    fun onCurrencyPicked(code: String): ConverterState = copy(currency = code).recalculated()

    /** Premium field edited: any value, unparseable → 0. */
    fun onPremiumText(text: String): ConverterState =
        copy(premiumText = text, premiumPercent = dartTryParseDouble(text.replace(',', '.')) ?: 0.0)

    /** Slider moved: rounded to 0.5 steps; the field shows `""` for 0, else Dart `toString`. */
    fun onSlider(value: Double): ConverterState {
        val p = (value * 2).dartRound() / 2.0
        return copy(premiumPercent = p, premiumText = if (p == 0.0) "" else p.toDartString())
    }

    /** [onSlider] for Compose's `Float` slider value. */
    fun onSlider(value: Float): ConverterState = onSlider(value.toDouble())

    /** A price load started. */
    fun onLoading(): ConverterState = copy(loading = true, error = false)

    /** A price load finished; an empty map is a failure (old prices stay). */
    fun onPricesLoaded(newPrices: Map<String, Double>, nowMillis: Long): ConverterState =
        if (newPrices.isEmpty()) {
            copy(loading = false, error = true)
        } else {
            copy(prices = newPrices, loading = false, error = false, updatedAtMillis = nowMillis).recalculated()
        }

    /**
     * Recomputes the side that was not edited last (Dart `_recalculate`). Without a price
     * nothing changes; an unparseable input clears the other side.
     */
    fun recalculated(): ConverterState {
        if (btcPrice <= 0) return this
        return if (fiatIsInput) {
            val fiat = parse(fiatText) ?: return copy(cryptoText = "")
            copy(cryptoText = formatCrypto(fiat / btcPrice))
        } else {
            val raw = parse(cryptoText) ?: return copy(fiatText = "")
            copy(fiatText = (toBtc(raw) * btcPrice).toStringAsFixed(2))
        }
    }

    private fun toBtc(raw: Double): Double = if (cryptoIsBtc) raw else raw / BtcFormat.SATS_PER_BTC

    private fun formatCrypto(btc: Double): String =
        if (cryptoIsBtc) btc.toStringAsFixed(8) else roundOrNull(btc * BtcFormat.SATS_PER_BTC)?.toString() ?: ""

    /** Constants and pure helpers. */
    companion object {
        /** Slider range limit in percent. */
        const val PREMIUM_LIMIT: Double = 20.0

        /** Slider divisions (Compose `steps = DIVISIONS - 1`). */
        const val DIVISIONS: Int = 80

        /** Dart `double.tryParse(text.replaceAll(',', '.'))`. */
        fun parse(text: String): Double? = dartTryParseDouble(text.replace(',', '.'))

        /** Slider label: `"${if (p > 0) "+" else ""}${p.toStringAsFixed(1)}%"`. */
        fun premiumLabel(p: Double): String = "${if (p > 0) "+" else ""}${p.toStringAsFixed(1)}%"

        // Dart's round() throws for NaN/Infinity (a red error screen); the port keeps the text.
        private fun roundOrNull(x: Double): Long? = if (x.isFinite()) x.dartRound() else null
    }
}
