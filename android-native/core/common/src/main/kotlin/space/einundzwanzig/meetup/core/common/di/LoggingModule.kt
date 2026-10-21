package space.einundzwanzig.meetup.core.common.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.common.log.AndroidEnvironmentProbe
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticsService
import space.einundzwanzig.meetup.core.common.log.EnvironmentProbe
import space.einundzwanzig.meetup.core.common.log.SecretRedactor
import javax.inject.Singleton

/**
 * Provides the process-wide logger. The instance is [AppLogger.logger], so injected and static
 * callers share one buffer.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun diagnosticLogger(): DiagnosticLogger = AppLogger.logger

    @Provides
    fun diagnosticLog(logger: DiagnosticLogger): DiagnosticLog = logger

    @Provides
    fun secretRedactor(): SecretRedactor = SecretRedactor.Default

    @Provides
    @Singleton
    fun environmentProbe(@ApplicationContext context: Context): EnvironmentProbe = AndroidEnvironmentProbe(context)

    @Provides
    @Singleton
    fun diagnosticsService(log: DiagnosticLog, probe: EnvironmentProbe): DiagnosticsService =
        DiagnosticsService(log, probe)
}
