package space.einundzwanzig.meetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.R
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/**
 * System-splash keep condition (spec 12 §2.3, spec 14 §4.7): the splash stays until the migration
 * gate completed, the theme mode is known and the first route is decided, but at most until the
 * 20 s gate timeout; after it [MigrationWaitScreen] takes over (the app itself still waits).
 */
internal fun keepSystemSplash(
    gateDone: Boolean,
    themeLoaded: Boolean,
    firstRouteShown: Boolean,
    splashTimedOut: Boolean,
): Boolean = !splashTimedOut && (!gateDone || !themeLoaded || !firstRouteShown)

internal const val MIGRATION_WAIT_TAG = "migration_wait"

/**
 * Blocking in-app screen shown when the Flutter data import takes longer than the system splash
 * may stay (slow Keystore/ESP decryption on old phones). Nothing of the app is composed meanwhile,
 * so no domain can create a replacement key over a secret that is still being imported.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MigrationWaitScreen(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .testTag(MIGRATION_WAIT_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ContainedLoadingIndicator(Modifier.size(72.dp))
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.native_migration_wait_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.native_migration_wait_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun MigrationWaitScreenPreview() {
    EinundzwanzigTheme { MigrationWaitScreen() }
}
