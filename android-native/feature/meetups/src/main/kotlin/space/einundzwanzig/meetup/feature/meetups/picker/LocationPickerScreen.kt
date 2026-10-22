package space.einundzwanzig.meetup.feature.meetups.picker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.result.LocalResultEventBus
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.map.LocationPicker
import space.einundzwanzig.meetup.core.map.LocationPickerMap
import space.einundzwanzig.meetup.core.map.MapZoomControls
import space.einundzwanzig.meetup.core.map.OsmCameraState
import space.einundzwanzig.meetup.core.map.format
import space.einundzwanzig.meetup.core.map.rememberLocationPickerCameraState
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.PickedLocation
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.meetups.common.CollectMessages
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object LocationPickerTags {
    const val MAP = "picker_map"
    const val HINT = "picker_hint"
    const val COORDS = "picker_coords"
    const val CONFIRM = "picker_confirm"
    const val JUMP = "picker_jump"
}

/** Stateful "Ort des Events" (spec 09 §2.6); returns [NavResults.LocationPicked]. */
@Composable
fun LocationPickerRoute(
    initialLatitude: Double?,
    initialLongitude: Double?,
    viewModel: LocationPickerViewModel = hiltViewModel(),
) {
    val navigator = LocalNavigator.current
    val bus = LocalResultEventBus.current
    val initialPick = remember(initialLatitude, initialLongitude) { LocationPicker.initialPick(initialLatitude, initialLongitude) }
    LaunchedEffect(viewModel) { viewModel.init(initialPick) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    val camera = rememberLocationPickerCameraState(initialPick)
    LaunchedEffect(viewModel, camera) {
        viewModel.jumps.collect { camera.animateTo(it, LocationPicker.JUMP_TO_ME_ZOOM) }
    }
    LocationPickerScreen(state, camera, snackbar) { action ->
        when (action) {
            LocationPickerAction.Back -> navigator.back()
            LocationPickerAction.Confirm -> state.pick?.let { p ->
                bus.sendResult(NavResults.LocationPicked, PickedLocation(p.latitude, p.longitude))
                navigator.back()
            }
            else -> viewModel.onAction(action)
        }
    }
}

/** Stateless "Ort des Events". */
@Composable
fun LocationPickerScreen(
    state: LocationPickerUiState,
    cameraState: OsmCameraState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (LocationPickerAction) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.locPickTitle),
                onBack = { onAction(LocationPickerAction.Back) },
                actions = {
                    IconButton(
                        onClick = { onAction(LocationPickerAction.JumpToMe) },
                        enabled = !state.locating,
                        modifier = Modifier.testTag(LocationPickerTags.JUMP),
                    ) {
                        if (state.locating) {
                            AppLoadingIndicator(size = 24.dp, contentDescription = stringResource(L10nR.string.locPickJumpToMe))
                        } else {
                            Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(L10nR.string.locPickJumpToMe))
                        }
                    }
                },
            )
        },
        bottomBar = { ConfirmBar(state, onAction) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(LocationPickerTags.HINT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(state.pick != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hint") { picked ->
                        Text(
                            stringResource(if (picked) L10nR.string.locPickHintDone else L10nR.string.locPickHint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LocationPickerMap(
                    cameraState = cameraState,
                    picked = state.pick,
                    onPick = { onAction(LocationPickerAction.Pick(it)) },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(LocationPickerTags.MAP),
                )
                MapZoomControls(cameraState, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
            }
        }
    }
}

@Composable
private fun ConfirmBar(state: LocationPickerUiState, onAction: (LocationPickerAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            val pick = state.pick
            AnimatedVisibility(pick != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                MonoText(
                    pick?.format(LocationPicker.COORDINATE_DIGITS).orEmpty(),
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .testTag(LocationPickerTags.COORDS),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Button(
                onClick = { onAction(LocationPickerAction.Confirm) },
                enabled = pick != null,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.extendedColors.textTertiary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(LocationPickerTags.CONFIRM),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.locPickConfirm), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Preview
@Composable
private fun LocationPickerPreview() {
    val pick = LatLng(48.1351, 11.582)
    EinundzwanzigTheme {
        LocationPickerScreen(
            LocationPickerUiState(pick = pick),
            rememberLocationPickerCameraState(pick),
            SnackbarHostState(),
        ) {}
    }
}
