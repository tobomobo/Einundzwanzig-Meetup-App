package space.einundzwanzig.meetup.feature.reputation.verify

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.LayerLoad
import space.einundzwanzig.meetup.feature.reputation.common.amberColor
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.greenColor
import space.einundzwanzig.meetup.feature.reputation.common.orangeColor
import space.einundzwanzig.meetup.feature.reputation.common.redColor
import space.einundzwanzig.meetup.feature.reputation.common.shortenNpubVerify
import space.einundzwanzig.meetup.feature.reputation.common.verifyMessage
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the verify screen. */
internal object ReputationVerifyTags {
    const val INPUT = "rv_input"
    const val PASTE = "rv_paste"
    const val VERIFY = "rv_verify"
    const val RESULT = "rv_result"
    const val LAYERS_STATUS = "rv_layers_status"
}

/** Stateful entry of [ReputationVerify][space.einundzwanzig.meetup.core.navigation.ReputationVerify] (spec 04 §2.4). */
@Composable
internal fun ReputationVerifyRoute(
    initialInput: String?,
    viewModel: ReputationVerifyViewModel = hiltViewModel<ReputationVerifyViewModel, ReputationVerifyViewModel.Factory>(
        key = "reputation-verify:${initialInput.orEmpty()}",
        creationCallback = { factory -> factory.create(initialInput) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    ReputationVerifyScreen(
        state = state,
        onAction = viewModel::onAction,
        onPaste = { viewModel.onAction(ReputationVerifyAction.Paste(readClipboardText(context))) },
        onBack = navigator::back,
    )
}

private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** Stateless verify-string / npub screen (spec 04 §2.4). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReputationVerifyScreen(
    state: ReputationVerifyUiState,
    onAction: (ReputationVerifyAction) -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cyan = cyanColor
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AppLargeTopBar(title = stringResource(L10nR.string.rvTitle), onBack = onBack, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            val infoShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, infoShape)
                    .border(1.dp, cyan.copy(alpha = 0.2f), infoShape)
                    .padding(16.dp),
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = cyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(L10nR.string.rvIntro1) + stringResource(L10nR.string.rvIntro2),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = state.input,
                onValueChange = { onAction(ReputationVerifyAction.InputChanged(it)) },
                textStyle = MonoStyle.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                placeholder = { Text(stringResource(R.string.native_rvInputHint), style = MonoStyle.copy(fontSize = 12.sp)) },
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onAction(ReputationVerifyAction.Verify) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    cursorColor = cyan,
                ),
                trailingIcon = {
                    IconButton(onClick = onPaste, modifier = Modifier.testTag(ReputationVerifyTags.PASTE)) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.native_rvPaste))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ReputationVerifyTags.INPUT),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onAction(ReputationVerifyAction.Verify) },
                enabled = !state.isVerifying,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cyan,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = cyan.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(ReputationVerifyTags.VERIFY),
            ) {
                if (state.isVerifying) {
                    LoadingIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (state.isVerifying) L10nR.string.rvChecking else L10nR.string.rvTitle), fontWeight = FontWeight.Bold)
            }
            if (state.isVerifying) {
                Spacer(Modifier.height(12.dp))
                StatusLine(stringResource(L10nR.string.rvCheckingSignature))
            }
            state.result?.let { result ->
                Spacer(Modifier.height(24.dp))
                ResultHeader(result)
                Spacer(Modifier.height(16.dp))
                InfoRows(result)
            }
            AnimatedVisibility(visible = state.layers.isLoading, enter = fadeIn() + expandVertically()) {
                Column {
                    Spacer(Modifier.height(20.dp))
                    StatusLine(
                        text = stringResource(
                            when (state.layers.currentLoad) {
                                LayerLoad.LIGHTNING -> L10nR.string.rvCheckingLightning
                                LayerLoad.NIP05 -> L10nR.string.rvCheckingNip05
                                LayerLoad.SOCIAL, null -> L10nR.string.rvCheckingNostr
                            },
                        ),
                        modifier = Modifier.testTag(ReputationVerifyTags.LAYERS_STATUS),
                    )
                }
            }
            state.layersInput?.let {
                Spacer(Modifier.height(20.dp))
                ReputationLayers(it)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        LoadingIndicator(color = cyanColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultHeader(result: ProofVerifyResult<ReputationEvent>) {
    val (color, icon, title) = when (result.level) {
        VerifyLevel.FULL -> Triple(greenColor, Icons.Rounded.Verified, stringResource(L10nR.string.rvFullyVerified))
        VerifyLevel.PARTIAL -> Triple(amberColor, Icons.Rounded.Shield, stringResource(L10nR.string.rvPartiallyVerified))
        VerifyLevel.SIGNATURE_ONLY -> Triple(orangeColor, Icons.Outlined.Lock, stringResource(R.string.native_rvFormatOnly))
        VerifyLevel.INVALID -> Triple(redColor, Icons.Rounded.Dangerous, stringResource(L10nR.string.rvInvalid))
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), shape)
            .border(2.dp, color.copy(alpha = 0.4f), shape)
            .padding(20.dp)
            .testTag(ReputationVerifyTags.RESULT)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(color.copy(alpha = 0.15f), MaterialShapes.Cookie9Sided.toShape()),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        }
        result.reputation?.let {
            Spacer(Modifier.height(10.dp))
            Text(it.nickname, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = verifyMessage(result.message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InfoRows(result: ProofVerifyResult<ReputationEvent>) {
    Column {
        val platform = result.platform
        val username = result.claimedUsername
        if (platform != null && username != null) {
            InfoRow(Icons.Rounded.Language, stringResource(L10nR.string.rvLabelPlatform), platform)
            InfoRow(Icons.Rounded.Person, stringResource(L10nR.string.rvLabelUsername), username)
            if (result.proofInEvent) {
                InfoRow(Icons.Rounded.CheckCircle, stringResource(L10nR.string.rvPlatformProof), stringResource(L10nR.string.rvConfirmedInEvent), greenColor)
            }
        }
        result.npub?.let { InfoRow(Icons.Rounded.Key, "npub", shortenNpubVerify(it), mono = true) }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, valueColor: Color? = null, mono: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("$label: ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(
            text = value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
private fun ReputationVerifyPreview() {
    EinundzwanzigTheme {
        ReputationVerifyScreen(
            state = ReputationVerifyUiState(
                input = "21rep::npub1abc::telegram::satoshi::sig=…",
                result = ProofVerifyResult(
                    level = VerifyLevel.FULL,
                    message = VerifyMessage.Full,
                    npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsxxxxxx",
                    platform = "telegram",
                    claimedUsername = "satoshi",
                    reputation = ReputationEvent("hex", "npub1…", "id", "sig", 1_700_000_000, nickname = "Satoshi", totalBadges = 8, boundBadges = 8),
                    proofInEvent = true,
                ),
            ),
            onAction = {},
            onPaste = {},
            onBack = {},
        )
    }
}
