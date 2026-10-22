package space.einundzwanzig.meetup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.DeepLinks
import space.einundzwanzig.meetup.data.calendar.widget.WidgetLaunchTargetStore

/**
 * Invisible router for the widget tap areas (spec 14 §3.1.5, FQCN kept for stale Flutter-era
 * PendingIntents). Stores the target as `e21_widget/pending_target` (commit) and opens
 * [MainActivity]; the app consumes the target once on resume. MIUI replays the old launch intent
 * when MainActivity is restored from recents, so the target never travels in MainActivity's intent.
 */
class WidgetRouterActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RouterEntryPoint {
        fun widgetLaunchTargetStore(): WidgetLaunchTargetStore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val target = targetOf(intent)
            if (!target.isNullOrEmpty() && target != DeepLinks.TARGET_HOME) {
                EntryPointAccessors.fromApplication(applicationContext, RouterEntryPoint::class.java)
                    .widgetLaunchTargetStore()
                    .store(target)
            }
        } catch (e: Exception) {
            AppLogger.warn("Widget", "Widget-Ziel nicht gespeichert", e)
        }
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLogger.warn("Widget", "App nicht geöffnet", e)
        }
        finish()
    }

    internal companion object {
        /** Flutter: `intent.data?.host ?: intent.getStringExtra("open")`. */
        fun targetOf(intent: Intent?): String? =
            intent?.data?.host?.takeIf { it.isNotEmpty() } ?: intent?.getStringExtra(DeepLinks.EXTRA_OPEN)
    }
}
