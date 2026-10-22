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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
    DiagnosticLogRoute(viewModel, LogClipboard::writeSystemClipboard)
}

/** [DiagnosticLogRoute] with a replaceable clipboard writer (tests: a writer that throws). */
@Composable
internal fun DiagnosticLogRoute(viewModel: DiagnosticLogViewModel, writeClip: (Context, String) -> Unit) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val currentWriteClip by rememberUpdatedState(writeClip)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // "Log kopiert" only after a successful copy; a clipboard failure (e.g. Binder limit) shows
    // an error snackbar instead of crashing the collector.
    fun copyAndReport(text: String) {
        val ok = LogClipboard.copy(context, text, currentWriteClip)
        scope.launch {
            snackbar.showAppSnackbar(
                resources.getString(if (ok) R.string.native_log_copied else R.string.native_log_copy_failed),
                tone = if (ok) Tone.Neutral else Tone.Error,
                durationMillis = if (ok) AppSnackbarDurations.Short else DEFAULT_SNACKBAR_MILLIS,
            )
        }
    }

    LaunchedEffect(viewModel, context, snackbar) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is DiagnosticLogEvent.Snackbar -> scope.launch {
                    snackbar.showAppSnackbar(
                        resources.getString(event.message.res),
                        tone = if (event.message == LogMessage.ProbeFailed) Tone.Error else Tone.Neutral,
                        durationMillis = DEFAULT_SNACKBAR_MILLIS,
                    )
                }
                is DiagnosticLogEvent.Copy -> copyAndReport(event.text)
                is DiagnosticLogEvent.Share -> if (!shareLog(context, event.text)) copyAndReport(event.text)
            }
        }
    }

    DiagnosticLogScreen(state, snackbar) { action ->
        if (action == DiagnosticLogAction.Back) navigator.back() else viewModel.onAction(action)
    }
}

/**
 * Clipboard copy of the log export. `setPrimaryClip` goes through Binder and throws above about
 * 1 MB, while the buffer (800 entries of up to 4,000 chars) can reach several MB: the text is
 * cut to its newest [MAX_CLIP_BYTES] (at a line start) and every failure is caught.
 */
internal object LogClipboard {
    /** UTF-8 budget of the clip, well below the ~1 MB Binder transaction limit. */
    const val MAX_CLIP_BYTES = 500 * 1024

    fun writeSystemClipboard(context: Context, text: String) {
        SecretClipboard.copyPlain(context, text, LogExport.SHARE_SUBJECT)
    }

    /** Copies [clipText] of [text] with [write]; `false` (and a WARN entry) if that throws. */
    fun copy(context: Context, text: String, write: (Context, String) -> Unit = ::writeSystemClipboard): Boolean = try {
        write(context, clipText(text))
        true
    } catch (e: RuntimeException) {
        AppLogger.warn("Log", "Kopieren fehlgeschlagen", e)
        false
    }

    /**
     * [text] if it fits into [maxBytes] UTF-8 bytes, else its newest part (the log ends with the
     * newest entry) starting at a line start; without a line break inside the budget the cut is
     * at a character boundary.
     */
    fun clipText(text: String, maxBytes: Int = MAX_CLIP_BYTES): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var start = bytes.size - maxBytes
        val newline = (start until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }
        if (newline != null) {
            start = newline + 1
        } else {
            // Skip UTF-8 continuation bytes (10xxxxxx) so no character is split.
            while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        }
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            MigrationDiagnostics(state.migration, onAction)
            Box(Modifier.weight(1f)) {
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

/**
 * Terminal-style log: `HH:MM:SS  LEVEL  tag: message`, one line per entry.
 *
 * Like Flutter's single `SelectableText.rich`, the whole (filtered) log is one annotated text in
 * a scrolling column, so a selection can span every line, also the ones scrolled off screen.
 * The ring buffer holds at most 800 short lines, which one text layout handles easily.
 */
@Composable
private fun LogList(entries: List<LogEntry>) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    val tagColor = MaterialTheme.colorScheme.primary
    val text = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    val warn = MaterialTheme.colorScheme.primary
    val sec = MaterialTheme.extendedColors.purple
    val info = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(entries, tertiary, tagColor, text, error, warn, sec, info) {
        buildAnnotatedString {
            entries.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                val levelColor = when (entry.level) {
                    LogLevel.ERROR.code -> error
                    LogLevel.WARN.code -> warn
                    LogLevel.SEC.code -> sec
                    LogLevel.DEBUG.code -> tertiary
                    else -> info
                }
                withStyle(SpanStyle(color = tertiary)) { append(entry.timeOfDay()); append("  ") }
                withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) { append(entry.level.padEnd(5)) }
                withStyle(SpanStyle(color = tagColor)) { append("  "); append(entry.tag); append(": ") }
                withStyle(SpanStyle(color = text)) { append(entry.message) }
            }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        SelectionContainer {
            Text(
                annotated,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(DiagnosticLogTags.LIST)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                fontFamily = Inconsolata,
                fontSize = 11.5.sp,
                lineHeight = 1.6.em,
            )
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
