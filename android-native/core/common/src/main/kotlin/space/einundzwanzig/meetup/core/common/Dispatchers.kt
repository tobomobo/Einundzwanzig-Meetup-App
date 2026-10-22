package space.einundzwanzig.meetup.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import space.einundzwanzig.meetup.core.common.log.AppLogger
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Process-wide scope for work that must outlive a screen (relay connections, sync). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Spec 12 §2.1: uncaught failures of `appScope.launch` are logged as
     * `ERROR [Async] Unbehandelter Fehler` instead of killing the process (Flutter
     * `PlatformDispatcher.onError`), so testers can still export the diagnostic log.
     */
    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + AppLogger.coroutineExceptionHandler)
}
