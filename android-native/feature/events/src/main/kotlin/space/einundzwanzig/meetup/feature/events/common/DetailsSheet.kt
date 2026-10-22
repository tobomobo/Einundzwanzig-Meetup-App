package space.einundzwanzig.meetup.feature.events.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tag of the bounded details sheet container. */
const val DETAILS_SHEET_TAG = "events.detailsSheet"

/**
 * The shared bounded details sheet (Dart `showEventDetailsSheet`, spec 10 §2.7): modal, card
 * colour, radius 20 on top, drag handle with a close button in the same row, content scrolls,
 * at most 90 % of the available height, compact for short content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = null,
    ) {
        DetailsSheetLayout(
            onClose = {
                // Close button: run the hide animation first, like a swipe or scrim tap.
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) onDismiss()
                }
            },
            content = content,
        )
        // The sheet is its own window, so snackbars raised while it is open would render under it
        // (the screen hides its own host meanwhile). A popup is a child window of the sheet and
        // sits at the bottom of the visible display, also while the sheet is only half expanded.
        if (snackbarHostState != null) {
            Popup(popupPositionProvider = BottomOfWindow, properties = PopupProperties(focusable = false)) {
                AppSnackbarHost(snackbarHostState, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Header (handle + close) and the bounded scrolling body of [EventDetailsSheet]. */
@Composable
fun DetailsSheetLayout(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val max = if (constraints.hasBoundedHeight) maxHeight * MAX_HEIGHT_FRACTION else maxHeight
        Column(Modifier.fillMaxWidth().heightIn(max = max).testTag(DETAILS_SHEET_TAG)) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(width = 44.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                )
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(L10nR.string.actionClose))
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}

private const val MAX_HEIGHT_FRACTION = 0.9f

/** Places a popup bottom-centred in the visible window frame (above the navigation bar). */
private object BottomOfWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = windowSize.height - popupContentSize.height,
    )
}
