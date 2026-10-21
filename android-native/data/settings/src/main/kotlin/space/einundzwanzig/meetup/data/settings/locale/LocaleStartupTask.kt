package space.einundzwanzig.meetup.data.settings.locale

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import javax.inject.Inject

/**
 * API 33+: adopts a language chosen in the system settings, then keeps the platform per-app
 * language in step with `app_locale` for the rest of the process (including an app reset that
 * clears the store). Returns right after starting the collector so later startup groups are not
 * blocked. No-op below API 33.
 */
internal class LocaleStartupTask @Inject constructor(
    private val controller: LocaleControllerImpl,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppStartupTask {

    override val name: String = "LocaleSync"

    override val order: Int = 0

    override suspend fun run() {
        if (!controller.isManagedByPlatform) return
        controller.syncWithSystem()
        scope.launch {
            controller.appLocale.collect { controller.applyToPlatform(it) }
        }
    }
}
