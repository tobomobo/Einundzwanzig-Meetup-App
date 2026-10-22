package space.einundzwanzig.meetup.feature.profile.relay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.profile.R
import space.einundzwanzig.meetup.feature.profile.common.SnackbarEffect

/** Stateful entry of the `RelaySettings` destination (spec 07 §2.1). */
@Composable
fun RelaySettingsRoute(viewModel: RelaySettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(viewModel.snackbars, snackbarHostState)
    RelaySettingsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            if (action == RelaySettingsAction.Back) navigator.back() else viewModel.onAction(action)
        },
    )
}

/** Stateless "NOSTR-RELAYS" screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    state: RelaySettingsUiState,
    onAction: (RelaySettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.rsTitle),
                onBack = { onAction(RelaySettingsAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        AnimatedContent(targetState = state.isLoading, label = "relayLoading") { loading ->
            if (loading) {
                FullScreenLoading(Modifier.padding(padding))
            } else {
                RelayList(state, onAction, PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + Dimens.ScreenPadding,
                ))
            }
        }
    }
    if (state.addDialogOpen) {
        AddRelayDialog(
            testing = state.isTestingRelay,
            onAdd = { onAction(RelaySettingsAction.AddRelay(it)) },
            onDismiss = { onAction(RelaySettingsAction.DismissAddDialog) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayList(state: RelaySettingsUiState, onAction: (RelaySettingsAction) -> Unit, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(RelaySettingsTestTags.LIST),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "intro") { IntroCard() }
        item(key = "defaultsLabel") {
            SectionLabel(stringResource(L10nR.string.rsDefaultRelays), Modifier.padding(top = 16.dp))
        }
        items(state.defaultRelays, key = { "d:" + it.url }) { row ->
            RelayTile(url = row.url, enabled = row.enabled) {
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { onAction(RelaySettingsAction.ToggleDefault(row.url, it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.extendedColors.onSuccess,
                        checkedTrackColor = MaterialTheme.extendedColors.success,
                    ),
                    modifier = Modifier.testTag(RelaySettingsTestTags.switch(row.url)),
                )
            }
        }
        item(key = "customHeader") {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(stringResource(L10nR.string.rsCustomRelays), Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { onAction(RelaySettingsAction.OpenAddDialog) },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(L10nR.string.rsAdd), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
        if (state.customRelays.isEmpty()) {
            item(key = "customEmpty") {
                Text(
                    stringResource(L10nR.string.rsNoCustomRelays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendedColors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                )
            }
        } else {
            items(state.customRelays, key = { "c:$it" }) { url ->
                RelayTile(url = url, enabled = true, modifier = Modifier.animateItem()) {
                    // Spec 07 §2.1 item 7: tooltip "Entfernen".
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.native_relay_remove)) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = { onAction(RelaySettingsAction.RemoveCustom(url)) }) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.native_relay_remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        item(key = "summary") { SummaryBox(state.activeCount, Modifier.padding(top = 22.dp)) }
    }
}

@Composable
private fun IntroCard() {
    val gold = MaterialTheme.extendedColors.community
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, gold.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Hub, contentDescription = null, tint = gold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(L10nR.string.rsRelaysIntro) + stringResource(L10nR.string.rsAllRelaysInfo),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun RelayTile(
    url: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val border by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        label = "relayBorder",
    )
    val stateText = stringResource(if (enabled) R.string.native_relay_enabled else R.string.native_relay_disabled)
    Surface(
        shape = RoundedCornerShape(Dimens.TileRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neutral status icon: it says "enabled", not "reachable" (spec 07 §1 item 2).
            Icon(
                if (enabled) Icons.Rounded.CloudQueue else Icons.Rounded.CloudOff,
                contentDescription = stateText,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.extendedColors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                url,
                style = MonoStyle.copy(fontSize = 12.sp),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.extendedColors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

@Composable
private fun SummaryBox(count: Int, modifier: Modifier = Modifier) {
    val ok = count > 0
    val accent = if (ok) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth().animateContentSize().testTag(RelaySettingsTestTags.SUMMARY),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (ok) pluralStringResource(R.plurals.native_relays_active, count, count) else stringResource(L10nR.string.rsNoRelaysActive),
                color = accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddRelayDialog(testing: Boolean, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue("wss://", selection = TextRange(6)))
    }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(L10nR.string.rsAddRelay), fontWeight = FontWeight.Bold) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MonoStyle.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                placeholder = { Text(stringResource(L10nR.string.rsRelayPlaceholder), style = MonoStyle) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(RelaySettingsTestTags.INPUT),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(L10nR.string.apCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(value.text) },
                enabled = !testing,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 48.dp).testTag(RelaySettingsTestTags.ADD_CONFIRM),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (testing) {
                        AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(L10nR.string.rsAdd), fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    )
}

/** Test tags of the relay screen. */
object RelaySettingsTestTags {
    const val LIST = "relay_list"
    const val SUMMARY = "relay_summary"
    const val INPUT = "relay_input"
    const val ADD_CONFIRM = "relay_add_confirm"
    fun switch(url: String) = "relay_switch:$url"
}

@Preview(name = "RelaySettings", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun RelaySettingsPreview() {
    EinundzwanzigTheme {
        RelaySettingsScreen(
            state = RelaySettingsUiState(
                isLoading = false,
                defaultRelays = listOf(
                    RelayRow("wss://relay.damus.io", true),
                    RelayRow("wss://nos.lol", true),
                    RelayRow("wss://relay.nostr.band", false),
                    RelayRow("wss://relay.primal.net", true),
                ),
                customRelays = listOf("wss://mein-relay.de"),
                activeCount = 4,
            ),
            onAction = {},
        )
    }
}

@Preview(name = "RelaySettings – none active", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun RelaySettingsNonePreview() {
    EinundzwanzigTheme {
        RelaySettingsScreen(
            state = RelaySettingsUiState(
                isLoading = false,
                defaultRelays = listOf(RelayRow("wss://relay.damus.io", false)),
                activeCount = 0,
            ),
            onAction = {},
        )
    }
}
