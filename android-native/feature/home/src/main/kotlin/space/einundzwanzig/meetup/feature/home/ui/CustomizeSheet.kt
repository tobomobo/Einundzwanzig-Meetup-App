package space.einundzwanzig.meetup.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.tiles.TileLayoutEngine

/**
 * Customize sheet (`_CustomizeSheet`, spec 12 §2.5.9): reorderable "AKTIV" list with switches to
 * hide, "VERFÜGBAR" list with switches to add. Changes are applied only with "FERTIG"; closing
 * the sheet any other way discards them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizeSheet(
    order: List<String>,
    hidden: Set<String>,
    isVisible: (TileId) -> Boolean,
    onSave: (List<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        CustomizeContent(
            initialOrder = order,
            initialHidden = hidden,
            isVisible = isVisible,
            onDone = { o, h ->
                onSave(o, h)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            },
        )
    }
}

/** Stateless-ish body of the sheet (local working copies); public to the module for tests. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CustomizeContent(
    initialOrder: List<String>,
    initialHidden: Set<String>,
    isVisible: (TileId) -> Boolean,
    onDone: (List<String>, Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var order by remember { mutableStateOf(initialOrder) }
    var hidden by remember { mutableStateOf(initialHidden) }
    val active = TileLayoutEngine.sheetActive(order, hidden, isVisible)
    val available = TileLayoutEngine.sheetAvailable(hidden, isVisible)
    val tertiary = MaterialTheme.extendedColors.textTertiary
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            .navigationBarsPadding()
            .testTag(HomeTags.CUSTOMIZE_SHEET),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.native_home_customize_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onDone(order, hidden) }, modifier = Modifier.testTag(HomeTags.CUSTOMIZE_DONE)) {
                Text(stringResource(R.string.native_home_customize_done), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.native_home_customize_hint), style = MaterialTheme.typography.labelSmall, color = tertiary)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.native_home_customize_active), stringResource(R.string.native_home_customize_active_sub))
            ReorderableList(
                items = active,
                onMove = { from, to -> order = TileLayoutEngine.sheetReorder(order, active, from, to) },
                onHide = { id -> hidden = LinkedHashSet(hidden + id) },
            )
            if (available.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionTitle(
                    stringResource(R.string.native_home_customize_available),
                    stringResource(R.string.native_home_customize_available_sub),
                )
                available.forEach { id ->
                    val tile = TileId.fromId(id) ?: return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag(HomeTags.customizeRow(id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(sheetIcon(tile), null, Modifier.size(15.dp), tint = tertiary)
                        Spacer(Modifier.width(10.dp))
                        val label = tileLabel(tile)
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = tertiary, modifier = Modifier.weight(1f))
                        Switch(
                            checked = false,
                            onCheckedChange = { hidden = LinkedHashSet(hidden - id) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
            color = MaterialTheme.extendedColors.textTertiary,
        )
        Spacer(Modifier.width(8.dp))
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textTertiary)
    }
}

/** Active rows: drag handle (long press + drag), icon, label, lock or switch; TalkBack move actions. */
@Composable
private fun ReorderableList(items: List<String>, onMove: (Int, Int) -> Unit, onHide: (String) -> Unit) {
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(1f) }
    val gapPx = with(LocalDensity.current) { 4.dp.toPx() }
    val currentItems by rememberUpdatedState(items)
    val currentMove by rememberUpdatedState(onMove)
    val up = stringResource(R.string.native_home_move_up)
    val down = stringResource(R.string.native_home_move_down)
    val handleLabel = stringResource(R.string.native_home_drag_handle)
    Column {
        items.forEachIndexed { index, id ->
            val tile = TileId.fromId(id) ?: return@forEachIndexed
            val locked = TileLayoutEngine.sheetLocked(id)
            val dragging = index == dragIndex
            val label = tileLabel(tile)
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .onSizeChanged { itemHeight = it.height.toFloat() + gapPx }
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(up) { onMove(index, index - 1); true })
                            if (index < items.lastIndex) add(CustomAccessibilityAction(down) { onMove(index, index + 1); true })
                        }
                    }
                    .testTag(HomeTags.customizeRow(id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.DragIndicator,
                    handleLabel,
                    Modifier
                        .size(32.dp)
                        .padding(8.dp)
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragIndex = currentItems.indexOf(id)
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val i = dragIndex
                                    if (dragOffset > itemHeight / 2 && i in 0 until currentItems.lastIndex) {
                                        currentMove(i, i + 1)
                                        dragIndex = i + 1
                                        dragOffset -= itemHeight
                                    } else if (dragOffset < -itemHeight / 2 && i > 0) {
                                        currentMove(i, i - 1)
                                        dragIndex = i - 1
                                        dragOffset += itemHeight
                                    }
                                },
                                onDragEnd = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                },
                            )
                        },
                    tint = MaterialTheme.extendedColors.textTertiary,
                )
                Icon(sheetIcon(tile), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (locked) {
                    Icon(
                        Icons.Rounded.Lock,
                        stringResource(R.string.native_home_customize_locked, label),
                        Modifier.size(13.dp),
                        tint = MaterialTheme.extendedColors.textTertiary,
                    )
                } else {
                    Switch(
                        checked = true,
                        onCheckedChange = { onHide(id) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

/** Sheet icon by tile id (Flutter `_CustomizeSheet`). */
private fun sheetIcon(tile: TileId): ImageVector = when (tile) {
    TileId.TrustScore -> Icons.Rounded.Shield
    TileId.HomeMeetup -> Icons.Rounded.Home
    TileId.Reputation -> Icons.Rounded.WorkspacePremium
    TileId.Community -> Icons.Rounded.Hub
    TileId.Events -> Icons.Rounded.Event
    TileId.Shoutout -> Icons.Rounded.Campaign
    TileId.Podcast -> Icons.Rounded.Podcasts
    TileId.Organisator -> Icons.Rounded.AdminPanelSettings
    else -> Icons.Rounded.Widgets
}
