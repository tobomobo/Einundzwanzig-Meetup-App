package space.einundzwanzig.meetup.feature.backup

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.share.ShareResult
import space.einundzwanzig.meetup.data.backup.BackupFiles
import space.einundzwanzig.meetup.feature.backup.di.BackupEntryPoint
import java.io.File
import javax.inject.Inject
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Exposes the app-wide [BackupCoordinator] to [BackupHost]. */
@HiltViewModel
class BackupHostViewModel @Inject constructor(
    private val coordinator: BackupCoordinator,
    /** For the share fallback's file I/O ([shareBackup]). */
    @IoDispatcher val io: CoroutineDispatcher,
) : ViewModel() {
    val uiState: StateFlow<BackupUiState> get() = coordinator.ui
    val messages: Flow<BackupMessage> get() = coordinator.messages

    fun onAction(action: BackupAction) = coordinator.onAction(action)
    fun onDocumentResult(uri: String?) = coordinator.onDocumentResult(uri)
    fun attach(platform: BackupPlatform) = coordinator.attach(platform)
    fun detach(platform: BackupPlatform) = coordinator.detach(platform)
}

/**
 * Mount once at the app root (inside the theme, next to the app's snackbar host). It renders the
 * password dialog, the identity dialogs and the overlay, registers the SAF launchers, provides the
 * share sheet and shows the backup snackbars on [snackbarHostState] (spec 08 §2.1, §13).
 */
@Composable
fun BackupHost(
    snackbarHostState: SnackbarHostState,
    viewModel: BackupHostViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val vm by rememberUpdatedState(viewModel)

    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFiles.MIME_TYPE),
    ) { uri -> vm.onDocumentResult(uri?.toString()) }
    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> vm.onDocumentResult(uri?.toString()) }

    val io = viewModel.io
    val platform = remember(context, create, open, io) {
        object : BackupPlatform {
            override fun launchCreateDocument(fileName: String): Boolean = launchSafely { create.launch(fileName) }
            override fun launchOpenDocument(): Boolean = launchSafely { open.launch(BackupFiles.OPEN_MIME_TYPES) }
            override suspend fun share(fileName: String, bytes: ByteArray): ShareResult =
                shareBackup(context, fileName, bytes, io)
        }
    }
    DisposableEffect(viewModel, platform) {
        viewModel.attach(platform)
        onDispose { viewModel.detach(platform) }
    }

    // Each message is consumed once. A snackbar that is showing when the activity is recreated
    // (rotation) is not shown again: the app root's SnackbarHostState is recreated as well.
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            val snackbar = BackupMessages.snackbar(context.resources, message)
            launch { snackbarHostState.showAppSnackbar(snackbar.text, snackbar.tone, snackbar.durationMillis) }
        }
    }

    BackupDialogs(state, viewModel::onAction)
}

/**
 * The [BackupLauncher] for composables, limited to reading [BackupLauncher.busy] and starting a
 * flow whose result nobody needs ([BackupLauncher.launchExport] / [BackupLauncher.launchRestore]
 * without a callback that touches UI state).
 *
 * Do not call [BackupLauncher.export] / [BackupLauncher.restore] from a composition scope
 * (`rememberCoroutineScope`): the SAF picker and the share sheet are separate activities, a
 * rotation while they are open recreates the host activity, cancels that scope and drops the
 * result. Callers that act on the result (intro and identity setup navigate to the app shell on
 * `true`, spec 08 §2.1) must inject [BackupLauncher] into their `@HiltViewModel` and call it from
 * `viewModelScope`, which survives configuration changes.
 *
 * Tests and previews can override it with [LocalBackupLauncher].
 */
@Composable
fun rememberBackupLauncher(): BackupLauncher {
    LocalBackupLauncher.current?.let { return it }
    val app = LocalContext.current.applicationContext
    check(!LocalInspectionMode.current) { "Provide LocalBackupLauncher in previews" }
    return remember(app) { EntryPointAccessors.fromApplication(app, BackupEntryPoint::class.java).backupLauncher() }
}

/** Override for [rememberBackupLauncher] (tests, previews). */
val LocalBackupLauncher = compositionLocalOf<BackupLauncher?> { null }

/** A [BackupLauncher] that does nothing (previews and tests of calling screens). */
object NoOpBackupLauncher : BackupLauncher {
    override val busy: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun export(): Boolean = false
    override suspend fun restore(): Boolean = false
    override fun launchExport(onResult: (Boolean) -> Unit) = onResult(false)
    override fun launchRestore(onResult: (Boolean) -> Unit) = onResult(false)
}

private inline fun launchSafely(block: () -> Unit): Boolean = try {
    block()
    true
} catch (e: ActivityNotFoundException) {
    AppLogger.debug("App", "Dateiauswahl nicht verfuegbar: ${e.message}")
    false
}

/**
 * Share fallback (spec 08 §2.4 step 11, §11): writes the file to `cacheDir/backup/` (older
 * backups there are deleted first) and opens the chooser with subject/text.
 */
internal suspend fun shareBackup(
    context: Context,
    fileName: String,
    bytes: ByteArray,
    io: CoroutineDispatcher,
    openChooser: suspend (File) -> ShareResult = { file -> openShareChooser(context, file) },
): ShareResult {
    val file = withContext(io) {
        val dir = ShareHelper.backupDir(context)
        dir.listFiles()?.forEach { it.delete() }
        File(dir, fileName.replace('/', '_').replace('\\', '_')).apply { writeBytes(bytes) }
    }
    // Cancelled (host destroyed while the chooser was open): keep the file, a chosen target may
    // still be reading it; the next export clears the directory.
    val result = openChooser(file)
    if (result == ShareResult.Dismissed) withContext(io) { file.delete() }
    return result
}

private suspend fun openShareChooser(context: Context, file: File): ShareResult {
    val subject = context.getString(L10nR.string.backupShareTitle)
    return ShareHelper.shareFileForResult(
        context = context,
        uri = ShareHelper.uriFor(context, file),
        mimeType = BackupFiles.MIME_TYPE,
        subject = subject,
        text = context.getString(L10nR.string.backupShareText),
        chooserTitle = subject,
    )
}
