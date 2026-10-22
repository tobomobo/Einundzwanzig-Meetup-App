package space.einundzwanzig.meetup.core.ui.security

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * Secret-safe window helpers (spec 14 §0 F7, §6, R12).
 *
 * Flutter drew dialogs into the activity window, so `FLAG_SECURE` on the activity covered the
 * nsec dialog. Compose `Dialog`, `AlertDialog` and `ModalBottomSheet` are **separate windows**;
 * their default `SecureFlagPolicy.Inherit` only copies the flag if the activity already had it
 * when the dialog window was created. Every dialog or sheet that shows an nsec or ncryptsec must
 * therefore use these properties (or [SecureDialog]) **and** call [SecureScreenEffect] inside its
 * content, which also marks the activity window for the recents thumbnail.
 */
object SecureWindows {
    /**
     * [DialogProperties] with `securePolicy = SecureOn`. Pass to `Dialog`/`AlertDialog`
     * (`properties = SecureWindows.dialogProperties()`).
     */
    fun dialogProperties(
        dismissOnBackPress: Boolean = true,
        dismissOnClickOutside: Boolean = true,
        usePlatformDefaultWidth: Boolean = true,
        decorFitsSystemWindows: Boolean = true,
    ): DialogProperties = DialogProperties(
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        securePolicy = SecureFlagPolicy.SecureOn,
        usePlatformDefaultWidth = usePlatformDefaultWidth,
        decorFitsSystemWindows = decorFitsSystemWindows,
    )

    /**
     * [ModalBottomSheetProperties] with `securePolicy = SecureOn` for sheets that show a secret
     * (e.g. the ncryptsec export sheet).
     */
    @ExperimentalMaterial3Api
    fun bottomSheetProperties(
        shouldDismissOnBackPress: Boolean = true,
        shouldDismissOnClickOutside: Boolean = true,
    ): ModalBottomSheetProperties = ModalBottomSheetProperties(
        securePolicy = SecureFlagPolicy.SecureOn,
        shouldDismissOnBackPress = shouldDismissOnBackPress,
        shouldDismissOnClickOutside = shouldDismissOnClickOutside,
    )
}

/**
 * A `Dialog` that can never be captured: its own window is created with
 * `SecureFlagPolicy.SecureOn` and, while it is shown, the activity window carries
 * `FLAG_SECURE` too ([SecureScreenEffect], reference counted). Use it for the nsec dialog.
 *
 * @param properties must keep `securePolicy = SecureOn`; build them with
 *   [SecureWindows.dialogProperties].
 */
@Composable
fun SecureDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = SecureWindows.dialogProperties(),
    content: @Composable () -> Unit,
) {
    require(properties.securePolicy == SecureFlagPolicy.SecureOn) {
        "SecureDialog needs securePolicy = SecureOn (use SecureWindows.dialogProperties())"
    }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        SecureScreenEffect()
        content()
    }
}
