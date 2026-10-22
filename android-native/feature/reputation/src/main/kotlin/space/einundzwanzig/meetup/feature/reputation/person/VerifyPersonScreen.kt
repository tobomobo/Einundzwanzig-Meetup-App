package space.einundzwanzig.meetup.feature.reputation.person

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.VerifyPersonQrCamera
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.greenColor
import space.einundzwanzig.meetup.feature.reputation.common.openNostr
import space.einundzwanzig.meetup.feature.reputation.common.orangeColor
import space.einundzwanzig.meetup.feature.reputation.common.redColor
import space.einundzwanzig.meetup.feature.reputation.common.shortenNpub
import space.einundzwanzig.meetup.feature.reputation.common.tertiaryTextColor
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of "Person prüfen". */
internal object VerifyPersonTags {
    const val INPUT = "vp_input"
    const val SCAN = "vp_scan"
    const val CHECK = "vp_check"
    const val ERROR = "vp_error"
    const val LOADING = "vp_loading"
    const val STATUS = "vp_status"
    const val HINT = "vp_hint"
    fun node(index: Int) = "vp_node_$index"
}

/** Stateful entry of [VerifyPerson][space.einundzwanzig.meetup.core.navigation.VerifyPerson] (spec 04 §2.7). */
@Composable
internal fun VerifyPersonRoute(
    initialTargetNpub: String?,
    viewModel: VerifyPersonViewModel = hiltViewModel<VerifyPersonViewModel, VerifyPersonViewModel.Factory>(
        key = "verify-person:${initialTargetNpub.orEmpty()}",
        creationCallback = { factory -> factory.create(initialTargetNpub) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    NavResultEffect(NavResults.NpubScanned) { npub -> viewModel.onAction(VerifyPersonAction.Scanned(npub)) }
    VerifyPersonScreen(
        state = state,
        onAction = { action ->
            when (action) {
                VerifyPersonAction.OpenScanner -> navigator.navigate(VerifyPersonQrCamera)
                is VerifyPersonAction.OpenNostr -> openNostr(context, action.npub)
                else -> viewModel.onAction(action)
            }
        },
        onBack = navigator::back,
    )
}

/** Visual mapping of a presence check (spec 04 §8.12). */
private data class StatusStyle(val color: Color, val icon: ImageVector, val title: String, val sub: String?)

@Composable
private fun statusStyle(result: PresenceCheck): StatusStyle = when {
    result.isSelf -> StatusStyle(cyanColor, Icons.Rounded.Person, stringResource(L10nR.string.vpSelfTitle), null)
    result.isDirect -> StatusStyle(
        greenColor,
        Icons.Rounded.Verified,
        stringResource(L10nR.string.vpDirectTitle),
        if (result.sharedMeetups.size == 1) {
            stringResource(L10nR.string.vpDirectSubOne)
        } else {
            stringResource(L10nR.string.vpDirectSub, result.sharedMeetups.size)
        },
    )
    result.found -> StatusStyle(
        orangeColor,
        Icons.Rounded.Hub,
        stringResource(L10nR.string.vpIndirectTitle, result.degree),
        stringResource(L10nR.string.vpIndirectSub),
    )
    else -> StatusStyle(
        tertiaryTextColor,
        Icons.Rounded.LinkOff,
        stringResource(L10nR.string.vpNoneTitle),
        stringResource(if (result.targetInNetwork) L10nR.string.vpNoneSub else L10nR.string.vpNotInNetwork),
    )
}

/** Stateless "Person prüfen" screen (spec 04 §2.7). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VerifyPersonScreen(
    state: VerifyPersonUiState,
    onAction: (VerifyPersonAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val orange = orangeColor
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AppLargeTopBar(title = stringResource(L10nR.string.vpTitle), onBack = onBack, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(stringResource(L10nR.string.vpIntro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.input,
                onValueChange = { onAction(VerifyPersonAction.InputChanged(it)) },
                textStyle = MonoStyle.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                placeholder = { Text(stringResource(L10nR.string.vpEnterNpub)) },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAction(VerifyPersonAction.Check) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = orange,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(VerifyPersonTags.INPUT),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onAction(VerifyPersonAction.OpenScanner) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag(VerifyPersonTags.SCAN),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(L10nR.string.vpScanQr))
                }
                Button(
                    onClick = { onAction(VerifyPersonAction.Check) },
                    enabled = !state.checking,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag(VerifyPersonTags.CHECK),
                ) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(L10nR.string.vpCheck), fontWeight = FontWeight.Bold)
                }
            }
            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                ErrorBox(
                    stringResource(
                        when (error) {
                            VerifyPersonError.INVALID_NPUB -> L10nR.string.vpInvalidNpub
                            VerifyPersonError.CHECK_FAILED -> L10nR.string.vpNoneTitle
                        },
                    ),
                )
            }
            if (state.checking) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp)
                        .testTag(VerifyPersonTags.LOADING),
                ) {
                    LoadingIndicator(color = orange, modifier = Modifier.size(56.dp))
                }
            }
            val result = state.result
            if (result != null && !state.checking) {
                Spacer(Modifier.height(16.dp))
                ResultView(result, onAction)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ErrorBox(text: String) {
    val red = redColor
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(red.copy(alpha = 0.1f), shape)
            .border(0.5.dp, red.copy(alpha = 0.3f), shape)
            .padding(12.dp)
            .testTag(VerifyPersonTags.ERROR)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultView(result: PresenceCheck, onAction: (VerifyPersonAction) -> Unit) {
    val style = statusStyle(result)
    val reducedMotion = rememberReducedMotion()
    val pop = remember(result) { Animatable(if (reducedMotion) 1f else 0.6f) }
    LaunchedEffect(result) { pop.animateTo(1f, ExpressiveSprings.fastSpatial()) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(style.color.copy(alpha = 0.1f), shape)
            .border(0.5.dp, style.color.copy(alpha = 0.35f), shape)
            .padding(20.dp)
            .testTag(VerifyPersonTags.STATUS)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .background(style.color.copy(alpha = 0.15f), MaterialShapes.Cookie9Sided.toShape()),
        ) {
            Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            style.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        style.sub?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
    if (result.found && !result.isSelf && result.path.size >= 2) {
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(L10nR.string.vpPathTitle).uppercase(),
            color = tertiaryTextColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        result.path.forEachIndexed { i, npub ->
            if (i > 0) Connector()
            // Staggered expressive entrance; starts visible (no animation) under reduced motion.
            val shown = remember(result) { MutableTransitionState(reducedMotion) }.apply { targetState = true }
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(tween(durationMillis = 220, delayMillis = i * 60)) +
                    scaleIn(ExpressiveSprings.fastSpatial(), initialScale = 0.9f),
            ) {
                PathNode(index = i, last = i == result.path.lastIndex, npub = npub, onOpen = { onAction(VerifyPersonAction.OpenNostr(npub)) })
            }
        }
    }
    if (result.found && !result.isSelf) {
        Spacer(Modifier.height(20.dp))
        val orange = orangeColor
        val shape2 = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(orange.copy(alpha = 0.08f), shape2)
                .border(0.5.dp, orange.copy(alpha = 0.2f), shape2)
                .padding(12.dp)
                .testTag(VerifyPersonTags.HINT),
        ) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = orange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.vpTrustNote), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PathNode(index: Int, last: Boolean, npub: String, onOpen: () -> Unit) {
    val first = index == 0
    val color = when {
        first -> cyanColor
        last -> orangeColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        first -> stringResource(L10nR.string.vpYou)
        last -> stringResource(L10nR.string.vpTarget)
        else -> shortenNpub(npub)
    }
    val icon = when {
        first -> Icons.Rounded.Person
        last -> Icons.Rounded.Flag
        else -> Icons.Rounded.PersonOutline
    }
    val shape = RoundedCornerShape(10.dp)
    val openLabel = stringResource(R.string.native_openInNostr)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(
                if (first || last) 1.dp else 0.5.dp,
                if (first || last) color.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .then(if (first) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen))
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(VerifyPersonTags.node(index)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(color.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!first && !last) {
                // PARITY: the middle node repeats the short npub on a second line.
                Text(shortenNpub(npub), style = MonoStyle, color = tertiaryTextColor, fontSize = 10.sp)
            }
        }
        if (!first) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = tertiaryTextColor.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Connector() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 29.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Rounded.Groups, contentDescription = null, tint = tertiaryTextColor, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(L10nR.string.vpMetAt), color = tertiaryTextColor, fontSize = 10.sp, fontStyle = FontStyle.Italic)
    }
}

@Preview
@Composable
private fun VerifyPersonPreview() {
    EinundzwanzigTheme {
        VerifyPersonScreen(
            state = VerifyPersonUiState(
                input = "npub1target",
                result = PresenceCheck(
                    targetNpub = "npub1target",
                    degree = 2,
                    path = listOf("npub1me", "npub1middlexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "npub1target"),
                    sharedMeetups = emptySet(),
                    targetInNetwork = true,
                    targetTotalMeetups = 3,
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
