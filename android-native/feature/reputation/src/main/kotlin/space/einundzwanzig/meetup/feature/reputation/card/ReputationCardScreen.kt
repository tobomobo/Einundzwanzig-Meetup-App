package space.einundzwanzig.meetup.feature.reputation.card

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.share.rememberComposableCapturer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.CARD_SHARE_FILE
import space.einundzwanzig.meetup.feature.reputation.common.localizedLevel
import space.einundzwanzig.meetup.feature.reputation.common.shareCapturedCard
import space.einundzwanzig.meetup.feature.reputation.common.tertiaryTextColor
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the reputation card screen. */
internal object ReputationCardTags {
    const val CARD = "rep_card"
    const val SHARE_BUTTON = "rep_card_share"
}

/** Stateful entry of [ReputationCard][space.einundzwanzig.meetup.core.navigation.ReputationCard] (spec 04 §2.2). */
@Composable
internal fun ReputationCardRoute(viewModel: ReputationCardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capturer = rememberComposableCapturer()
    var cardSize by remember { mutableStateOf(DpSize.Zero) }
    var sharing by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    val shareText = stringResource(L10nR.string.rcShareText)

    ReputationCardScreen(
        state = state,
        isSharing = sharing,
        onAction = { action ->
            when (action) {
                ReputationCardAction.Share -> {
                    val info = state.info
                    // Guard re-entry with `sharing`, reset in finally (spec 04 §2.2).
                    if (info != null && !sharing && cardSize != DpSize.Zero) {
                        sharing = true
                        scope.launch {
                            try {
                                shareCapturedCard(context, capturer, cardSize.width, cardSize.height, fontScale, CARD_SHARE_FILE, shareText) {
                                    ProfileCard(info)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                host.showAppSnackbar(context.getString(L10nR.string.rcShareError, e.toString()), Tone.Error)
                            } finally {
                                sharing = false
                            }
                        }
                    }
                }
                ReputationCardAction.Retry -> viewModel.onAction(action)
            }
        },
        onBack = navigator::back,
        onCardSize = { cardSize = it },
        snackbarHost = { if (appHost == null) AppSnackbarHost(localHost) },
    )
}

/** Stateless "Reputations-Profil" screen (spec 04 §2.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReputationCardScreen(
    state: ReputationCardUiState,
    isSharing: Boolean,
    onAction: (ReputationCardAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCardSize: (DpSize) -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val density = LocalDensity.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = snackbarHost,
        topBar = { AppMediumTopBar(title = stringResource(L10nR.string.rcTitle), onBack = onBack, scrollBehavior = scrollBehavior) },
    ) { padding ->
        AnimatedContent(
            targetState = when {
                state.isLoading -> 0
                state.info == null -> 1
                else -> 2
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "repCardState",
            modifier = Modifier.padding(padding),
        ) { phase ->
            when (phase) {
                0 -> FullScreenLoading()
                1 -> EmptyState(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.native_repLoadError),
                    subtitle = "",
                    actionLabel = stringResource(R.string.native_retry),
                    onAction = { onAction(ReputationCardAction.Retry) },
                    accentColor = MaterialTheme.colorScheme.error,
                )
                else -> state.info?.let { info ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        ProfileCard(
                            info = info,
                            modifier = Modifier
                                .testTag(ReputationCardTags.CARD)
                                .onSizeChanged { with(density) { onCardSize(DpSize(it.width.toDp(), it.height.toDp())) } },
                        )
                        Spacer(Modifier.height(24.dp))
                        ShareButton(isSharing) { onAction(ReputationCardAction.Share) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShareButton(isSharing: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isSharing,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(ReputationCardTags.SHARE_BUTTON),
    ) {
        if (isSharing) {
            LoadingIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Icon(Icons.Rounded.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(if (isSharing) L10nR.string.rcSaving else L10nR.string.rcShareImage),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

/** The branded card (capture boundary content, spec 04 §2.2 layout). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProfileCard(info: ReputationCardInfo, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val orange = colors.primary
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(shape, Shadow(radius = 16.dp, spread = (-4).dp, color = orange.copy(alpha = 0.10f), offset = DpOffset(0.dp, 6.dp)))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        orange.copy(alpha = 0.07f).compositeOver(colors.surface),
                        orange.copy(alpha = 0.11f).compositeOver(colors.surface),
                        colors.surface,
                    ),
                ),
            )
            .border(1.2.dp, orange.copy(alpha = 0.45f), shape),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 24.dp, y = (-24).dp)
                .size(140.dp)
                .background(orange.copy(alpha = 0.16f), MaterialShapes.Cookie9Sided.toShape()),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("EINUNDZWANZIG", style = MonoStyle, color = orange, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(L10nR.string.rcMember), color = colors.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = info.displayName ?: stringResource(L10nR.string.rcMember),
                color = colors.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (info.hasNoData) {
                Spacer(Modifier.height(12.dp))
                NoDataBox()
                Spacer(Modifier.height(12.dp))
            } else {
                ScoreHero(info.score)
                Spacer(Modifier.height(12.dp))
                LevelPill(info.level)
                Spacer(Modifier.height(20.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GridTile(Icons.Rounded.Style, info.totalBadges, stringResource(L10nR.string.rcLabelBadges))
                    GridTile(Icons.Rounded.Groups, info.uniqueMeetups, stringResource(L10nR.string.rcLabelMeetups))
                    GridTile(Icons.Rounded.LocationCity, info.uniqueCities, stringResource(L10nR.string.rcLabelCities))
                    GridTile(Icons.Rounded.VerifiedUser, info.uniqueSigners, stringResource(L10nR.string.rcLabelSigners))
                    GridTile(Icons.Rounded.CalendarMonth, info.accountAgeDays, stringResource(L10nR.string.rcLabelAge))
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.outlineVariant),
            )
            Spacer(Modifier.height(12.dp))
            Text("21Adress · Web of Trust", style = MonoStyle, color = tertiaryTextColor, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun NoDataBox() {
    val colors = MaterialTheme.colorScheme
    val orange = colors.primary
    val shape = RoundedCornerShape(18.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow.copy(alpha = 0.8f), shape)
            .border(0.5.dp, colors.outlineVariant, shape)
            .padding(18.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(orange.copy(alpha = 0.13f), CircleShape)
                .border(1.dp, orange.copy(alpha = 0.24f), CircleShape),
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = orange, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(L10nR.string.rcNoData), color = colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.native_rcNoDataHint), color = tertiaryTextColor, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ScoreHero(score: Double) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .dropShadow(shape, Shadow(radius = 20.dp, color = colors.primary.copy(alpha = 0.3f)))
            .background(Brush.linearGradient(listOf(colors.primary, lerp(colors.primary, colors.surface, 0.12f))), shape)
            .padding(horizontal = 28.dp, vertical = 18.dp),
    ) {
        Text(score.toStringAsFixed(1), color = colors.onPrimary, fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
        Text(
            text = stringResource(L10nR.string.rcLabelScore).uppercase(),
            color = colors.onPrimary.copy(alpha = 0.87f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun LevelPill(level: String) {
    val orange = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(orange.copy(alpha = 0.12f), shape)
            .border(0.5.dp, orange.copy(alpha = 0.4f), shape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = orange, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(localizedLevel(level).uppercase(), color = orange, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
}

@Composable
private fun GridTile(icon: ImageVector, value: Int, label: String) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(92.dp)
            .background(colors.surfaceContainerLow, shape)
            .border(0.5.dp, colors.outlineVariant, shape)
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(value.toString(), color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Preview
@Composable
private fun ReputationCardScreenPreview() {
    EinundzwanzigTheme {
        ReputationCardScreen(
            state = ReputationCardUiState(
                isLoading = false,
                info = ReputationCardInfo("Satoshi", 12.3, "AKTIV", 8, 4, 4, 3, 120),
            ),
            isSharing = false,
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ReputationCardNoDataPreview() {
    EinundzwanzigTheme {
        ReputationCardScreen(
            state = ReputationCardUiState(isLoading = false, info = ReputationCardInfo(null, 0.0, "NEU", 0, 0, 0, 0, 0)),
            isSharing = false,
            onAction = {},
            onBack = {},
        )
    }
}
