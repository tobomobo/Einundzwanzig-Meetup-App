package space.einundzwanzig.meetup.feature.community.v4v

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.community.v4v.LightningInvoiceLauncher
import space.einundzwanzig.meetup.data.community.v4v.V4V_LIGHTNING_ADDRESS
import space.einundzwanzig.meetup.data.community.v4v.V4vErrorKind
import space.einundzwanzig.meetup.feature.community.common.ObserveAsEvents
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object V4vTestTags {
    const val AMOUNT = "v4vAmount"
    const val DONATE = "v4vDonate"
    const val ERROR = "v4vError"
    const val LOADING = "v4vLoading"
    const val NO_WALLET_SHEET = "v4vNoWalletSheet"
}

/** Snackbar time of Flutter's default `SnackBar` (4 s). */
internal const val FLUTTER_SNACKBAR_MS: Long = 4_000

@Composable
fun ValueForValueRoute(viewModel: ValueForValueViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(L10nR.string.v4vCopied)

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ValueForValueEvent.OpenInvoice ->
                if (!LightningInvoiceLauncher.openWithChooser(context, event.invoice)) {
                    viewModel.onAction(ValueForValueAction.WalletUnavailable(event.invoice))
                }
            is ValueForValueEvent.CopyInvoice -> {
                SecretClipboard.copyPlain(context, event.invoice, "lightning invoice")
                snackbar.showAppSnackbar(copied, durationMillis = FLUTTER_SNACKBAR_MS)
            }
        }
    }

    ValueForValueScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = navigator::back,
        snackbarHostState = snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ValueForValueScreen(
    state: ValueForValueUiState,
    onAction: (ValueForValueAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val orange = MaterialTheme.colorScheme.primary
    val ext = MaterialTheme.extendedColors
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.v4vTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Bolt badge: radial orange glow, cookie shape (Expressive MaterialShapes).
            val shape = MaterialShapes.Cookie9Sided.toShape()
            Box(
                Modifier
                    .size(84.dp)
                    .background(Brush.radialGradient(listOf(orange.copy(alpha = 0.25f), orange.copy(alpha = 0.04f))), shape)
                    .border(1.dp, orange.copy(alpha = 0.35f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = orange, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(L10nR.string.v4vHeadline),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp, lineHeight = (14.5 * 1.6).sp)
                    Text(stringResource(L10nR.string.v4vExplain1), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(L10nR.string.v4vExplain2), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(28.dp))
            val amountLabel = stringResource(L10nR.string.v4vAmountLabel)
            Text(
                amountLabel.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
                color = orange,
            )
            Spacer(Modifier.height(12.dp))
            AmountField(
                value = state.amount,
                label = amountLabel,
                onValueChange = { onAction(ValueForValueAction.AmountChanged(it)) },
                onDone = { onAction(ValueForValueAction.Donate) },
            )
            // Keeps the last error text while the exit animation runs.
            var shownError by remember { mutableStateOf(state.error) }
            if (state.error != null) shownError = state.error
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    Modifier.padding(top = 12.dp).testTag(V4vTestTags.ERROR),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val red = MaterialTheme.colorScheme.error
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        shownError?.let { stringResource(it.messageRes()) } ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = red,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = { onAction(ValueForValueAction.Donate) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag(V4vTestTags.DONATE),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = orange,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = orange.copy(alpha = 0.6f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (state.loading) {
                    // Flutter: spinner and an EMPTY label while loading.
                    AppLoadingIndicator(
                        size = 28.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag(V4vTestTags.LOADING),
                    )
                } else {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(L10nR.string.v4vDonateButton),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, contentDescription = null, tint = orange, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${stringResource(L10nR.string.v4vRecipient)}: $V4V_LIGHTNING_ADDRESS",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = ext.textTertiary,
                )
            }
        }
    }

    state.noWalletInvoice?.let { invoice ->
        NoWalletSheet(
            invoice = invoice,
            onCopy = { onAction(ValueForValueAction.CopyInvoice) },
            onDismiss = { onAction(ValueForValueAction.DismissSheet) },
        )
    }
}

@Composable
private fun AmountField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val orange = MaterialTheme.colorScheme.primary
    val big = MaterialTheme.typography.displayMedium.copy(fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = big,
            placeholder = { Text("0", style = big, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.extendedColors.textTertiary) },
            suffix = {
                Text("Sats", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = orange)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = orange,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .testTag(V4vTestTags.AMOUNT)
                .semantics { contentDescription = label },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoWalletSheet(invoice: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .testTag(V4vTestTags.NO_WALLET_SHEET),
        ) {
            Text(
                stringResource(L10nR.string.v4vNoWalletTitle),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(L10nR.string.v4vNoWalletBody),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                invoice,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MonoStyle.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                // Animate the sheet out first (Flutter Navigator.pop), then copy + snackbar.
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onCopy() } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.v4vCopyInvoice))
            }
        }
    }
}

/** ARB message of a V4V failure (spec 11 §2.10). */
internal fun V4vErrorKind.messageRes(): Int = when (this) {
    V4vErrorKind.InvalidAmount -> L10nR.string.v4vErrInvalidAmount
    V4vErrorKind.BelowMin -> L10nR.string.v4vErrBelowMin
    V4vErrorKind.AboveMax -> L10nR.string.v4vErrAboveMax
    V4vErrorKind.Unreachable -> L10nR.string.v4vErrUnreachable
    V4vErrorKind.Generic -> L10nR.string.v4vErrGeneric
}

@Preview
@Composable
private fun ValueForValueScreenPreview() {
    EinundzwanzigTheme {
        ValueForValueScreen(
            state = ValueForValueUiState(amount = "2100", error = V4vErrorKind.BelowMin),
            onAction = {},
            onBack = {},
        )
    }
}
