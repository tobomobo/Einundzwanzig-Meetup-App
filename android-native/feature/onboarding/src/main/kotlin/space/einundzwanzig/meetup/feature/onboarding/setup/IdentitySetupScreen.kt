package space.einundzwanzig.meetup.feature.onboarding.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.onboarding.R
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerConnectSheet
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerSheetState
import space.einundzwanzig.meetup.feature.onboarding.common.GradientButton
import space.einundzwanzig.meetup.feature.onboarding.common.OnboardingBackground
import space.einundzwanzig.meetup.feature.onboarding.common.UiText

/**
 * Identity setup wizard (spec 01 §2.3): transparent top bar over the cross-faded background,
 * one animated step at a time, the whole list blocked while busy or bootstrapping.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IdentitySetupScreen(
    state: IdentitySetupUiState,
    onAction: (SetupAction) -> Unit,
    bunkerState: BunkerSheetState = BunkerSheetState(),
    snackbarHostState: SnackbarHostState? = null,
) {
    // System back behaves like the leading button (spec 01 §2.3.2); on `choose` the default pop.
    BackHandler(enabled = state.step != SetupStep.CHOOSE || state.busy) { onAction(SetupAction.Back) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { snackbarHostState?.let { AppSnackbarHost(it) } },
    ) { _ ->
        OnboardingBackground(subtle = state.step.subtle && !state.bootstrapping) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        AnimatedContent(targetState = state.step.titleRes, label = "setupTitle") { res ->
                            Text(stringResource(res), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        if (state.step == SetupStep.CHOOSE) {
                            IconButton(onClick = { onAction(SetupAction.Close) }, enabled = !state.busy, modifier = Modifier.testTag(SetupTags.CLOSE)) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(L10nR.string.actionClose))
                            }
                        } else {
                            IconButton(onClick = { onAction(SetupAction.Back) }, enabled = !state.busy, modifier = Modifier.testTag(SetupTags.BACK)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(L10nR.string.actionBack))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            // Edge-to-edge: keep the last control (e.g. "Später") above a 3-button nav bar.
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .verticalScroll(rememberScrollState())
                            .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 32.dp)
                            .then(if (state.inputBlocked) Modifier.absorbInput() else Modifier)
                            .testTag(SetupTags.CONTENT),
                    ) {
                        if (state.bootstrapping) {
                            Bootstrapping()
                        } else {
                            // Keep the last error while the box collapses, so it shrinks instead of vanishing.
                            var lastError by remember { mutableStateOf<UiText?>(null) }
                            if (state.error != null) lastError = state.error
                            AnimatedVisibility(
                                visible = state.error != null,
                                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                                    fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                            ) {
                                Column {
                                    lastError?.let { ErrorBox(it) }
                                    VSpace(16.dp)
                                }
                            }
                            AnimatedContent(
                                targetState = state.step,
                                transitionSpec = {
                                    val forward = targetState.ordinal >= initialState.ordinal
                                    val spec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 380f)
                                    (slideInHorizontally(spec) { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                                        (slideOutHorizontally(spec) { if (forward) -it / 4 else it / 4 } + fadeOut())
                                },
                                label = "setupStep",
                            ) { step ->
                                Column(Modifier.widthIn(max = 560.dp)) {
                                    when (step) {
                                        SetupStep.CHOOSE -> ChooseStep(state, onAction)
                                        SetupStep.NEW -> NewStep(state, onAction)
                                        SetupStep.RESUME -> ResumeStep(state, onAction)
                                        SetupStep.PASSKEY -> PasskeyStep(state, onAction)
                                        SetupStep.EXISTING -> ExistingStep(state, onAction)
                                        SetupStep.EXISTING_MORE -> ExistingMoreStep(state, onAction)
                                        SetupStep.NAME_ONLY -> NameOnlyStep(state, onAction)
                                        SetupStep.MEETUP -> MeetupStep(state, onAction)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.secureSheet?.let { SecureIdentitySheet(it, onAction) }
    if (state.showBunkerSheet) BunkerConnectSheet(bunkerState) { onAction(SetupAction.Bunker(it)) }
    if (state.showMeetupPicker) {
        MeetupPickerSheet(
            meetups = state.meetups,
            initialSelected = state.selectedMeetups,
            onDone = { onAction(SetupAction.MeetupsPicked(it)) },
            onDismiss = { onAction(SetupAction.DismissMeetupPicker) },
        )
    }
    if (state.confirmOverwrite) {
        ConfirmDialog(
            title = stringResource(R.string.native_onboarding_overwrite_title),
            text = stringResource(R.string.native_onboarding_overwrite_body),
            confirmLabel = stringResource(R.string.native_onboarding_overwrite_confirm),
            onConfirm = { onAction(SetupAction.ConfirmOverwrite) },
            onDismiss = { onAction(SetupAction.DismissOverwrite) },
            destructive = true,
        )
    }
}

/** Flutter `AbsorbPointer`: swallow every touch on the list. */
private fun Modifier.absorbInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Bootstrapping() {
    Column(Modifier.fillMaxWidth().testTag(SetupTags.BOOTSTRAP)) {
        LinearWavyProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        VSpace(48.dp)
        Text(
            stringResource(L10nR.string.idSetupSubtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ChooseStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    Hint(stringResource(L10nR.string.idSetupSubtitle))
    VSpace(24.dp)
    if (state.canResume) {
        SetupCard(
            icon = Icons.Rounded.Restore,
            title = stringResource(L10nR.string.idSetupResumeCard),
            subtitle = stringResource(L10nR.string.idSetupResumeCardSub),
            onClick = { onAction(SetupAction.PickResume) },
            primary = true,
            modifier = Modifier.testTag(SetupTags.CARD_RESUME),
        )
        VSpace(12.dp)
    }
    SetupCard(
        icon = Icons.Rounded.PersonAddAlt1,
        title = stringResource(L10nR.string.idSetupNewCard),
        subtitle = stringResource(L10nR.string.idSetupNewCardSub),
        onClick = { onAction(SetupAction.PickNew) },
        primary = !state.canResume,
        modifier = Modifier.testTag(SetupTags.CARD_NEW),
    )
    VSpace(12.dp)
    SetupCard(
        icon = Icons.Rounded.Link,
        title = stringResource(L10nR.string.idSetupExistingCard),
        subtitle = stringResource(L10nR.string.idSetupExistingCardSub),
        onClick = { onAction(SetupAction.PickExisting) },
        primary = false,
        modifier = Modifier.testTag(SetupTags.CARD_EXISTING),
    )
    if (state.offerReviewSignIn) {
        VSpace(28.dp)
        AppReviewSignInBox(state, onAction)
    }
}

@Composable
private fun NewStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    Hint(stringResource(L10nR.string.idSetupNewHint))
    VSpace(20.dp)
    SetupField(
        value = state.name,
        onValueChange = { onAction(SetupAction.NameChanged(it)) },
        label = stringResource(L10nR.string.idSetupNameLabel),
        icon = Icons.Rounded.Badge,
        modifier = Modifier.testTag(SetupTags.NAME),
    )
    VSpace(12.dp)
    SetupField(
        value = state.pass,
        onValueChange = { onAction(SetupAction.PassChanged(it)) },
        label = stringResource(L10nR.string.idSetupPasswordLabel),
        icon = Icons.Rounded.Lock,
        password = true,
        revealed = PasswordField.PASS in state.revealed,
        onToggleReveal = { onAction(SetupAction.ToggleReveal(PasswordField.PASS)) },
        borderColor = passwordBorder(state.passLengthOk, state.pass.isNotEmpty()),
        modifier = Modifier.testTag(SetupTags.PASS),
    )
    VSpace(12.dp)
    SetupField(
        value = state.pass2,
        onValueChange = { onAction(SetupAction.Pass2Changed(it)) },
        label = stringResource(L10nR.string.idSetupPasswordConfirmLabel),
        icon = Icons.Rounded.Lock,
        password = true,
        revealed = PasswordField.PASS2 in state.revealed,
        onToggleReveal = { onAction(SetupAction.ToggleReveal(PasswordField.PASS2)) },
        borderColor = passwordBorder(state.passMatch, state.pass2.isNotEmpty()),
        modifier = Modifier.testTag(SetupTags.PASS2),
    )
    VSpace(4.dp)
    RuleRow(state.passLengthOk, stringResource(L10nR.string.backupPwRuleLength, LocalEasyAuth.MIN_PASSWORD_LENGTH))
    RuleRow(state.passMatch, stringResource(L10nR.string.backupPwRuleMatch))
    VSpace(10.dp)
    Row {
        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(L10nR.string.idSetupPasswordWarn), fontSize = 12.sp, lineHeight = 17.sp)
    }
    VSpace(24.dp)
    GradientButton(
        label = stringResource(L10nR.string.idSetupCreate),
        onClick = { onAction(SetupAction.Register) },
        enabled = state.pwOk,
        busy = state.busy,
        modifier = Modifier.testTag(SetupTags.CREATE),
    )
}

@Composable
private fun ResumeStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    val passkeyPath = !state.hasLocalKey && state.hasPasskeyWrap && !state.resumeUsePassword
    Hint(
        stringResource(
            when {
                state.hasLocalKey -> L10nR.string.idSetupResumeHasKey
                passkeyPath -> L10nR.string.idSetupResumePasskeyHint
                else -> L10nR.string.idSetupResumePasswordHint
            },
        ),
    )
    VSpace(24.dp)
    when {
        state.hasLocalKey -> GradientButton(
            label = stringResource(L10nR.string.idSetupResumeContinue),
            onClick = { onAction(SetupAction.Resume) },
            busy = state.busy,
            modifier = Modifier.testTag(SetupTags.RESUME_CONTINUE),
        )
        passkeyPath -> {
            GradientButton(
                label = stringResource(L10nR.string.idSetupResumePasskey),
                onClick = { onAction(SetupAction.ResumeWithPasskey) },
                busy = state.busy,
                modifier = Modifier.testTag(SetupTags.RESUME_PASSKEY),
            )
            if (state.hasPasswordWrap) {
                VSpace(8.dp)
                TextButton(
                    onClick = { onAction(SetupAction.UsePasswordInstead) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.RESUME_USE_PASSWORD),
                ) {
                    Text(stringResource(L10nR.string.idSetupResumePassword))
                }
            }
        }
        else -> {
            // No rule checks: an existing password may be shorter.
            SetupField(
                value = state.resumePass,
                onValueChange = { onAction(SetupAction.ResumePassChanged(it)) },
                label = stringResource(L10nR.string.idSetupPasswordLabel),
                icon = Icons.Rounded.Lock,
                password = true,
                revealed = PasswordField.RESUME_PASS in state.revealed,
                onToggleReveal = { onAction(SetupAction.ToggleReveal(PasswordField.RESUME_PASS)) },
                modifier = Modifier.testTag(SetupTags.RESUME_PASS),
            )
            VSpace(16.dp)
            GradientButton(
                label = stringResource(L10nR.string.idSetupResumeContinue),
                onClick = { onAction(SetupAction.Resume) },
                busy = state.busy,
                modifier = Modifier.testTag(SetupTags.RESUME_CONTINUE),
            )
        }
    }
}

@Composable
private fun PasskeyStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    Hint(stringResource(L10nR.string.idSetupPasskeyBody))
    VSpace(24.dp)
    GradientButton(
        label = stringResource(L10nR.string.idSetupPasskeyAction),
        onClick = { onAction(SetupAction.AddPasskey) },
        busy = state.busy,
        modifier = Modifier.testTag(SetupTags.PASSKEY_ADD),
    )
    VSpace(8.dp)
    TextButton(
        onClick = { onAction(SetupAction.PasskeyLater) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.PASSKEY_LATER),
    ) {
        Text(stringResource(L10nR.string.idSetupPasskeyLater), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExistingStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    when (state.path) {
        RecommendedExistingPath.NIP07 -> SetupCard(
            icon = Icons.Rounded.Extension,
            title = stringResource(L10nR.string.idSetupPrimaryNip07),
            subtitle = stringResource(L10nR.string.idSetupPrimaryNip07Sub),
            onClick = { onAction(SetupAction.ConnectPrimary) },
            primary = true,
            modifier = Modifier.testTag(SetupTags.CARD_PRIMARY),
        )
        RecommendedExistingPath.AMBER -> SetupCard(
            icon = Icons.Rounded.PhoneAndroid,
            title = stringResource(L10nR.string.idSetupPrimaryAmber),
            subtitle = stringResource(L10nR.string.idSetupPrimaryAmberSub),
            onClick = { onAction(SetupAction.ConnectPrimary) },
            primary = true,
            modifier = Modifier.testTag(SetupTags.CARD_PRIMARY),
        )
        RecommendedExistingPath.BUNKER -> SetupCard(
            icon = Icons.Rounded.VpnKey,
            title = stringResource(L10nR.string.idSetupPrimaryBunker),
            subtitle = stringResource(L10nR.string.idSetupPrimaryBunkerSub),
            onClick = { onAction(SetupAction.ConnectPrimary) },
            primary = true,
            modifier = Modifier.testTag(SetupTags.CARD_PRIMARY),
        )
    }
    VSpace(12.dp)
    SetupCard(
        icon = Icons.AutoMirrored.Rounded.AltRoute,
        title = stringResource(L10nR.string.idSetupOtherWay),
        subtitle = stringResource(L10nR.string.idSetupOtherWaySub),
        onClick = { onAction(SetupAction.OtherWay) },
        primary = false,
        modifier = Modifier.testTag(SetupTags.CARD_OTHER_WAY),
    )
}

@Composable
private fun ExistingMoreStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    Hint(stringResource(L10nR.string.idSetupImportHint))
    VSpace(12.dp)
    SetupField(
        value = state.importKey,
        onValueChange = { onAction(SetupAction.ImportChanged(it)) },
        label = stringResource(L10nR.string.idSetupImportLabel),
        icon = Icons.Rounded.Key,
        maxLines = 3,
        autocorrect = false,
        modifier = Modifier.testTag(SetupTags.IMPORT_KEY),
    )
    VSpace(12.dp)
    SetupField(
        value = state.importPass,
        onValueChange = { onAction(SetupAction.ImportPassChanged(it)) },
        label = stringResource(L10nR.string.idSetupImportPasswordLabel),
        icon = Icons.Rounded.Lock,
        password = true,
        revealed = PasswordField.IMPORT_PASS in state.revealed,
        onToggleReveal = { onAction(SetupAction.ToggleReveal(PasswordField.IMPORT_PASS)) },
        modifier = Modifier.testTag(SetupTags.IMPORT_PASS),
    )
    VSpace(16.dp)
    GradientButton(
        label = stringResource(L10nR.string.idSetupImportAction),
        onClick = { onAction(SetupAction.ImportKey) },
        busy = state.busy,
        modifier = Modifier.testTag(SetupTags.IMPORT_ACTION),
    )
    VSpace(20.dp)
    if (state.path != RecommendedExistingPath.BUNKER) {
        SetupCard(
            icon = Icons.Rounded.VpnKey,
            title = stringResource(L10nR.string.idSetupPrimaryBunker),
            subtitle = stringResource(L10nR.string.idSetupPrimaryBunkerSub),
            onClick = { onAction(SetupAction.ConnectBunker) },
            primary = false,
            modifier = Modifier.testTag(SetupTags.CARD_BUNKER),
        )
        VSpace(12.dp)
    }
    if (state.path != RecommendedExistingPath.AMBER) {
        SetupCard(
            icon = Icons.Rounded.PhoneAndroid,
            title = stringResource(L10nR.string.idSetupPrimaryAmber),
            subtitle = stringResource(L10nR.string.idSetupPrimaryAmberSub),
            onClick = { onAction(SetupAction.ConnectAmber) },
            primary = false,
            modifier = Modifier.testTag(SetupTags.CARD_AMBER),
        )
        VSpace(12.dp)
    }
    TextButton(
        onClick = { onAction(SetupAction.RestoreBackup) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.RESTORE),
    ) {
        Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(L10nR.string.introLoadBackup), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NameOnlyStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    Hint(stringResource(L10nR.string.idSetupNameOnlyHint))
    VSpace(20.dp)
    SetupField(
        value = state.name,
        onValueChange = { onAction(SetupAction.NameChanged(it)) },
        label = stringResource(L10nR.string.idSetupNameLabel),
        icon = Icons.Rounded.Badge,
        modifier = Modifier.testTag(SetupTags.NAME),
    )
    VSpace(24.dp)
    GradientButton(
        label = stringResource(L10nR.string.idSetupContinue),
        onClick = { onAction(SetupAction.SaveName) },
        busy = state.busy,
        modifier = Modifier.testTag(SetupTags.SAVE_NAME),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MeetupStep(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    Hint(stringResource(L10nR.string.idSetupMeetupHint))
    VSpace(20.dp)
    when {
        state.meetupsLoading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(SetupTags.MEETUP_LOADING)) {
            AppLoadingIndicator(size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(L10nR.string.idSetupMeetupLoading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.meetups.isEmpty() -> MeetupLaterButton(onAction)
        else -> {
            SetupCard(
                icon = Icons.Rounded.Place,
                title = stringResource(L10nR.string.idSetupMeetupPick),
                subtitle = if (state.selectedMeetups.isEmpty()) {
                    stringResource(L10nR.string.homeMeetupChooseSub)
                } else {
                    MeetupPickerLogic.label(state.selectedMeetups, state.meetups)
                },
                onClick = { onAction(SetupAction.OpenMeetupPicker) },
                primary = true,
                modifier = Modifier.testTag(SetupTags.MEETUP_PICK),
            )
            VSpace(24.dp)
            if (state.selectedMeetups.isNotEmpty()) {
                GradientButton(
                    label = stringResource(L10nR.string.idSetupMeetupContinue),
                    onClick = { onAction(SetupAction.SaveMeetup) },
                    busy = state.busy,
                    modifier = Modifier.testTag(SetupTags.MEETUP_SAVE),
                )
            }
            MeetupLaterButton(onAction)
        }
    }
}

@Composable
private fun MeetupLaterButton(onAction: (SetupAction) -> Unit) {
    TextButton(
        onClick = { onAction(SetupAction.MeetupLater) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.MEETUP_LATER),
    ) {
        Text(stringResource(L10nR.string.idSetupMeetupLater), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * App-review demo login (spec 01 §2.6). Hard-coded English by design (reviewers), only rendered
 * when the build offers it (`BuildConfig.DEBUG || APP_REVIEW_SIGNIN`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppReviewSignInBox(state: IdentitySetupUiState, onAction: (SetupAction) -> Unit) {
    val tertiary = space.einundzwanzig.meetup.feature.onboarding.common.onboardingTertiary()
    Column(Modifier.fillMaxWidth().testTag(SetupTags.REVIEW_BOX), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("App Review", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = tertiary)
        VSpace(6.dp)
        Text("Sign in", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        VSpace(10.dp)
        SetupField(
            value = state.reviewUser,
            onValueChange = { onAction(SetupAction.ReviewUserChanged(it)) },
            label = "User Name",
            icon = Icons.Rounded.Badge,
            autocorrect = false,
            modifier = Modifier.testTag(SetupTags.REVIEW_USER),
        )
        VSpace(8.dp)
        SetupField(
            value = state.reviewPass,
            onValueChange = { onAction(SetupAction.ReviewPassChanged(it)) },
            label = "Password",
            icon = Icons.Rounded.Lock,
            password = true,
            revealed = PasswordField.REVIEW_PASS in state.revealed,
            onToggleReveal = { onAction(SetupAction.ToggleReveal(PasswordField.REVIEW_PASS)) },
            modifier = Modifier.testTag(SetupTags.REVIEW_PASS),
        )
        state.reviewError?.let {
            VSpace(8.dp)
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        VSpace(12.dp)
        OutlinedButton(
            onClick = { onAction(SetupAction.ReviewSignIn) },
            enabled = !state.reviewBusy,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag(SetupTags.REVIEW_SUBMIT),
        ) {
            if (state.reviewBusy) {
                AppLoadingIndicator(size = 24.dp)
            } else {
                Text("Sign in", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Test tags of the identity setup. */
object SetupTags {
    const val CONTENT = "setup_content"
    const val BOOTSTRAP = "setup_bootstrap"
    const val ERROR = "setup_error"
    const val CLOSE = "setup_close"
    const val BACK = "setup_back"
    const val CARD_RESUME = "setup_card_resume"
    const val CARD_NEW = "setup_card_new"
    const val CARD_EXISTING = "setup_card_existing"
    const val CARD_PRIMARY = "setup_card_primary"
    const val CARD_OTHER_WAY = "setup_card_other_way"
    const val CARD_BUNKER = "setup_card_bunker"
    const val CARD_AMBER = "setup_card_amber"
    const val NAME = "setup_name"
    const val PASS = "setup_pass"
    const val PASS2 = "setup_pass2"
    const val CREATE = "setup_create"
    const val RESUME_CONTINUE = "setup_resume_continue"
    const val RESUME_PASSKEY = "setup_resume_passkey"
    const val RESUME_USE_PASSWORD = "setup_resume_use_password"
    const val RESUME_PASS = "setup_resume_pass"
    const val PASSKEY_ADD = "setup_passkey_add"
    const val PASSKEY_LATER = "setup_passkey_later"
    const val IMPORT_KEY = "setup_import_key"
    const val IMPORT_PASS = "setup_import_pass"
    const val IMPORT_ACTION = "setup_import_action"
    const val RESTORE = "setup_restore"
    const val SAVE_NAME = "setup_save_name"
    const val MEETUP_LOADING = "setup_meetup_loading"
    const val MEETUP_PICK = "setup_meetup_pick"
    const val MEETUP_SAVE = "setup_meetup_save"
    const val MEETUP_LATER = "setup_meetup_later"
    const val MEETUP_SEARCH = "setup_meetup_search"
    const val MEETUP_DONE = "setup_meetup_done"
    const val SECURE_SHEET = "setup_secure_sheet"
    const val SECURE_PRIMARY = "setup_secure_primary"
    const val SECURE_COPY = "setup_secure_copy"
    const val SECURE_SKIP = "setup_secure_skip"
    const val REVIEW_BOX = "setup_review_box"
    const val REVIEW_USER = "setup_review_user"
    const val REVIEW_PASS = "setup_review_pass"
    const val REVIEW_SUBMIT = "setup_review_submit"
    fun meetupRow(id: String) = "setup_meetup_row_$id"
}

@Preview
@Composable
private fun SetupChoosePreview() {
    EinundzwanzigTheme {
        IdentitySetupScreen(IdentitySetupUiState(bootstrapping = false, hasLocalKey = true, offerReviewSignIn = true), onAction = {})
    }
}

@Preview
@Composable
private fun SetupNewPreview() {
    EinundzwanzigTheme {
        IdentitySetupScreen(
            IdentitySetupUiState(
                bootstrapping = false,
                step = SetupStep.NEW,
                name = "Satoshi",
                pass = "hunter22",
                pass2 = "hunter2",
                error = UiText.Raw("Passwort muss mindestens 8 Zeichen haben."),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun SetupMeetupPreview() {
    EinundzwanzigTheme {
        IdentitySetupScreen(
            IdentitySetupUiState(
                bootstrapping = false,
                step = SetupStep.MEETUP,
                meetups = listOf(Meetup(id = "1", name = "Einundzwanzig Wien", city = "Wien", country = "AT", telegramLink = "", lat = 0.0, lng = 0.0)),
                selectedMeetups = listOf("1"),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun SetupBootstrapPreview() {
    EinundzwanzigTheme { IdentitySetupScreen(IdentitySetupUiState(), onAction = {}) }
}
