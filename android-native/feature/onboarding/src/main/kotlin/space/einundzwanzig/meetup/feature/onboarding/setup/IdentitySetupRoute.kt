package space.einundzwanzig.meetup.feature.onboarding.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.util.findActivity
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfException
import space.einundzwanzig.meetup.feature.onboarding.common.SnackbarEffect

/**
 * Stateful `IdentitySetup` entry (spec 01 §2.3). Runs what needs the Activity: the passkey
 * ceremonies (Credential Manager), the SAF "save key file" picker, the clipboard and navigation.
 */
@Composable
fun IdentitySetupRoute(viewModel: IdentitySetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bunkerState by viewModel.bunkerState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    // App-level host first: "Schlüsseldatei gespeichert" must survive the reset to the shell.
    SnackbarEffect(viewModel.snackbars, appHost ?: localHost)

    val header by rememberUpdatedState(stringResource(L10nR.string.idSetupSecureFileHeader))
    val saveKey = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.onAction(SetupAction.KeyFilePicked(uri?.toString(), header))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SetupEffect.Back -> navigator.back()
                SetupEffect.GoHome -> navigator.resetTo(TopLevelDestination.start)
                is SetupEffect.CopyPlain -> SecretClipboard.copyPlain(context, effect.text)
                is SetupEffect.CreateKeyFile -> try {
                    saveKey.launch(effect.fileName)
                } catch (e: android.content.ActivityNotFoundException) {
                    viewModel.onAction(SetupAction.KeyFilePicked(uri = "", header = header))
                }
            }
        }
    }

    // Passkey ceremony in the UI scope; the ViewModel does the slow, Activity-free scrypt part.
    val pending = state.pendingPasskey
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val activity = context.findActivity()
        val result = try {
            if (activity == null) throw PasskeyPrfException(PasskeyPrfException.UNHANDLED)
            Result.success(
                when (pending) {
                    is PasskeyRequest.Register ->
                        viewModel.passkeys.register(activity, pending.registration.userIdHex, pending.registration.userName)
                    is PasskeyRequest.Unlock -> viewModel.passkeys.unlock(activity, pending.credentialIds)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        viewModel.onAction(SetupAction.PasskeyDone(pending, result))
    }

    IdentitySetupScreen(
        state = state,
        onAction = viewModel::onAction,
        bunkerState = bunkerState,
        snackbarHostState = if (appHost == null) localHost else null,
    )
}
