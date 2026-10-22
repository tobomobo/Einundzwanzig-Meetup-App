package space.einundzwanzig.meetup.feature.wallet.details

import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.badge.BadgeArt
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestFace
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.share.rememberComposableCapturer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.PreviewData
import space.einundzwanzig.meetup.feature.wallet.R
import space.einundzwanzig.meetup.feature.wallet.crest.ProvideWalletCrests
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.share.BadgeShareCard
import space.einundzwanzig.meetup.feature.wallet.share.ShareCard
import space.einundzwanzig.meetup.feature.wallet.ui.artSeed
import space.einundzwanzig.meetup.feature.wallet.ui.dartUpper
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object BadgeDetailsTestTags {
    const val HASH = "details.hash"
    const val NOT_FOUND = "details.notFound"
    const val CONTENT = "details.content"
}

/** User intents of the details screen. */
internal sealed interface BadgeDetailsAction {
    data object Back : BadgeDetailsAction
    data object Share : BadgeDetailsAction
    data object CopyHash : BadgeDetailsAction
}

/** Stateful badge details (spec 03 §2.2): share as image, copy the hash. */
@Composable
fun BadgeDetailsRoute(key: BadgeDetails) {
    ProvideWalletCrests {
        BadgeDetailsRouteContent(
            viewModel = hiltViewModel<BadgeDetailsViewModel, BadgeDetailsViewModel.Factory>(creationCallback = { it.create(key) }),
        )
    }
}

/**
 * Renders [badge]'s share card and opens the share sheet; false when no chooser could start.
 * Throws on render or write errors.
 */
internal typealias BadgeImageSharer = suspend (context: Context, badge: MeetupBadge, tagline: String) -> Boolean

/** Captures [BadgeShareCard] (360 × 560 dp × 3, dark) and shares it as `einundzwanzig_badge.png`. */
@Composable
private fun rememberBadgeImageSharer(): BadgeImageSharer {
    val capturer = rememberComposableCapturer()
    return remember(capturer) {
        { context, badge, tagline ->
            val bitmap = capturer.capture(
                width = ShareCard.WIDTH_DP.dp,
                height = ShareCard.HEIGHT_DP.dp,
                pixelRatio = ShareCard.PIXEL_RATIO,
                settleMillis = ShareCard.SETTLE_MS,
            ) {
                EinundzwanzigTheme(themeMode = ThemeMode.DARK) { BadgeShareCard(badge) }
            }
            ShareHelper.shareImage(
                context = context,
                bitmap = bitmap,
                fileName = ShareCard.FILE_NAME,
                subject = badge.meetupName,
                text = BadgeFormat.shareImageText(badge, tagline),
            )
        }
    }
}

@Composable
internal fun BadgeDetailsRouteContent(
    viewModel: BadgeDetailsViewModel,
    shareImage: BadgeImageSharer = rememberBadgeImageSharer(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val snackbars = appHost ?: localHost
    val scope = rememberCoroutineScope()
    val tagline = stringResource(L10nR.string.badgeShareTagline)
    val hashCopied = stringResource(L10nR.string.badgeHashCopied)

    BadgeDetailsScreen(
        state = state,
        snackbarHostState = if (appHost == null) localHost else null,
        onAction = onAction@{ action ->
            when (action) {
                BadgeDetailsAction.Back -> navigator.back()
                BadgeDetailsAction.CopyHash -> {
                    SecretClipboard.copyPlain(context, state.hash, "hash")
                    scope.launch { snackbars.showAppSnackbar(hashCopied, durationMillis = AppSnackbarDurations.Short) }
                }
                BadgeDetailsAction.Share -> {
                    val badge = state.badge ?: return@onAction
                    if (!viewModel.beginShare()) return@onAction
                    scope.launch {
                        try {
                            if (!shareImage(context, badge, tagline)) {
                                // No chooser could start (spec 03 §2.2 step 5).
                                AppLogger.warn("Wallet", "Badge-Bild teilen: kein Ziel zum Teilen")
                                snackbars.showAppSnackbar(context.getString(L10nR.string.repShareError, NO_SHARE_TARGET), Tone.Error)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.warn("Wallet", "Badge-Bild teilen fehlgeschlagen", e)
                            snackbars.showAppSnackbar(context.getString(L10nR.string.repShareError, e.toString()), Tone.Error)
                        } finally {
                            viewModel.endShare()
                        }
                    }
                }
            }
        },
    )
}

/** Error detail of `repShareError` when no app can receive the image. */
internal const val NO_SHARE_TARGET = "no share target"

/** Stateless badge details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BadgeDetailsScreen(
    state: BadgeDetailsUiState,
    onAction: (BadgeDetailsAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val badge = state.badge
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppMediumTopBar(
                title = badge?.let { dartUpper(it.meetupName) }.orEmpty(),
                onBack = { onAction(BadgeDetailsAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (badge != null) {
                        IconButton(onClick = { onAction(BadgeDetailsAction.Share) }, enabled = !state.sharing) {
                            if (state.sharing) {
                                AppLoadingIndicator(size = 24.dp)
                            } else {
                                Icon(Icons.Rounded.Share, contentDescription = stringResource(L10nR.string.badgeShare))
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { if (snackbarHostState != null) AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            badge != null -> DetailsContent(badge, state, onAction, Modifier.padding(padding))
            !state.loaded -> FullScreenLoading(Modifier.padding(padding), message = stringResource(L10nR.string.loading))
            else -> EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.native_wallet_badge_not_found),
                subtitle = stringResource(R.string.native_wallet_badge_not_found_sub),
                modifier = Modifier.padding(padding).testTag(BadgeDetailsTestTags.NOT_FOUND),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailsContent(badge: MeetupBadge, state: BadgeDetailsUiState, onAction: (BadgeDetailsAction) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val ext = MaterialTheme.extendedColors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(BadgeDetailsTestTags.CONTENT),
    ) {
        HeroCard(badge)
        Spacer(Modifier.height(20.dp))

        SectionCard(stringResource(L10nR.string.badgeTimestamp), Icons.Rounded.AccessTime, colors.primary) {
            InfoRow(stringResource(L10nR.string.badgeMeetupDate), BadgeFormat.datePadded(badge.date))
            InfoRow(
                stringResource(L10nR.string.badgeBlockAtScan),
                if (badge.blockHeight > 0) BadgeFormat.blockDetails(badge.blockHeight, "") else stringResource(L10nR.string.badgeUnknown),
                valueColor = if (badge.blockHeight > 0) colors.primary else ext.textTertiary,
                mono = true,
            )
            if (badge.claimTimestamp > 0) {
                InfoRow(stringResource(L10nR.string.badgeScanTime), BadgeFormat.scanTime(badge.claimTimestamp))
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionCard(stringResource(L10nR.string.badgeDetailsTitle), Icons.Rounded.Badge, ext.community) {
            InfoRow(stringResource(L10nR.string.badgeMeetup), badge.meetupName)
            InfoRow(
                stringResource(L10nR.string.badgeTransmission),
                when (badge.delivery) {
                    "rolling_qr" -> stringResource(L10nR.string.badgeRollingQr)
                    "nfc" -> stringResource(L10nR.string.badgeNfcTag)
                    else -> badge.delivery
                },
            )
            InfoRow(stringResource(L10nR.string.badgeMeetupId), badge.meetupEventId.ifEmpty { "—" })
        }
        Spacer(Modifier.height(12.dp))

        SectionCard(stringResource(L10nR.string.badgeProofTitle), Icons.Rounded.Security, ext.success) {
            val (sigText, sigColor) = when (badge.sigVersion) {
                2L -> stringResource(L10nR.string.badgeSchnorrSig) to ext.success
                1L -> "HMAC (Legacy v1)" to colors.primary
                else -> stringResource(L10nR.string.badgeNoSignature) to ext.textTertiary
            }
            InfoRow(stringResource(L10nR.string.badgeSignatureType), sigText, valueColor = sigColor)
            InfoRow(stringResource(L10nR.string.badgeOrganizerNpub), BadgeFormat.shortNpubDetails(badge.signerNpub), mono = true)
            if (badge.sigId.isNotEmpty()) {
                InfoRow("Nostr Event-ID", BadgeFormat.shortSigId(badge.sigId), mono = true)
            }
            InfoRow(
                stringResource(L10nR.string.badgeClaimBinding),
                if (badge.isClaimed) stringResource(L10nR.string.badgeBound) else stringResource(L10nR.string.badgeNotBound),
                valueColor = if (badge.isClaimed) ext.success else colors.error,
            )
            if (badge.isRetroactive) {
                InfoRow(stringResource(L10nR.string.badgeNote), stringResource(L10nR.string.badgeClaimedLater), valueColor = colors.primary)
            }
            if (!badge.presenceVerified) {
                InfoRow(stringResource(L10nR.string.badgeUnverified), stringResource(L10nR.string.badgeUnverifiedInfo), valueColor = ext.textTertiary)
            }
        }
        Spacer(Modifier.height(12.dp))

        HashCard(state.hash, onClick = { onAction(BadgeDetailsAction.CopyHash) })
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onAction(BadgeDetailsAction.Share) },
            enabled = !state.sharing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.badgeShareCaps), fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onAction(BadgeDetailsAction.Back) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.badgeClose), color = colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(badge: MeetupBadge) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.5.dp, colors.primary.copy(alpha = 0.5f), shape)
            .padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(168.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
                MeetupCrestFace(
                    meetupName = badge.meetupName,
                    imageUrl = badge.coverUrl.ifEmpty { null },
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = badge.meetupName,
                ) {
                    BadgeArt(badge.artSeed(), Modifier.fillMaxSize())
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(6.dp, 6.dp)
                    .size(40.dp)
                    .background(colors.surfaceContainer, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Verified, contentDescription = null, tint = colors.primary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            dartUpper(badge.meetupName),
            color = colors.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("₿", color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                "Block " + BadgeFormat.blockDetails(badge.blockHeight, stringResource(L10nR.string.badgeUnknown)),
                color = colors.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (badge.isOrganizer) L10nR.string.badgeOrganizerTitle else L10nR.string.badgeProofOfAttendance),
            color = if (badge.isOrganizer) colors.onSurfaceVariant else colors.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (badge.isOrganizer) L10nR.string.badgeOrganizerDesc else L10nR.string.badgeProofDesc),
            color = colors.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, color: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, color.copy(alpha = 0.25f), shape),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(dartUpper(title), color = color, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, modifier = Modifier.width(130.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun HashCard(hash: String, onClick: () -> Unit) {
    val purple = MaterialTheme.extendedColors.purple
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(BadgeDetailsTestTags.HASH),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = purple, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.badgeVerificationHash), color = purple, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary, modifier = Modifier.size(13.dp))
            }
            Text(hash, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 1.5.em)
        }
    }
}

@Preview(widthDp = 380, heightDp = 1400)
@Composable
private fun BadgeDetailsScreenPreview() {
    val badge = PreviewData.badges().first()
    EinundzwanzigTheme {
        BadgeDetailsScreen(BadgeDetailsUiState(loaded = true, badge = badge, hash = badge.verificationHash()), onAction = {})
    }
}
