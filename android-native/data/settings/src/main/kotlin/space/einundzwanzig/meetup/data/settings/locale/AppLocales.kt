package space.einundzwanzig.meetup.data.settings.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Static language facts of the app (Dart `LocaleController.supported` / `displayName`,
 * `HomeScreen._flagFor`, spec 12 §2.6 and §3.6).
 */
object AppLocales {
    /** Supported language codes in picker order. German is the template and fallback. */
    val SUPPORTED: List<String> = listOf("de", "en", "es")

    /**
     * Picker label: `de`→`Deutsch`, `en`→`English`, `es`→`Español`, anything else (incl. `null`
     * = system) → `System`. Hard-coded like in Dart (language names are not translated).
     */
    fun displayName(code: String?): String = when (code) {
        "de" -> "Deutsch"
        "en" -> "English"
        "es" -> "Español"
        else -> "System"
    }

    /** Picker flag: de 🇩🇪, en 🇬🇧, es 🇪🇸, else 🌐 (Dart `_flagFor`). */
    fun flagFor(code: String?): String = when (code) {
        "de" -> "🇩🇪"
        "en" -> "🇬🇧"
        "es" -> "🇪🇸"
        else -> "🌐"
    }

    /** Language subtag of a BCP-47 tag (`de-AT` → `de`), or `null` for null/empty/undetermined. */
    fun languageOf(tag: String?): String? {
        if (tag.isNullOrEmpty()) return null
        return Locale.forLanguageTag(tag).language.takeIf { it.isNotEmpty() && it != "und" }
    }

    /**
     * Context with [languageCode] applied, for `Activity.attachBaseContext` below API 33 where
     * there is no per-app language API without AppCompat. Returns [base] unchanged on API 33+
     * (the platform `LocaleManager` already applies the language).
     *
     * Below API 33 this also sets the process-wide `Locale.getDefault()` (used by
     * `DateTimeFormatter` month/day names and `String.format`) to the chosen language, or back to
     * the system language of [base] for `null` (system), in which case [base] is returned
     * unchanged. Contexts that are not wrapped (the Application context used by notifications,
     * widgets, workers) keep the system language for resources; see the module README.
     */
    fun wrapContext(base: Context, languageCode: String?): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        if (languageCode.isNullOrEmpty()) {
            val system = base.resources.configuration.locales
            if (!system.isEmpty) Locale.setDefault(system.get(0))
            return base
        }
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
