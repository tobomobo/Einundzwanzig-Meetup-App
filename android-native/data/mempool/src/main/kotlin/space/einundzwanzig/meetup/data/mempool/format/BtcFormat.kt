package space.einundzwanzig.meetup.data.mempool.format

import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyFormat
import java.time.Instant
import java.time.ZoneId

/**
 * Locale-independent display strings of the Bitcoin screens, tiles and widget
 * (spec 13 §2.1, §2.5, §4.3, §7). The exact characters matter: [DASH] is two U+2013,
 * the fee separator is U+00B7.
 */
object BtcFormat {
    /** Placeholder for unknown values: two U+2013 en dashes. */
    const val DASH: String = "––"

    /** Placeholder time (ASCII), used for dead/no data and missing Moscow time. */
    const val NO_TIME: String = "--:--"

    /** Satoshis per bitcoin. */
    const val SATS_PER_BTC: Long = 100_000_000

    /** Dart `_fmtInt`: `.` grouping (non-negative values in practice). */
    fun fmtInt(v: Long): String = CurrencyFormat.groupInt(v)

    /** [fmtInt] for Int values. */
    fun fmtInt(v: Int): String = CurrencyFormat.groupInt(v.toLong())

    /** Converter `_fmtSats`: `.` grouping with a leading `-` for negatives. */
    fun fmtSats(v: Long): String = CurrencyFormat.groupInt(v)

    /** `HH:mm` (24 h, zero padded) of [epochMillis] in [zone]. */
    fun hhmm(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    /** Dashboard time label: `HH:mm` of `updatedAt`, or [NO_TIME] without data or when dead. */
    fun updatedLabel(d: BitcoinDashboardData?, zone: ZoneId = ZoneId.systemDefault()): String =
        if (d != null && !d.isDead) hhmm(d.updatedAtMillis, zone) else NO_TIME

    /** `"low·medium·high"` (always, also `0·0·0`; the widget value). */
    fun fees(d: BitcoinDashboardData): String = "${d.feeLow}·${d.feeMedium}·${d.feeHigh}"

    /** Dashboard fee row: [fees] if `feeHigh > 0`, else [DASH]. */
    fun feesRow(d: BitcoinDashboardData): String = if (d.feeHigh > 0) fees(d) else DASH

    /** `"812 EH/s"` (0 decimals) if positive, else [DASH]. */
    fun hashrate(ehs: Double): String = if (ehs > 0) "${ehs.toStringAsFixed(0)} EH/s" else DASH

    /** Grouped integer if positive, else [DASH] (block, supply, nodes, channels). */
    fun intOrDash(v: Long): String = if (v > 0) fmtInt(v) else DASH

    /** Grouped, rounded price if positive, else [DASH] (`EUR/BTC` row, widget `priceFiat`). */
    fun priceOrDash(price: Double): String = if (price > 0) fmtInt(price.dartRound()) else DASH

    /** Lightning capacity row: `"5.123 BTC"` if positive, else [DASH]. */
    fun lnCapacity(btc: Double): String = if (btc > 0) "${fmtInt(btc.dartRound())} BTC" else DASH

    /** Signed percent with one decimal: `+3.7%`, `-0.0%` (sign `+` for `>= 0`). */
    fun signedPercent(pct: Double): String = "${if (pct >= 0) "+" else ""}${pct.toStringAsFixed(1)}%"

    /**
     * Dashboard difficulty row: `"+3.7% / 1234 blk"`, [DASH] if `blockHeight <= 0`
     * (the guard is the height, not the difficulty).
     */
    fun difficultyRow(d: BitcoinDashboardData): String =
        if (d.blockHeight > 0) "${signedPercent(d.difficultyChangePct)} / ${d.difficultyRemainingBlocks} blk" else DASH

    /** Widget difficulty: `"+3.7% / 1234"` (no ` blk`), [DASH] if `remainingBlocks <= 0`. */
    fun difficultyWidget(d: BitcoinDashboardData): String =
        if (d.difficultyRemainingBlocks > 0) "${signedPercent(d.difficultyChangePct)} / ${d.difficultyRemainingBlocks}" else DASH

    /** Converter tile: sats per one fiat unit, `round(1e8 / price)`, 0 if the price is unknown. */
    fun satsPerUnit(price: Double): Long = if (price > 0) (1e8 / price).dartRound() else 0

    /** Converter tile value `"1 € = 1.053 sats"`, or `null` (show `tileConverter`) without a price. */
    fun converterTile(price: Double, code: String): String? {
        val sats = satsPerUnit(price)
        return if (sats > 0) "1 ${CurrencyFormat.symbol(code)} = ${fmtInt(sats)} sats" else null
    }
}
