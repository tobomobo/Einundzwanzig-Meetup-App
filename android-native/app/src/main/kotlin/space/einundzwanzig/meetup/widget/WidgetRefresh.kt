package space.einundzwanzig.meetup.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridge
import space.einundzwanzig.meetup.data.migration.MigrationGate
import space.einundzwanzig.meetup.data.news.NewsService

/** Widget "⟳" (spec 14 §3.1.6, §3.2): enqueues the unique refresh worker. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        enqueue(context)
    }

    companion object {
        const val UNIQUE_WORK = "widget_refresh"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Dart `widgetBackgroundCallback('refresh')`: `refreshBitcoin()` (skipped when no source answered)
 * then `refreshNews()` (latest article, `limit = 1`), then re-render every widget.
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val gate: MigrationGate,
    private val bitcoin: BitcoinWidgetBridge,
    private val news: NewsService,
    private val widgetStore: HomeWidgetStore,
    private val log: DiagnosticLog,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        refreshWidget(gate, bitcoin, news, widgetStore, log)
        MeetupWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/** The worker body without Android types (unit-tested). */
internal suspend fun refreshWidget(
    gate: MigrationGate,
    bitcoin: BitcoinWidgetBridge,
    news: NewsService,
    widgetStore: HomeWidgetStore,
    log: DiagnosticLog,
) {
    // WorkManager cancellation stops this attempt; it must never bypass an unfinished import.
    gate.await()
    try {
        bitcoin.refreshBitcoin()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("WidgetService", "refreshBitcoin fehlgeschlagen", e)
    }
    try {
        val latest = news.fetchArticles(limit = 1).firstOrNull() ?: return
        widgetStore.publishLatestNews(latest.id, latest.title)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("WidgetService", "refreshNews fehlgeschlagen", e)
    }
}
