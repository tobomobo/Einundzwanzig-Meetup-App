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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = null,
    ) {
        DetailsSheetLayout(onClose = onDismiss, content = content)
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
