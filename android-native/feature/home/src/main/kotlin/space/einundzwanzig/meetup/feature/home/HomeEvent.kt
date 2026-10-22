package space.einundzwanzig.meetup.feature.home

import androidx.navigation3.runtime.NavKey

/** Snackbar texts of the dashboard; the screen resolves them to strings (spec 12 §2.5). */
sealed interface HomeMessage {
    /** `refreshRunning`, 2 s. */
    data object RefreshRunning : HomeMessage

    /** `refreshDone`, green, 4 s. */
    data object RefreshDone : HomeMessage

    /** `badgesUnreadableBody`, 8 s, once per screen instance. */
    data object BadgesUnreadable : HomeMessage

    /** `portalConnectForOrganizer`, orange, 8 s, action `portalConnect`. */
    data object PortalHint : HomeMessage

    /** `portalTokenMismatch`, orange, 6 s. */
    data object TokenMismatch : HomeMessage

    /** `portalConnecting`, 8 s (hidden when the login answers). */
    data object PortalConnecting : HomeMessage

    /** `portalConnected`, green. */
    data object PortalConnected : HomeMessage

    /** `'${portalLoginFailed}: $error'`, red. */
    data class PortalLoginFailed(val error: String) : HomeMessage

    /** `chatSearching`, 4 s. */
    data object ChatSearching : HomeMessage

    /** `chatOffline`, 5 s. */
    data object ChatOffline : HomeMessage

    /** `chatRelayUnavailable`, 5 s. */
    data object ChatRelayUnavailable : HomeMessage

    /** `chatNoRoom(label)`, 4 s. */
    data class ChatNoRoom(val label: String) : HomeMessage

    /** `homeImageLoadError(detail)`. */
    data class ImageLoadError(val detail: String) : HomeMessage

    /** `homeCouldNotOpen(url)`. */
    data class CouldNotOpen(val url: String) : HomeMessage

    /** Native: the reset failed before anything was lost (badges or preferences not wiped). */
    data object ResetFailed : HomeMessage
}

/** One-shot effects of [HomeViewModel]. */
sealed interface HomeEvent {
    data class Show(val message: HomeMessage) : HomeEvent

    /** Hides the current snackbar (Dart `hideCurrentSnackBar`). */
    data object HideMessage : HomeEvent
    data class Navigate(val key: NavKey) : HomeEvent

    /** Opens an external http(s) link; failure shows [HomeMessage.CouldNotOpen]. */
    data class OpenUrl(val url: String) : HomeEvent

    /** Opens Einundzwanzig's Nostr profile (`nostr:` app, njump.me fallback). */
    data object OpenNostrProfile : HomeEvent

    /** Reset done: clear the back stack and show the intro. */
    data object ResetToIntro : HomeEvent
}
