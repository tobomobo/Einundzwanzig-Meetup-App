package space.einundzwanzig.meetup.data.settings.locale

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi

/** The platform per-app language, abstracted for JVM tests. */
internal interface PlatformLocales {
    /** Whether the platform manages the per-app language (API 33+). */
    val isAvailable: Boolean

    /** Per-app language tags (e.g. `de` or `de-AT`), `null` when following the system. */
    fun applicationLanguageTag(): String?

    /** Sets the per-app language; `null` = follow the system. */
    fun setApplicationLanguageTag(tag: String?)

    companion object {
        /** The implementation for the running device. */
        fun create(context: Context): PlatformLocales =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LocaleManagerPlatformLocales(context)
            } else {
                Unavailable
            }
    }

    /** Below API 33: the activity applies the language itself (see [LocaleController]). */
    object Unavailable : PlatformLocales {
        override val isAvailable: Boolean = false
        override fun applicationLanguageTag(): String? = null
        override fun setApplicationLanguageTag(tag: String?) = Unit
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LocaleManagerPlatformLocales(context: Context) : PlatformLocales {
    private val manager: LocaleManager = context.getSystemService(LocaleManager::class.java)

    override val isAvailable: Boolean = true

    override fun applicationLanguageTag(): String? {
        val locales = manager.applicationLocales
        return if (locales.isEmpty) null else locales[0].toLanguageTag()
    }

    override fun setApplicationLanguageTag(tag: String?) {
        manager.applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
