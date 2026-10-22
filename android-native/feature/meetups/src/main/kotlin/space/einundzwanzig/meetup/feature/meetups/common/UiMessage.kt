package space.einundzwanzig.meetup.feature.meetups.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.feature.meetups.R

/** Text that a ViewModel hands to the UI without a `Context`. */
sealed interface UiText {
    /** A string resource with positional [args]. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that is shown verbatim. */
    data class Raw(val text: String) : UiText

    /**
     * A portal error (spec 09 §5.3): fixed texts are localized from [kind]; server messages and
     * login failures keep the portal/Flutter text in [error]; nothing at all gives "Fehler".
     */
    data class Portal(val error: String?, val kind: PortalErrorKind?) : UiText

    /** `"<prefix>: <body>"` (Flutter `"${t.portalLoginFailed}: $err"`). */
    data class Prefixed(val prefix: UiText, val body: UiText) : UiText

    companion object {
        fun portal(result: PortalResult): UiText = Portal(result.error, result.errorKind)
    }
}

/** Resolves this text with [context]'s resources. */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
    is UiText.Raw -> text
    is UiText.Prefixed -> "${prefix.resolve(context)}: ${body.resolve(context)}"
    is UiText.Portal -> when (kind) {
        PortalErrorKind.NOT_LOGGED_IN -> context.getString(R.string.native_portal_error_not_logged_in)
        PortalErrorKind.NETWORK -> context.getString(R.string.native_portal_error_network)
        PortalErrorKind.UNAUTHENTICATED -> context.getString(R.string.native_portal_error_unauthenticated)
        PortalErrorKind.FORBIDDEN -> context.getString(R.string.native_portal_error_forbidden)
        PortalErrorKind.NOT_FOUND -> context.getString(R.string.native_portal_error_not_found)
        PortalErrorKind.VALIDATION -> context.getString(R.string.native_portal_error_validation)
        PortalErrorKind.NO_TOKEN -> context.getString(R.string.native_portal_error_no_token)
        PortalErrorKind.HTTP -> {
            val code = HTTP_CODE.find(error.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (code != null) context.getString(R.string.native_portal_error_http, code) else error.orEmpty()
        }
        PortalErrorKind.SERVER_MESSAGE, PortalErrorKind.LOGIN_FAILED, null ->
            error?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.native_portal_error_generic)
    }
}

private val HTTP_CODE = Regex("""HTTP (\d+)""")

/** A snackbar request from a ViewModel. */
data class UiMessage(
    val text: UiText,
    val tone: Tone = Tone.Neutral,
    val durationMillis: Long = FLUTTER_SNACKBAR_MILLIS,
)

/** Shows every [messages] item on [host] while composed. */
@Composable
fun CollectMessages(messages: Flow<UiMessage>, host: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages, host) {
        messages.collect { m -> host.showAppSnackbar(m.text.resolve(context), m.tone, m.durationMillis) }
    }
}

/** Flutter `SnackBar` default display time (4 s). */
const val FLUTTER_SNACKBAR_MILLIS = 4_000L
