package space.einundzwanzig.meetup.data.mempool.widget

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BitcoinWidgetBridgeImpl @Inject constructor(
    private val sinks: Set<@JvmSuppressWildcards BitcoinWidgetSink>,
    private val currency: CurrencyRepository,
    private val mempool: MempoolService,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : BitcoinWidgetBridge {

    override suspend fun publish(d: BitcoinDashboardData): Boolean {
        if (d.isDead || sinks.isEmpty()) return false
        val values = BitcoinWidgetValues.of(d, currency.read(), time.nowMillis())
        for (sink in sinks) {
            try {
                sink.updateBitcoin(values)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Bitcoin-Widget-Update fehlgeschlagen: $e")
            }
        }
        return true
    }

    override suspend fun refreshBitcoin(): Boolean {
        val d = mempool.getDashboardData()
        if (d.isDead) {
            val reason = d.lastError.ifEmpty { "offline" }
            log.diag(TAG, "Bitcoin-Widget nicht aktualisiert — keine Quelle erreichbar ($reason). Alte Werte bleiben stehen.")
            return false
        }
        return publish(d)
    }

    private companion object {
        const val TAG = "WidgetService"
    }
}
