package space.einundzwanzig.meetup.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import space.einundzwanzig.meetup.R
import space.einundzwanzig.meetup.core.common.url.SafeUrl
import space.einundzwanzig.meetup.core.common.log.AppLogger

/**
 * Opens data-derived links outside the app (Flutter `launchHttpUrl`, Security Audit M2): only
 * http(s) URLs with a host pass; everything else is refused and logged with the host only.
 * Prefers a Custom Tab in the brand colours and falls back to a plain `ACTION_VIEW`.
 */
object ExternalUrlOpener {
    private const val TAG = "Link"

    /** @return `true` when a browser was started. */
    fun open(context: Context, url: String): Boolean {
        val safe = SafeUrl.httpUrlOrNull(url)
        if (safe == null) {
            AppLogger.warn(TAG, "Link nicht geöffnet (Schema nicht http(s)), Host: ${SafeUrl.hostForLog(url)}")
            return false
        }
        val uri = safe.url.toUri()
        val toolbar = ContextCompat.getColor(context, R.color.brand_dark)
        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbar)
                    .setNavigationBarColor(toolbar)
                    .build(),
            )
            .build()
        if (context !is android.app.Activity) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            customTab.launchUrl(context, uri)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).apply {
                        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                true
            } catch (e: ActivityNotFoundException) {
                AppLogger.warn(TAG, "Kein Browser für Host ${SafeUrl.hostForLog(url)}: ${e.javaClass.simpleName}")
                false
            }
        } catch (e: SecurityException) {
            AppLogger.warn(TAG, "Link nicht geöffnet, Host ${SafeUrl.hostForLog(url)}: ${e.javaClass.simpleName}")
            false
        }
    }
}
