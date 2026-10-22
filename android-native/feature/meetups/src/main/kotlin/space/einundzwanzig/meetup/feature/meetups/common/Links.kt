package space.einundzwanzig.meetup.feature.meetups.common

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** What the user sees when a link cannot be opened. */
enum class LinkFailure {
    /** Nothing (Flutter `canLaunchUrl` false → silent; Nostr link errors are only logged). */
    Silent,

    /** `errorOpenLink` (Flutter `launchHttpUrl`, meetup details). */
    Snackbar,

    /** `"${errorOpenLink}: <url>"`, red (Flutter `CommunityPortalScreen._open`). */
    SnackbarWithUrl,
}

/**
 * Opens http(s) links externally through the safe launcher (Audit M2: other schemes are refused)
 * and reports failures as [LinkFailure] says.
 */
class LinkOpener internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: SnackbarHostState,
) {
    fun open(url: String, failure: LinkFailure = LinkFailure.Snackbar) {
        if (SafeUrlLauncher.open(context, url)) return
        AppLogger.warn(TAG, "Link nicht geöffnet: ${url.substringBefore('?').take(80)}")
        val prefix = context.getString(L10nR.string.errorOpenLink)
        when (failure) {
            LinkFailure.Silent -> Unit
            LinkFailure.Snackbar -> scope.launch { host.showAppSnackbar(prefix, Tone.Error, FLUTTER_SNACKBAR_MILLIS) }
            LinkFailure.SnackbarWithUrl ->
                scope.launch { host.showAppSnackbar("$prefix: $url", Tone.Error, FLUTTER_SNACKBAR_MILLIS) }
        }
    }

    private companion object {
        const val TAG = "Meetups"
    }
}

/** A [LinkOpener] bound to this composition. */
@Composable
fun rememberLinkOpener(host: SnackbarHostState): LinkOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, host) { LinkOpener(context, scope, host) }
}
