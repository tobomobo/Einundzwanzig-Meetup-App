package space.einundzwanzig.meetup.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.log.InventoryCounts
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarController
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetRenderer
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetSink
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetValues
import space.einundzwanzig.meetup.data.migration.MigrationCompletedHook
import space.einundzwanzig.meetup.data.settings.diagnostics.DiagnosticsInventorySource
import space.einundzwanzig.meetup.widget.MeetupWidget
import javax.inject.Inject
import javax.inject.Singleton

/** App-level bindings: widget seams of the data modules, startup work, root snackbar queue. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun homeWidgetRenderer(impl: GlanceHomeWidgetRenderer): HomeWidgetRenderer

    @Binds
    @IntoSet
    abstract fun bitcoinWidgetSink(impl: HomeWidgetBitcoinSink): BitcoinWidgetSink

    @Binds
    @IntoSet
    abstract fun widgetAfterMigration(impl: WidgetMigrationHook): MigrationCompletedHook

    @Binds
    abstract fun inventorySource(impl: AppInventorySource): DiagnosticsInventorySource

    @Binds
    @IntoSet
    abstract fun badgeLoadTask(impl: BadgeLoadStartupTask): AppStartupTask

    companion object {
        /** The root snackbar queue that outlives screens and activity recreation (core:ui README). */
        @Provides
        @Singleton
        fun appSnackbarController(): AppSnackbarController = AppSnackbarController()
    }
}

/** Re-renders every placed widget after a widget write (spec 14 §3.1.4). */
class GlanceHomeWidgetRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) : HomeWidgetRenderer {
    override suspend fun requestUpdate() {
        MeetupWidget().updateAll(context)
    }
}

/** Dart `WidgetService.updateBitcoin`: the dashboard values go into `HomeWidgetPreferences`. */
class HomeWidgetBitcoinSink @Inject constructor(
    private val store: HomeWidgetStore,
) : BitcoinWidgetSink {
    override suspend fun updateBitcoin(values: BitcoinWidgetValues) {
        store.writeBitcoin(values.toMap())
    }
}

/** Replaces the Flutter-era `RemoteViews` once the import finished (spec 14 §3.2 upgrade path). */
class WidgetMigrationHook @Inject constructor(
    private val renderer: HomeWidgetRenderer,
) : MigrationCompletedHook {
    override suspend fun onMigrationImported(report: space.einundzwanzig.meetup.data.migration.MigrationReport) {
        renderer.requestUpdate()
    }
}

/** `Bestand:` line of the start-up fingerprint (spec 12 §8.13): badge and favourite counts. */
class AppInventorySource @Inject constructor(
    private val badges: BadgeRepository,
    private val profiles: UserProfileRepository,
) : DiagnosticsInventorySource {
    override suspend fun counts(): InventoryCounts =
        InventoryCounts(badges = badges.loadBadges().size, favorites = profiles.load().favoriteMeetupIds.size)
}

/**
 * Dart `main()` step 8 `MeetupBadge.loadBadges()` before the badge sync starts (spec 12 §2.1). Runs
 * after the diagnostics group and before the default-order tasks (badge sync, reputation publish).
 */
class BadgeLoadStartupTask @Inject constructor(
    private val badges: BadgeRepository,
) : AppStartupTask {
    override val name: String = "BadgeLoad"
    override val order: Int = -50
    override suspend fun run() {
        badges.loadBadges()
    }
}
