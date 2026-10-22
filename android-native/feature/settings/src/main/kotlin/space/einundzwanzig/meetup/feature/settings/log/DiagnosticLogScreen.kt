package space.einundzwanzig.meetup.feature.settings.log

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.core.common.log.LogExport
import space.einundzwanzig.meetup.core.common.log.LogLevel
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.Inconsolata
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.settings.R

/** Test tags of the log screen. */
object DiagnosticLogTags {
    const val LIST = "log_list"
    const val EMPTY = "log_empty"
    const val COUNT = "log_count"
    const val CLEAR = "log_clear"
    const val PROBE = "log_probe"
    const val FILTER = "log_filter"
    const val VERBOSE = "log_verbose"
    const val COPY = "log_copy"
    const val SHARE = "log_share"
}

/** Flutter SnackBar default display time (4 s). */
private const val DEFAULT_SNACKBAR_MILLIS = 4_000L

/** Stateful diagnostic log entry (spec 12 §2.8, NIP-44 probe spec 01 §2.9). */
@Composable
fun DiagnosticLogRoute(viewModel: DiagnosticLogViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is DiagnosticLogEvent.Snackbar -> scope.launch {
                    snackbar.showAppSnackbar(
                        context.getString(event.message.res),
                        tone = if (event.message == LogMessage.ProbeFailed) Tone.Error else Tone.Neutral,
                        durationMillis = DEFAULT_SNACKBAR_MILLIS,
                    )
                }
                is DiagnosticLogEvent.Copy -> {
                    SecretClipboard.copyPlain(context, event.text, LogExport.SHARE_SUBJECT)
                    scope.launch { snackbar.showAppSnackbar(context.getString(R.string.native_log_copied), durationMillis = AppSnackbarDurations.Short) }
                }
                is DiagnosticLogEvent.Share -> {
                    if (!shareLog(context, event.text)) {
                        SecretClipboard.copyPlain(context, event.text, LogExport.SHARE_SUBJECT)
                        scope.launch { snackbar.showAppSnackbar(context.getString(R.string.native_log_copied), durationMillis = AppSnackbarDurations.Short) }
                    }
                }
            }
        }
    }

    DiagnosticLogScreen(state, snackbar) { action ->
        if (action == DiagnosticLogAction.Back) navigator.back() else viewModel.onAction(action)
    }
}

/** Snackbar text of a [LogMessage]. */
internal val LogMessage.res: Int
    get() = when (this) {
        LogMessage.ProbeOk -> R.string.native_log_probe_ok
        LogMessage.ProbeFailed -> R.string.native_log_probe_failed
        LogMessage.VerboseOn -> R.string.native_log_verbose_enabled
        LogMessage.VerboseOff -> R.string.native_log_verbose_disabled
        LogMessage.Copied -> R.string.native_log_copied
    }

/**
 * Android Sharesheet with subject `21Meetup Diagnose-Log`. Large logs (over ~100 KB) go as a
 * `.txt` attachment through the FileProvider to stay below the Binder limit (spec 12 §2.8, §14.16).
 */
private suspend fun shareLog(context: Context, text: String): Boolean = try {
    if (LogExport.shouldShareAsFile(text)) {
        val file = withContext(Dispatchers.IO) { LogExport.writeToFile(text, ShareHelper.shareDir(context)) }
        ShareHelper.shareFile(context, ShareHelper.uriFor(context, file), "text/plain", subject = LogExport.SHARE_SUBJECT)
    } else {
        ShareHelper.shareText(context, text, subject = LogExport.SHARE_SUBJECT)
    }
} catch (e: Exception) {
    AppLogger.warn("Log", "Teilen fehlgeschlagen", e)
    false
}

/** Stateless diagnostic log screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogScreen(
    state: DiagnosticLogUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (DiagnosticLogAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppMediumTopBar(
                title = stringResource(R.string.native_log_title),
                onBack = { onAction(DiagnosticLogAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = { LogActions(state, onAction) },
            )
        },
        bottomBar = { LogFooter(count = state.entries.size, onClear = { onAction(DiagnosticLogAction.Clear) }) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.ReceiptLong,
                    title = stringResource(R.string.native_log_empty),
                    subtitle = "",
                    modifier = Modifier.testTag(DiagnosticLogTags.EMPTY),
                    accentColor = MaterialTheme.extendedColors.textTertiary,
                )
            } else {
                LogList(state.entries)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogActions(state: DiagnosticLogUiState, onAction: (DiagnosticLogAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    WithTooltip(stringResource(R.string.native_log_probe_tooltip)) {
        IconButton(
            onClick = { onAction(DiagnosticLogAction.Probe) },
            enabled = !state.probing,
            modifier = Modifier.testTag(DiagnosticLogTags.PROBE),
        ) {
            if (state.probing) {
                AppLoadingIndicator(size = 20.dp, contentDescription = stringResource(R.string.native_log_probe_running))
            } else {
                Icon(Icons.Rounded.Key, contentDescription = stringResource(R.string.native_log_probe_tooltip), tint = idle, modifier = Modifier.size(20.dp))
            }
        }
    }
    val filterLabel = stringResource(R.string.native_log_filter_tooltip)
    WithTooltip(filterLabel) {
        IconToggleButton(
            checked = state.onlyProblems,
            onCheckedChange = { onAction(DiagnosticLogAction.ToggleFilter) },
            modifier = Modifier.testTag(DiagnosticLogTags.FILTER),
        ) {
            Icon(
                if (state.onlyProblems) Icons.Rounded.FilterAlt else Icons.Outlined.FilterAlt,
                contentDescription = filterLabel,
                tint = animateColorAsState(if (state.onlyProblems) primary else idle, label = "filter").value,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    val verboseLabel = stringResource(if (state.verbose) R.string.native_log_verbose_on_tooltip else R.string.native_log_verbose_off_tooltip)
    WithTooltip(verboseLabel) {
        IconToggleButton(
            checked = state.verbose,
            onCheckedChange = { onAction(DiagnosticLogAction.ToggleVerbose) },
            modifier = Modifier.testTag(DiagnosticLogTags.VERBOSE),
        ) {
            Icon(
                if (state.verbose) Icons.Rounded.ZoomIn else Icons.Rounded.ZoomOutMap,
                contentDescription = verboseLabel,
                tint = animateColorAsState(if (state.verbose) primary else idle, label = "verbose").value,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    ActionIcon(Icons.Rounded.ContentCopy, stringResource(R.string.native_log_copy), DiagnosticLogTags.COPY) { onAction(DiagnosticLogAction.Copy) }
    ActionIcon(Icons.Rounded.Share, stringResource(R.string.native_log_share), DiagnosticLogTags.SHARE) { onAction(DiagnosticLogAction.Share) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    WithTooltip(label) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    LogLevel.ERROR.code -> MaterialTheme.colorScheme.error
    LogLevel.WARN.code -> MaterialTheme.colorScheme.primary
    LogLevel.SEC.code -> MaterialTheme.extendedColors.nostr
    LogLevel.DEBUG.code -> MaterialTheme.extendedColors.textTertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Terminal-style list: `HH:MM:SS  LEVEL  tag: message`, selectable. */
@Composable
private fun LogList(entries: List<LogEntry>) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    val tagColor = MaterialTheme.colorScheme.primary
    val text = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        SelectionContainer {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .testTag(DiagnosticLogTags.LIST),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                itemsIndexed(entries, key = { index, e -> "$index-${e.timeMillis}" }) { _, entry ->
                    val levelColor = levelColor(entry.level)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = tertiary)) { append(entry.timeOfDay()); append("  ") }
                            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) { append(entry.level.padEnd(5)) }
                            withStyle(SpanStyle(color = tagColor)) { append("  "); append(entry.tag); append(": ") }
                            withStyle(SpanStyle(color = text)) { append(entry.message) }
                        },
                        fontFamily = Inconsolata,
                        fontSize = 11.5.sp,
                        lineHeight = 1.6.em,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogFooter(count: Int, onClear: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.native_log_count, count),
            modifier = Modifier.testTag(DiagnosticLogTags.COUNT),
            fontSize = 12.sp,
            color = MaterialTheme.extendedColors.textTertiary,
        )
        FilledTonalButton(
            onClick = onClear,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DiagnosticLogTags.CLEAR)
                .border(0.5.dp, error.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = error.copy(alpha = 0.1f), contentColor = error),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.native_log_clear), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun previewEntries(): List<LogEntry> {
    val t = 1_760_000_000_000L
    return listOf(
        LogEntry(t, "INFO", "ooo", "───── APP-START ─────"),
        LogEntry(t + 1_000, "INFO", "System", "App 1.7.2 (Build 31)"),
        LogEntry(t + 2_000, "DEBUG", "Relay", "wss://relay.damus.io verbunden (212 ms)"),
        LogEntry(t + 3_000, "SEC", "Signer", "Amber-Freigabe angefragt"),
        LogEntry(t + 4_000, "WARN", "Chat", "Relay antwortet nicht — erneuter Versuch"),
        LogEntry(t + 5_000, "ERROR", "Badge", "Signatur ungültig — IllegalStateException: bad sig"),
    )
}

@Preview(name = "Diagnostic log", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 700)
@Composable
private fun DiagnosticLogPreview() {
    EinundzwanzigTheme {
        DiagnosticLogScreen(
            DiagnosticLogUiState(entries = previewEntries(), verbose = true),
            remember { SnackbarHostState() },
        ) {}
    }
}

@Preview(name = "Diagnostic log empty, probing", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 700)
@Composable
private fun DiagnosticLogEmptyPreview() {
    EinundzwanzigTheme {
        DiagnosticLogScreen(
            DiagnosticLogUiState(onlyProblems = true, probing = true),
            remember { SnackbarHostState() },
        ) {}
    }
}
