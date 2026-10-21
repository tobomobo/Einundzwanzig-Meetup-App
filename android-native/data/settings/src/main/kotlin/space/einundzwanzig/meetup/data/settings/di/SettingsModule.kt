package space.einundzwanzig.meetup.data.settings.di

import android.content.Context
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.TourProgressStore
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import space.einundzwanzig.meetup.data.settings.diagnostics.DiagnosticsInventorySource
import space.einundzwanzig.meetup.data.settings.diagnostics.DiagnosticsStartupTask
import space.einundzwanzig.meetup.data.settings.internal.KeyValueSettingsRepository
import space.einundzwanzig.meetup.data.settings.internal.KeyValueTourProgressStore
import space.einundzwanzig.meetup.data.settings.locale.LocaleController
import space.einundzwanzig.meetup.data.settings.locale.LocaleControllerImpl
import space.einundzwanzig.meetup.data.settings.locale.LocaleStartupTask
import space.einundzwanzig.meetup.data.settings.locale.PlatformLocales
import javax.inject.Singleton

/** Bindings of `:data:settings`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SettingsModule {

    @Binds
    abstract fun settingsRepository(impl: KeyValueSettingsRepository): SettingsRepository

    @Binds
    abstract fun tourProgressStore(impl: KeyValueTourProgressStore): TourProgressStore

    @Binds
    abstract fun localeController(impl: LocaleControllerImpl): LocaleController

    @BindsOptionalOf
    abstract fun inventorySource(): DiagnosticsInventorySource

    @Binds
    @IntoSet
    abstract fun localeStartupTask(task: LocaleStartupTask): AppStartupTask

    @Binds
    @IntoSet
    abstract fun diagnosticsStartupTask(task: DiagnosticsStartupTask): AppStartupTask

    companion object {
        @Provides
        @Singleton
        fun platformLocales(@ApplicationContext context: Context): PlatformLocales = PlatformLocales.create(context)

        /** The one app-wide tour state machine, persisted through [TourProgressStore]. */
        @Provides
        @Singleton
        fun guideController(store: TourProgressStore, @ApplicationScope scope: CoroutineScope): GuideController =
            GuideController(store, scope)
    }
}

/**
 * Access for code that runs before Hilt injects (e.g. `Activity.attachBaseContext`):
 * `EntryPointAccessors.fromApplication<SettingsEntryPoint>(base.applicationContext).localeController()`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    /** The app-wide [LocaleController]. */
    fun localeController(): LocaleController
}
