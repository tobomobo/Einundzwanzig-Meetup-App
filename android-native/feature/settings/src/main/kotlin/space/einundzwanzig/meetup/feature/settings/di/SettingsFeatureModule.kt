package space.einundzwanzig.meetup.feature.settings.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.feature.settings.AppVersionSource
import space.einundzwanzig.meetup.feature.settings.Nip44Probe

@Module
@InstallIn(SingletonComponent::class)
internal object SettingsFeatureModule {

    @Provides
    fun nip44Probe(signing: SigningService): Nip44Probe = Nip44Probe {
        // canNip44 logs its own step and reason; a throwing signer counts as a failed probe.
        runSuspendCatching { signing.canNip44() }
            .onFailure { AppLogger.warn("Log", "NIP-44-Probe abgebrochen", it) }
            .getOrDefault(false)
    }

    @Provides
    fun appVersionSource(@ApplicationContext context: Context): AppVersionSource = AppVersionSource {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            AppLogger.debug("App", "Versionsname nicht lesbar: ${e.message}")
            ""
        }
    }
}
