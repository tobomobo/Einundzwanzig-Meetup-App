package space.einundzwanzig.meetup.data.settings.locale

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.settings.SettingsKeys
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** [LocaleController] on [SettingsRepository] and the platform `LocaleManager`. */
@Singleton
internal class LocaleControllerImpl @Inject constructor(
    private val settings: SettingsRepository,
    private val store: KeyValueStore,
    private val platform: PlatformLocales,
) : LocaleController {

    /** Serializes platform writes from [setLocale], [syncWithSystem] and the startup collector. */
    private val applyLock = Mutex()

    override val appLocale: Flow<String?> get() = settings.appLocale

    override val isManagedByPlatform: Boolean get() = platform.isAvailable

    override suspend fun current(): String? = settings.getAppLocale()

    override suspend fun setLocale(code: String?): Boolean {
        val normalized = code?.takeIf { it.isNotEmpty() }
        val ok = settings.setAppLocale(normalized)
        if (ok) {
            AppLogger.info(TAG, "Sprache: ${normalized ?: "System"}")
            applyToPlatform()
        }
        return ok
    }

    override suspend fun syncWithSystem() {
        if (!platform.isAvailable) return
        applyLock.withLock {
            try {
                val system = platform.applicationLanguageTag()
                val applied = store.getString(SettingsKeys.NATIVE_APPLIED_LOCALE)
                val stored = settings.getAppLocale()
                val changedOutside = if (applied == null) {
                    // Never applied by this app (fresh install, after migration or reset): the stored
                    // preference wins; only an otherwise unset preference adopts a system choice.
                    stored == null && system != null
                } else {
                    system != applied.ifEmpty { null }
                }
                if (!changedOutside) return
                // Dart only ever stores de/en/es: an unsupported platform language counts as "system".
                val adopted = AppLocales.languageOf(system)?.takeIf { it in AppLocales.SUPPORTED }
                // One transaction: the marker must never record a system tag whose language was not
                // stored, or the change would never be adopted (system == applied on later starts).
                val ok = store.edit {
                    if (adopted == null) {
                        remove(SettingsKeys.APP_LOCALE)
                    } else {
                        set(SettingsKeys.APP_LOCALE, PrefValue.StringValue(adopted))
                    }
                    set(SettingsKeys.NATIVE_APPLIED_LOCALE, PrefValue.StringValue(system.orEmpty()))
                }
                if (ok) {
                    AppLogger.info(TAG, "Sprache in den Systemeinstellungen geaendert: ${adopted ?: "System"}")
                } else {
                    AppLogger.warn(TAG, "Systemsprache konnte nicht uebernommen werden")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                AppLogger.warn(TAG, "Systemsprache nicht lesbar", e)
            }
        }
    }

    /**
     * Hands the language stored in `app_locale` at the time the lock is taken to the platform
     * (last write wins, so concurrent [setLocale] calls cannot leave the platform on an older
     * value), unless the platform already shows that language (an unsupported platform language
     * counts as `null`). Records the tag the platform reports afterwards. No-op below API 33.
     */
    suspend fun applyToPlatform() {
        if (!platform.isAvailable) return
        applyLock.withLock {
            try {
                val code = settings.getAppLocale()
                val system = platform.applicationLanguageTag()
                // A platform language the app does not support (set outside the app) is treated
                // like "system": `app_locale` is null for it, so do not override the user's choice.
                val shown = AppLocales.languageOf(system)?.takeIf { it in AppLocales.SUPPORTED }
                val effective = if (shown == code) {
                    system
                } else {
                    platform.setApplicationLanguageTag(code)
                    // Record the platform's canonical form (OEM normalization, localeConfig
                    // filtering), so syncWithSystem does not mistake the own write for an
                    // external change.
                    platform.applicationLanguageTag()
                }
                val record = effective.orEmpty()
                if (store.getString(SettingsKeys.NATIVE_APPLIED_LOCALE) != record) {
                    store.setString(SettingsKeys.NATIVE_APPLIED_LOCALE, record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                AppLogger.warn(TAG, "App-Sprache konnte nicht gesetzt werden", e)
            }
        }
    }

    override fun currentBlocking(): String? = runBlocking { settings.getAppLocale() }

    override fun wrapContext(base: Context): Context =
        if (platform.isAvailable) base else AppLocales.wrapContext(base, currentBlocking())

    private companion object {
        const val TAG = "Locale"
    }
}
