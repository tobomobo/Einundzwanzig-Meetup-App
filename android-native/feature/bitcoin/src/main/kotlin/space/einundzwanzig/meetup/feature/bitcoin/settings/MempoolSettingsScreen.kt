package space.einundzwanzig.meetup.feature.bitcoin.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.mempool.HostTestFailure
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolMode
import space.einundzwanzig.meetup.feature.bitcoin.ui.BitcoinLabels
import space.einundzwanzig.meetup.feature.bitcoin.ui.HintCard
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the Mempool settings. */
internal object MempoolTags {
    const val CUSTOM_FIELD = "mempool_custom_field"
    const val TEST_RESULT = "mempool_test_result"
    const val TOR_HINT = "mempool_tor_hint"
    const val ACTIVE = "mempool_active"
    fun option(mode: MempoolMode) = "mempool_option_${mode.name}"
}

/** Flutter SnackBar default display time (4 s). */
private const val SAVED_SNACKBAR_MILLIS = 4_000L

/** Stateful Mempool settings entry (spec 13 §2.3). */
@Composable
fun MempoolSettingsRoute(viewModel: MempoolSettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(L10nR.string.mempoolSaved)
    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                MempoolSettingsEvent.Saved -> snackbar.showAppSnackbar(saved, durationMillis = SAVED_SNACKBAR_MILLIS)
            }
        }
    }
    MempoolSettingsScreen(state, snackbar) { action ->
        if (action == MempoolSettingsAction.Back) navigator.back() else viewModel.onAction(action)
    }
}

/** Text of a connection-test result (spec 13 §2.3 `test()`). */
@Composable
internal fun testResultText(result: MempoolTestResult): String = when (result) {
    is MempoolTestResult.Ok -> "${stringResource(L10nR.string.mempoolTestOk)} · Block ${result.height}"
    MempoolTestResult.InvalidUrl -> stringResource(L10nR.string.mempoolInvalidUrl)
    is MempoolTestResult.Failed -> when (val f = result.failure) {
        is HostTestFailure.Blocked -> "${stringResource(L10nR.string.mempoolTestBlocked)} (HTTP ${f.statusCode})"
        is HostTestFailure.Http -> "${stringResource(L10nR.string.mempoolTestFail)} — HTTP ${f.statusCode}"
        HostTestFailure.OnionUnreachable -> stringResource(L10nR.string.mempoolTestOnionFail)
        is HostTestFailure.Other -> "${stringResource(L10nR.string.mempoolTestFail)} — ${f.typeName}"
    }
}

/** Stateless Mempool settings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MempoolSettingsScreen(
    state: MempoolSettingsUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (MempoolSettingsAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.mempoolTitle),
                onBack = { onAction(MempoolSettingsAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 40.dp),
        ) {
            Text(
                stringResource(L10nR.string.mempoolIntro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.85.sp,
            )
            Spacer(Modifier.height(20.dp))
            ModeOptions(state.mode) { onAction(MempoolSettingsAction.Select(it)) }
            AnimatedVisibility(state.mode == MempoolMode.TOR, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(2.dp))
                    HintCard(
                        text = stringResource(L10nR.string.mempoolTorHint),
                        icon = Icons.Outlined.Info,
                        accent = MaterialTheme.extendedColors.nostr,
                        modifier = Modifier.testTag(MempoolTags.TOR_HINT),
                    )
                }
            }
            AnimatedVisibility(state.mode == MempoolMode.CUSTOM, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                CustomUrl(state, onAction)
            }
            Spacer(Modifier.height(22.dp))
            OutlinedButton(
                onClick = { onAction(MempoolSettingsAction.Test) },
                enabled = !state.testing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                if (state.testing) {
                    AppLoadingIndicator(size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(L10nR.string.mempoolTesting))
                } else {
                    Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(L10nR.string.mempoolTest))
                }
            }
            val result = state.testResult
            AnimatedVisibility(result != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (result != null) {
                    val ok = result is MempoolTestResult.Ok
                    Column {
                        Spacer(Modifier.height(12.dp))
                        HintCard(
                            text = testResultText(result),
                            icon = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            accent = if (ok) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(MempoolTags.TEST_RESULT),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            MonoText(
                text = "${stringResource(L10nR.string.mempoolActive)}:\n${state.activeHost}",
                modifier = Modifier.testTag(MempoolTags.ACTIVE),
                color = MaterialTheme.extendedColors.textTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ModeOptions(selected: MempoolMode, onSelect: (MempoolMode) -> Unit) {
    val ext = MaterialTheme.extendedColors
    Column(Modifier.selectableGroup()) {
        ModeCard(
            mode = MempoolMode.CLEARNET,
            selected = selected == MempoolMode.CLEARNET,
            icon = Icons.Rounded.Public,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(L10nR.string.mempoolClearnetTitle),
            subtitle = BitcoinLabels.CLEARNET_SUBTITLE,
            onSelect = onSelect,
        )
        ModeCard(
            mode = MempoolMode.TOR,
            selected = selected == MempoolMode.TOR,
            icon = Icons.Rounded.Shield,
            accent = ext.nostr,
            title = stringResource(L10nR.string.mempoolTorTitle),
            subtitle = stringResource(L10nR.string.mempoolTorSub),
            onSelect = onSelect,
        )
        ModeCard(
            mode = MempoolMode.CUSTOM,
            selected = selected == MempoolMode.CUSTOM,
            icon = Icons.Rounded.Dns,
            accent = ext.success,
            title = stringResource(L10nR.string.mempoolCustomTitle),
            subtitle = stringResource(L10nR.string.mempoolCustomSub),
            onSelect = onSelect,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeCard(
    mode: MempoolMode,
    selected: Boolean,
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onSelect: (MempoolMode) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .testTag(MempoolTags.option(mode))
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(if (selected) 1.4.dp else 0.5.dp, if (selected) primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(mode) })
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(accent.copy(alpha = 0.12f), if (selected) MaterialShapes.Cookie9Sided.toShape() else CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) primary else MaterialTheme.extendedColors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CustomUrl(state: MempoolSettingsUiState, onAction: (MempoolSettingsAction) -> Unit) {
    var field by remember(state.customRevision) {
        mutableStateOf(TextFieldValue(state.customText, TextRange(state.customText.length)))
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Like Flutter's onSubmitted: close the keyboard so the `mempoolSaved` snackbar is visible.
    val save = {
        keyboard?.hide()
        focus.clearFocus()
        onAction(MempoolSettingsAction.SaveCustom)
    }
    Column {
        Spacer(Modifier.height(4.dp))
        TextField(
            value = field,
            onValueChange = {
                val changed = it.text != field.text
                field = it
                if (changed) onAction(MempoolSettingsAction.CustomChanged(it.text))
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MempoolTags.CUSTOM_FIELD),
            singleLine = true,
            label = { Text(stringResource(L10nR.string.mempoolCustomTitle)) },
            placeholder = { Text(BitcoinLabels.CUSTOM_URL_HINT, color = MaterialTheme.extendedColors.textTertiary) },
            leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = save,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(L10nR.string.mempoolSave), fontWeight = FontWeight.Bold)
        }
    }
}

@Preview(name = "Mempool settings custom", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 1000)
@Composable
private fun MempoolSettingsPreview() {
    EinundzwanzigTheme {
        MempoolSettingsScreen(
            MempoolSettingsUiState(
                mode = MempoolMode.CUSTOM,
                customText = "https://umbrel.local:3006",
                testResult = MempoolTestResult.Ok(912_345),
                activeHost = "https://umbrel.local:3006",
            ),
            remember { SnackbarHostState() },
        ) {}
    }
}

@Preview(name = "Mempool settings Tor", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun MempoolSettingsTorPreview() {
    EinundzwanzigTheme {
        MempoolSettingsScreen(
            MempoolSettingsUiState(
                mode = MempoolMode.TOR,
                testing = true,
                activeHost = MempoolHosts.TOR,
            ),
            remember { SnackbarHostState() },
        ) {}
    }
}
