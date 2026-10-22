package space.einundzwanzig.meetup.feature.settings.sheet

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.DiagnosticLog
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MempoolSettings
import space.einundzwanzig.meetup.core.navigation.ProfileEdit
import space.einundzwanzig.meetup.core.navigation.RelaySettings
import space.einundzwanzig.meetup.core.navigation.ValueForValue
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.GuideWindowHost
import space.einundzwanzig.meetup.core.ui.guide.LocalGuideController
import space.einundzwanzig.meetup.core.ui.guide.LocalGuideTargetRegistry
import space.einundzwanzig.meetup.core.ui.guide.StartTourEffect
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.guide.guideScrollContainer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.settings.locale.AppLocales
import space.einundzwanzig.meetup.feature.backup.rememberBackupLauncher
import space.einundzwanzig.meetup.feature.settings.R
import space.einundzwanzig.meetup.feature.settings.SettingsTour
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** User actions of the settings sheet (spec 12 §2.6). */
sealed interface SettingsSheetAction {
    data object OpenProfile : SettingsSheetAction
    data object CreateBackup : SettingsSheetAction
    data object RestoreBackup : SettingsSheetAction
    data object OpenRelays : SettingsSheetAction
    data object OpenMempool : SettingsSheetAction
    data class SelectLanguage(val code: String?) : SettingsSheetAction
    data class SelectCurrency(val code: String) : SettingsSheetAction
    data class SetHaptic(val enabled: Boolean) : SettingsSheetAction
    data class SetThemeMode(val mode: ThemeMode) : SettingsSheetAction
    data object OpenLog : SettingsSheetAction
    data object RestartTours : SettingsSheetAction
    data object OpenV4V : SettingsSheetAction
    data object ResetApp : SettingsSheetAction
}

/** Test tags of the settings sheet. */
object SettingsSheetTags {
    const val CONTENT = "settings_sheet"
    const val PROFILE = "settings_row_profile"
    const val BACKUP = "settings_row_backup"
    const val RESTORE = "settings_row_restore"
    const val RELAYS = "settings_row_relays"
    const val MEMPOOL = "settings_row_mempool"
    const val LANGUAGE = "settings_row_language"
    const val CURRENCY = "settings_row_currency"
    const val HAPTIC = "settings_row_haptic"
    const val THEME = "settings_row_theme"
    const val LOG = "settings_row_log"
    const val RESTART = "settings_row_restart"
    const val V4V = "settings_row_v4v"
    const val RESET = "settings_row_reset"
    const val VERSION = "settings_version"
    const val LANGUAGE_DIALOG = "settings_language_dialog"
    const val CURRENCY_DIALOG = "settings_currency_dialog"
    fun theme(mode: ThemeMode) = "settings_theme_${mode.name}"
    fun language(code: String?) = "settings_language_${code ?: "system"}"
    fun currency(code: String) = "settings_currency_$code"
}

/** Literal row title of the V4V entry (Flutter hard-codes `'V4V'`). */
private const val V4V_TITLE = "V4V"

/**
 * The settings bottom sheet opened by the gear icon on Home (spec 12 §2.6, F32–F40).
 *
 * Keep it in the composition of the hosting screen and toggle [visible]: the sheet closes before
 * it navigates (Flutter `pop` then `push`) and the "Tour wiederholen" snackbar is shown after it
 * has closed. Runs the settings tour (§2.9.1) 650 ms after the first opening; closing the sheet
 * ends the tour and marks it completed. A configuration change (language switch, rotation)
 * neither ends nor restarts a running tour; the host must keep [visible] across it
 * (`rememberSaveable` or ViewModel state).
 *
 * Sheet height: a fixed 92 % sheet without a partially expanded stop (Flutter: draggable 50–95 %,
 * opening at 85 %); dragging it down closes it. See the module README.
 *
 * @param onDismissRequest the sheet was closed (set [visible] to false).
 * @param onResetApp "App zurücksetzen": the caller runs the reset flow of spec 12 §2.5.12
 *   (the sheet is already closed).
 * @param onRestoreFinished called after every restore attempt, also an aborted one: reload all
 *   dashboard data (Flutter `_loadAll()`). Runs on the main thread from the application scope,
 *   so pass a ViewModel call rather than something bound to a composition.
 * @param snackbarHostState host for the `settingsGuideReset` snackbar (default: the app host).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onResetApp: () -> Unit,
    onRestoreFinished: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = LocalAppSnackbarHostState.current,
    viewModel: SettingsSheetViewModel = hiltViewModel(),
) {
    val navigator = LocalNavigator.current
    val backup = rememberBackupLauncher()
    val backupBusy by backup.busy.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toursReset = stringResource(L10nR.string.settingsGuideReset)
    val currentRestoreFinished by rememberUpdatedState(onRestoreFinished)
    val currentDismiss by rememberUpdatedState(onDismissRequest)

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                SettingsSheetEvent.ToursReset -> snackbarHostState?.showAppSnackbar(toursReset, tone = Tone.Warning)
            }
        }
    }

    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Remembered below the `visible` check, so every opening starts with a fresh `false`.
    var closing by remember { mutableStateOf(false) }

    // Flutter: Navigator.pop(sheet) first, then the action. Only the first tap counts (Flutter
    // removes the sheet at once). A hide animation interrupted by a touch still closes the sheet;
    // if the host itself left the composition (scope cancelled), nothing runs.
    fun closeThen(block: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheetState.hide()
            } catch (e: CancellationException) {
                if (!isActive) throw e
            }
            currentDismiss()
            block()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { SheetGrabber() },
    ) {
        GuideWindowHost(Modifier.fillMaxHeight(0.92f)) {
            if (LocalGuideTargetRegistry.current != null) {
                SettingsTourEffects()
            }
            SettingsSheetContent(state = state, backupBusy = backupBusy, enabled = !closing) { action ->
                when (action) {
                    SettingsSheetAction.OpenProfile -> closeThen { navigator.navigate(ProfileEdit) }
                    SettingsSheetAction.CreateBackup -> closeThen { backup.launchExport() }
                    SettingsSheetAction.RestoreBackup -> closeThen { backup.launchRestore { currentRestoreFinished() } }
                    SettingsSheetAction.OpenRelays -> closeThen { navigator.navigate(RelaySettings) }
                    SettingsSheetAction.OpenMempool -> closeThen { navigator.navigate(MempoolSettings) }
                    SettingsSheetAction.OpenLog -> closeThen { navigator.navigate(DiagnosticLog) }
                    SettingsSheetAction.OpenV4V -> closeThen { navigator.navigate(ValueForValue) }
                    SettingsSheetAction.RestartTours -> closeThen { viewModel.restartTours() }
                    SettingsSheetAction.ResetApp -> closeThen(onResetApp)
                    is SettingsSheetAction.SelectLanguage -> viewModel.setLocale(action.code)
                    is SettingsSheetAction.SelectCurrency -> viewModel.setCurrency(action.code)
                    is SettingsSheetAction.SetHaptic -> viewModel.setHapticEnabled(action.enabled)
                    is SettingsSheetAction.SetThemeMode -> viewModel.setThemeMode(action.mode)
                }
            }
        }
    }
}

/**
 * Starts the settings tour after opening and finishes it when the sheet closes — but not when
 * the activity is only recreated (language change, rotation): the app-wide guide keeps running
 * and the recreated sheet must neither finish nor restart it from step 0.
 */
@Composable
private fun SettingsTourEffects() {
    val guide = LocalGuideController.current
    val activity = LocalActivity.current
    // Read once per composition entry: a tour already running (after recreation) is kept.
    val alreadyRunning = remember { guide.activeTour == GuideTour.Settings }
    StartTourEffect(GuideTour.Settings, enabled = !alreadyRunning, delayMillis = SettingsTour.START_DELAY_MS, controller = guide) {
        SettingsTour.steps()
    }
    DisposableEffect(guide, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) guide.finishIfActive(GuideTour.Settings)
        }
    }
}

@Composable
private fun SheetGrabber() {
    Box(
        Modifier
            .padding(top = 12.dp, bottom = 20.dp)
            .size(width = 40.dp, height = 4.dp)
            .background(MaterialTheme.extendedColors.textTertiary, RoundedCornerShape(2.dp)),
    )
}

/**
 * Stateless body of the settings sheet: header, the six groups, the footer and the language
 * and currency dialogs (which open on top of the sheet, the sheet stays open).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSheetContent(
    state: SettingsSheetUiState,
    modifier: Modifier = Modifier,
    backupBusy: Boolean = false,
    enabled: Boolean = true,
    onAction: (SettingsSheetAction) -> Unit,
) {
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    var currencyDialog by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val primary = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.extendedColors.community
    val purple = MaterialTheme.extendedColors.nostr
    val green = MaterialTheme.extendedColors.success
    val red = MaterialTheme.colorScheme.error

    Column(modifier.fillMaxWidth().testTag(SettingsSheetTags.CONTENT)) {
        SheetHeader()
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .weight(1f, fill = false)
                .guideScrollContainer(scroll)
                .verticalScroll(scroll)
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp)
                .navigationBarsPadding(),
        ) {
            SettingsGroup(stringResource(L10nR.string.settingsSecAccount)) {
                SettingsRow(
                    icon = Icons.Rounded.Person,
                    accent = primary,
                    title = stringResource(L10nR.string.settingsProfile),
                    subtitle = stringResource(L10nR.string.settingsProfileSub),
                    interactive = enabled,
                    modifier = Modifier.guideAnchor(SettingsTour.PROFILE).testTag(SettingsSheetTags.PROFILE),
                    onClick = { onAction(SettingsSheetAction.OpenProfile) },
                )
            }
            GroupGap()
            SettingsGroup(stringResource(L10nR.string.settingsSecData)) {
                SettingsRow(
                    icon = Icons.Rounded.CloudUpload,
                    accent = gold,
                    title = stringResource(L10nR.string.settingsBackup),
                    subtitle = stringResource(L10nR.string.settingsBackupSub),
                    enabled = !backupBusy,
                    interactive = enabled,
                    modifier = Modifier.guideAnchor(SettingsTour.BACKUP).testTag(SettingsSheetTags.BACKUP),
                    onClick = { onAction(SettingsSheetAction.CreateBackup) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Restore,
                    accent = gold,
                    title = stringResource(L10nR.string.settingsBackupLoad),
                    subtitle = stringResource(L10nR.string.settingsBackupLoadSub),
                    enabled = !backupBusy,
                    interactive = enabled,
                    modifier = Modifier.testTag(SettingsSheetTags.RESTORE),
                    onClick = { onAction(SettingsSheetAction.RestoreBackup) },
                )
            }
            GroupGap()
            SettingsGroup(stringResource(L10nR.string.settingsSecNetwork)) {
                SettingsRow(
                    icon = Icons.Rounded.Hub,
                    accent = purple,
                    title = stringResource(L10nR.string.settingsRelays),
                    subtitle = stringResource(L10nR.string.settingsRelaysSub),
                    interactive = enabled,
                    modifier = Modifier.guideAnchor(SettingsTour.RELAYS).testTag(SettingsSheetTags.RELAYS),
                    onClick = { onAction(SettingsSheetAction.OpenRelays) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Dns,
                    accent = primary,
                    title = stringResource(L10nR.string.settingsMempool),
                    subtitle = stringResource(L10nR.string.settingsMempoolSub),
                    interactive = enabled,
                    modifier = Modifier.testTag(SettingsSheetTags.MEMPOOL),
                    onClick = { onAction(SettingsSheetAction.OpenMempool) },
                )
            }
            GroupGap()
            SettingsGroup(stringResource(L10nR.string.settingsSecApp)) {
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    accent = green,
                    title = stringResource(L10nR.string.settingsLanguageTitle),
                    subtitle = "${AppLocales.flagFor(state.locale)}  ${AppLocales.displayName(state.locale)}",
                    interactive = enabled,
                    modifier = Modifier.guideAnchor(SettingsTour.LANGUAGE).testTag(SettingsSheetTags.LANGUAGE),
                    onClick = { languageDialog = true },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.CurrencyExchange,
                    accent = green,
                    title = stringResource(L10nR.string.settingsCurrencyTitle),
                    subtitle = "${state.currency} · ${currencyName(state.currency)}",
                    interactive = enabled,
                    modifier = Modifier.testTag(SettingsSheetTags.CURRENCY),
                    onClick = { currencyDialog = true },
                )
                RowDivider()
                HapticRow(
                    enabled = state.hapticEnabled,
                    interactive = enabled,
                    modifier = Modifier.guideAnchor(SettingsTour.HAPTIC).testTag(SettingsSheetTags.HAPTIC),
                    onToggle = { onAction(SettingsSheetAction.SetHaptic(it)) },
                )
                RowDivider()
                ThemeRow(state.themeMode) { onAction(SettingsSheetAction.SetThemeMode(it)) }
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.BugReport,
                    accent = gold,
                    title = stringResource(L10nR.string.settingsLogTitle),
                    subtitle = stringResource(L10nR.string.settingsLogSub),
                    interactive = enabled,
                    modifier = Modifier.testTag(SettingsSheetTags.LOG),
                    onClick = { onAction(SettingsSheetAction.OpenLog) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Replay,
                    accent = primary,
                    title = stringResource(L10nR.string.settingsRestartGuide),
                    subtitle = stringResource(L10nR.string.settingsRestartGuideSub),
                    interactive = enabled,
                    modifier = Modifier.guideAnchor(SettingsTour.RESTART).testTag(SettingsSheetTags.RESTART),
                    onClick = { onAction(SettingsSheetAction.RestartTours) },
                )
            }
            GroupGap()
            SettingsGroup(stringResource(L10nR.string.v4vSectionTitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Bolt,
                    accent = primary,
                    title = V4V_TITLE,
                    subtitle = stringResource(L10nR.string.v4vSectionSubtitle),
                    interactive = enabled,
                    modifier = Modifier.testTag(SettingsSheetTags.V4V),
                    onClick = { onAction(SettingsSheetAction.OpenV4V) },
                )
            }
            GroupGap()
            SettingsGroup(stringResource(L10nR.string.settingsSecDanger)) {
                SettingsRow(
                    icon = Icons.Rounded.DeleteForever,
                    accent = red,
                    title = stringResource(L10nR.string.settingsReset),
                    subtitle = stringResource(L10nR.string.settingsResetSub),
                    interactive = enabled,
                    danger = true,
                    modifier = Modifier.guideAnchor(SettingsTour.RESET).testTag(SettingsSheetTags.RESET),
                    onClick = { onAction(SettingsSheetAction.ResetApp) },
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (state.appVersion.isEmpty()) "" else stringResource(R.string.native_settings_version, state.appVersion),
                modifier = Modifier.fillMaxWidth().testTag(SettingsSheetTags.VERSION),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = MaterialTheme.extendedColors.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (languageDialog) {
        LanguageDialog(
            current = state.locale,
            onSelect = {
                languageDialog = false
                onAction(SettingsSheetAction.SelectLanguage(it))
            },
            onDismiss = { languageDialog = false },
        )
    }
    if (currencyDialog) {
        CurrencyDialog(
            current = state.currency,
            onSelect = {
                currencyDialog = false
                onAction(SettingsSheetAction.SelectCurrency(it))
            },
            onDismiss = { currencyDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SheetHeader() {
    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                stringResource(L10nR.string.settingsHeaderTitle),
                modifier = Modifier.semantics { heading() },
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                stringResource(L10nR.string.settingsHeaderSub),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupGap() = Spacer(Modifier.height(18.dp))

@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column {
        Text(
            label,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp).semantics { heading() },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.extendedColors.textTertiary,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(vertical = 2.dp),
        ) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RowIcon(icon: ImageVector, accent: Color) {
    Box(
        Modifier
            .size(36.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String, danger: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.extendedColors.textTertiary, lineHeight = 14.sp)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** `false` while the sheet is closing: ignores taps without greying the row out. */
    interactive: Boolean = true,
    danger: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled && interactive, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, if (enabled) accent else MaterialTheme.extendedColors.textTertiary)
        Spacer(Modifier.width(14.dp))
        RowTexts(title, subtitle, danger, Modifier.weight(1f))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.extendedColors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun HapticRow(enabled: Boolean, interactive: Boolean, modifier: Modifier, onToggle: (Boolean) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = enabled, enabled = interactive, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(Icons.Rounded.Vibration, MaterialTheme.extendedColors.success)
        Spacer(Modifier.width(14.dp))
        RowTexts(
            title = stringResource(L10nR.string.settingsHaptic),
            subtitle = stringResource(if (enabled) L10nR.string.settingsHapticOn else L10nR.string.settingsHapticOff),
            danger = false,
            modifier = Modifier.weight(1f),
        )
        // The row is the toggle (one 56 dp target); the switch only renders the state.
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** Native-only appearance choice as an Expressive connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        Triple(ThemeMode.DARK, Icons.Rounded.DarkMode, R.string.native_settings_theme_dark),
        Triple(ThemeMode.LIGHT, Icons.Rounded.LightMode, R.string.native_settings_theme_light),
        Triple(ThemeMode.SYSTEM, Icons.Rounded.SettingsSuggest, R.string.native_settings_theme_system),
    )
    val title = stringResource(R.string.native_settings_theme_title)
    // Large font scales: use the full row width so the labels fit (they wrap instead of ellipsizing).
    val inset = if (LocalDensity.current.fontScale > 1.3f) 0.dp else 50.dp
    Column(Modifier.fillMaxWidth().testTag(SettingsSheetTags.THEME).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.Palette, MaterialTheme.extendedColors.success)
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = inset).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, (mode, icon, label) ->
                val checked = mode == selected
                // TalkBack: "Erscheinungsbild: Dunkel" plus the checked state of the toggle.
                val description = "$title: ${stringResource(label)}"
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { if (!checked) onSelect(mode) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = description }
                        .testTag(SettingsSheetTags.theme(mode)),
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(label),
                        modifier = Modifier.clearAndSetSemantics { },
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Preview(name = "Settings sheet", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 1500)
@Composable
private fun SettingsSheetPreview() {
    EinundzwanzigTheme {
        SettingsSheetContent(
            SettingsSheetUiState(locale = "de", currency = "EUR", hapticEnabled = true, appVersion = "1.7.2"),
        ) {}
    }
}

@Preview(name = "Settings sheet light, haptics off", showBackground = true, heightDp = 1500)
@Composable
private fun SettingsSheetLightPreview() {
    EinundzwanzigTheme(themeMode = ThemeMode.LIGHT) {
        SettingsSheetContent(
            SettingsSheetUiState(locale = null, currency = "CHF", hapticEnabled = false, themeMode = ThemeMode.LIGHT),
            backupBusy = true,
        ) {}
    }
}
