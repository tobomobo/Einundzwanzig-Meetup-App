package space.einundzwanzig.meetup.feature.profile.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.util.findActivity
import space.einundzwanzig.meetup.feature.profile.R
import space.einundzwanzig.meetup.feature.profile.common.SnackbarEffect

/**
 * Stateful entry of the `ProfileEdit` destination (spec 07 §2.2, spec 01 §2.7). Runs the
 * Activity-bound parts: device authentication, photo picker, clipboard and navigation.
 */
@Composable
fun ProfileEditRoute(viewModel: ProfileEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bunkerState by viewModel.bunker.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(viewModel.snackbars, snackbarHostState)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onAction(ProfileEditAction.AvatarPicked(uri.toString()))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProfileEditEffect.CopyPlain -> SecretClipboard.copyPlain(context, effect.text)
                is ProfileEditEffect.Navigate -> navigator.navigate(effect.key)
                ProfileEditEffect.ResetToHome -> navigator.resetTo(HomeTab)
                ProfileEditEffect.Back -> navigator.back()
                ProfileEditEffect.PickImage -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }

    // Device authentication (BiometricPrompt needs the FragmentActivity).
    val pending = state.pendingAuth
    val showTitle = stringResource(R.string.native_auth_show_key)
    val exportTitle = stringResource(R.string.native_auth_export_key)
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val title = when (pending) {
            AuthPurpose.SHOW_NEW_KEY, AuthPurpose.SHOW_NSEC -> showTitle
            AuthPurpose.EXPORT, AuthPurpose.EXPORT_NEW_PASSWORD -> exportTitle
        }
        // A configuration change cancels the running prompt (DeviceAuthenticator cancels it on
        // cancellation / ON_DESTROY); pendingAuth is still set, so the new composition shows it again.
        val activity = context.findActivity() as? FragmentActivity
        if (activity == null) {
            viewModel.onAction(ProfileEditAction.AuthUnavailable(pending))
            return@LaunchedEffect
        }
        val ok = viewModel.authenticate(activity, title)
        viewModel.onAction(ProfileEditAction.AuthResult(pending, ok))
    }

    ProfileEditScreen(
        state = state,
        bunkerState = bunkerState,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
    )
}
