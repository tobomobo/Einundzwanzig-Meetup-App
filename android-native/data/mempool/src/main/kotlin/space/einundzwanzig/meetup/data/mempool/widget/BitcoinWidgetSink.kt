package space.einundzwanzig.meetup.data.mempool.widget

/**
 * Implemented by the home-screen widget domain (keeps `MeetupWidgetProvider` and the
 * `HomeWidgetPreferences` keys). Contribute it with
 * `@Binds @IntoSet abstract fun sink(impl: MyWidgetSink): BitcoinWidgetSink`.
 * Without any sink the widget pushes are no-ops.
 */
interface BitcoinWidgetSink {
    /** Writes [values] (see [BitcoinWidgetValues.toMap]) and refreshes the widget. */
    suspend fun updateBitcoin(values: BitcoinWidgetValues)
}
