package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/**
 * Brand wrapper around the Material 3 Expressive [LoadingIndicator] (shape-morphing),
 * replacing the Flutter `CircularProgressIndicator(color: cOrange)` everywhere.
 *
 * @param size outer size; 48 dp is the M3 default, use 24 dp inline and 18 dp in buttons.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    LoadingIndicator(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        color = color,
    )
}

/** Contained (filled background) variant for loading states on top of content. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    ContainedLoadingIndicator(modifier = modifier.size(size))
}

/** Centered full-area loading state with an optional message below the indicator. */
@Composable
fun FullScreenLoading(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppLoadingIndicator()
            if (message != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/**
 * Placeholder box of a fixed height with a small indicator in the middle (Flutter's image
 * loading placeholders: card colour, small orange spinner).
 */
@Composable
fun LoadingPlaceholder(
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        AppLoadingIndicator(size = 32.dp)
    }
}

@Preview(name = "Loading", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun LoadingPreview() {
    EinundzwanzigTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppLoadingIndicator()
                AppLoadingIndicator(size = 24.dp)
                AppContainedLoadingIndicator()
            }
            LoadingPlaceholder(height = 120.dp)
        }
    }
}
