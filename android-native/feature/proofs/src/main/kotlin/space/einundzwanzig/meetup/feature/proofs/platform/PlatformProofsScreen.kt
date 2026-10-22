package space.einundzwanzig.meetup.feature.proofs.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.GlassCard
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.SectionHeader
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.component.showLongDismissable
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.proofs.platform.PlatformCatalog
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.feature.proofs.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the platform proof screen. */
internal object PlatformProofsTags {
    const val PRIMARY_BUTTON = "pp_primary_button"
    const val CREATE_BUTTON = "pp_create_button"
    const val USERNAME_FIELD = "pp_username_field"
    const val CUSTOM_NAME_FIELD = "pp_custom_name_field"
    const val CREATING = "pp_creating"
}

/** Stateful entry of [PlatformProofs][space.einundzwanzig.meetup.core.navigation.PlatformProofs] (spec 06 §2.1). */
@Composable
internal fun PlatformProofsRoute(viewModel: PlatformProofsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val resources = LocalContext.current.resources

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PlatformProofsEvent.LinkSaved -> {
                    // Flutter shows the snackbar on the root messenger and then pops the screen.
                    // Only the app host outlives this screen; a local fallback host would leave
                    // composition with it, so then the screen just closes.
                    if (appHost != null) {
                        viewModel.showLinkSavedOnAppHost(appHost, resources.getString(L10nR.string.ppLinkSaved))
                    }
                    navigator.back()
                }
                is PlatformProofsEvent.CreateFailed -> launch {
                    host.showLongDismissable(createErrorMessage(resources, event.error, event.detail))
                }
                PlatformProofsEvent.RevokeFailed -> launch {
                    host.showAppSnackbar(resources.getString(R.string.native_ppRevokeFailed), Tone.Error)
                }
            }
        }
    }

    PlatformProofsScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = navigator::back,
        snackbarHostState = if (appHost == null) localHost else null,
    )
}

/** Stateless platform proof screen (spec 06 §2.1). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlatformProofsScreen(
    state: PlatformProofsUiState,
    onAction: (PlatformProofsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                AppLargeTopBar(
                    title = stringResource(L10nR.string.ppTitle),
                    onBack = onBack,
                    scrollBehavior = scrollBehavior,
                )
                AnimatedVisibility(state.isCreating) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                }
            }
        },
        snackbarHost = { snackbarHostState?.let { AppSnackbarHost(it) } },
    ) { padding ->
        if (state.isLoading) {
            FullScreenLoading(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "info") { HowItWorksCard() }
                if (state.proofs.isNotEmpty()) {
                    item(key = "label") {
                        SectionHeader(
                            title = stringResource(L10nR.string.ppActiveLinks),
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .animateItem(),
                        )
                    }
                    items(state.proofs, key = { "proof_${it.platform}" }) { proof ->
                        ProofCard(
                            proof = proof,
                            onRevoke = { onAction(PlatformProofsAction.RequestRevoke(proof)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                item(key = "primary") {
                    PrimaryLinkButton(
                        hasProofs = state.proofs.isNotEmpty(),
                        isCreating = state.isCreating,
                        onClick = { onAction(PlatformProofsAction.OpenSheet) },
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .animateItem(),
                    )
                }
            }
        }
    }

    state.sheet?.let { sheet ->
        CreateProofSheet(
            sheet = sheet,
            linkedPlatforms = state.linkedPlatforms,
            onAction = onAction,
        )
    }

    state.pendingRevoke?.let { proof ->
        RevokeProofDialog(
            proof = proof,
            onConfirm = { onAction(PlatformProofsAction.ConfirmRevoke) },
            onDismiss = { onAction(PlatformProofsAction.DismissRevoke) },
        )
    }
}

/**
 * Revoke confirmation (spec 06 §2.1). Local instead of core:ui ConfirmDialog because Flutter
 * (platform_proof_screen.dart) gives ppRevoke a filled red button, not a text button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RevokeProofDialog(
    proof: PlatformProof,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = error) },
        title = { Text(stringResource(L10nR.string.ppUnlinkQ), color = error) },
        // Raw platform id, not the display name (Flutter).
        text = {
            Text(
                stringResource(L10nR.string.ppUnlinkBody, proof.username, proof.platform),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(L10nR.string.ppRevoke), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(L10nR.string.ppCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun HowItWorksCard() {
    val primary = MaterialTheme.colorScheme.primary
    val steps = listOf(
        stringResource(L10nR.string.ppHowStep1),
        stringResource(L10nR.string.ppHowStep2),
        stringResource(L10nR.string.ppHowStep3),
        stringResource(L10nR.string.ppHowStep4),
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = primary.copy(alpha = 0.2f),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(L10nR.string.apHowItWorks),
                    color = primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                // The pieces carry their own line breaks; join robustly in case resources trim them.
                text = steps.joinToString("\n") { it.trim() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 19.sp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProofCard(proof: PlatformProof, onRevoke: () -> Unit, modifier: Modifier = Modifier) {
    val success = MaterialTheme.extendedColors.success
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, success.copy(alpha = 0.2f), shape)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(success.copy(alpha = 0.12f), MaterialShapes.Cookie4Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(platformIcon(proof.platform), contentDescription = null, tint = success, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        val displayName = platformDisplayName(proof.platform)
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = proof.username,
                style = MonoStyle.copy(fontSize = 13.sp),
                color = MaterialTheme.extendedColors.onSuccessContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(L10nR.string.ppCreated, proofDate(proof.createdAt)),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val embedded = stringResource(R.string.native_ppEmbeddedInQr)
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(success.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .semantics { contentDescription = embedded },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = success, modifier = Modifier.size(18.dp))
        }
        val tooltip = stringResource(L10nR.string.ppRevokeTooltip)
        val revokeDescription = stringResource(R.string.native_ppRevokeFor, displayName, proof.username)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = onRevoke,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = revokeDescription,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryLinkButton(
    hasProofs: Boolean,
    isCreating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !isCreating,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(PlatformProofsTags.PRIMARY_BUTTON),
    ) {
        if (isCreating) {
            LoadingIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .testTag(PlatformProofsTags.CREATING),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.native_ppCreating), fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(56.dp)))
            Text(
                text = stringResource(if (hasProofs) L10nR.string.ppAnotherPlatform else L10nR.string.ppLinkPlatform),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateProofSheet(
    sheet: CreateSheetState,
    linkedPlatforms: Set<String>,
    onAction: (PlatformProofsAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val success = MaterialTheme.extendedColors.success
    // One submit path for the button and the IME Done action: animate the sheet away first.
    val submit: () -> Unit = {
        if (sheet.canSubmit) {
            scope.launch {
                // Submit even if the hide animation is cancelled (e.g. sheet leaves composition):
                // the VM still holds the sheet and re-checks canSubmit, so this is safe.
                try {
                    sheetState.hide()
                } finally {
                    onAction(PlatformProofsAction.Create)
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { onAction(PlatformProofsAction.DismissSheet) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(L10nR.string.ppShareOnPlatform),
                color = primary,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(L10nR.string.ppIntro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            FieldLabel(stringResource(L10nR.string.ppPlatform))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val linkedLabel = stringResource(R.string.native_ppAlreadyLinked)
                PlatformCatalog.platforms.forEach { info ->
                    val selected = sheet.selectedPlatform == info.id
                    val linked = info.id in linkedPlatforms
                    FilterChip(
                        selected = selected,
                        onClick = { onAction(PlatformProofsAction.SelectPlatform(info.id)) },
                        label = {
                            Text(
                                platformDisplayName(info.id),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        leadingIcon = {
                            Icon(info.icon.imageVector(), contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = if (linked) {
                            {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = linkedLabel,
                                    tint = success,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primary.copy(alpha = 0.2f),
                            selectedLabelColor = primary,
                            selectedLeadingIconColor = primary,
                            selectedTrailingIconColor = success,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = if (linked) success.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = primary,
                            selectedBorderWidth = 2.dp,
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { if (linked) stateDescription = linkedLabel },
                    )
                }
            }

            AnimatedVisibility(
                visible = sheet.selectedPlatform == PlatformCatalog.OTHER,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                OutlinedTextField(
                    state = sheet.customPlatformName,
                    placeholder = { Text(stringResource(L10nR.string.ppPlatformName)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag(PlatformProofsTags.CUSTOM_NAME_FIELD),
                )
            }

            AnimatedVisibility(
                visible = sheet.selectedPlatform != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(top = 20.dp)) {
                    FieldLabel(stringResource(L10nR.string.ppUsername))
                    OutlinedTextField(
                        state = sheet.username,
                        placeholder = { Text(platformHint(sheet.selectedPlatform.orEmpty())) },
                        leadingIcon = { Icon(Icons.Rounded.AlternateEmail, contentDescription = null) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        onKeyboardAction = KeyboardActionHandler { submit() },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PlatformProofsTags.USERNAME_FIELD),
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = submit,
                        enabled = sheet.canSubmit,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag(PlatformProofsTags.CREATE_BUTTON),
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(L10nR.string.ppCreateLink),
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Preview(name = "Platform proofs")
@Composable
private fun PlatformProofsScreenPreview() {
    EinundzwanzigTheme {
        PlatformProofsScreen(
            state = PlatformProofsUiState(
                isLoading = false,
                proofs = listOf(
                    PlatformProof("telegram", "satoshi_fan", "a".repeat(128), 1_746_000_000),
                    PlatformProof("bisq", "bisq_trader", "b".repeat(128), 1_748_000_000),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(name = "Platform proofs, empty")
@Composable
private fun PlatformProofsEmptyPreview() {
    EinundzwanzigTheme {
        PlatformProofsScreen(state = PlatformProofsUiState(isLoading = false), onAction = {}, onBack = {})
    }
}

private val previewProofs = listOf(
    PlatformProof("telegram", "satoshi_fan", "a".repeat(128), 1_746_000_000),
    PlatformProof("bisq", "bisq_trader", "b".repeat(128), 1_748_000_000),
)

@Preview(name = "Platform proofs, create sheet")
@Composable
private fun PlatformProofsCreateSheetPreview() {
    EinundzwanzigTheme {
        PlatformProofsScreen(
            state = PlatformProofsUiState(
                isLoading = false,
                proofs = previewProofs,
                sheet = CreateSheetState(selectedPlatform = PlatformCatalog.OTHER),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(name = "Platform proofs, revoke dialog")
@Composable
private fun PlatformProofsRevokePreview() {
    EinundzwanzigTheme {
        PlatformProofsScreen(
            state = PlatformProofsUiState(
                isLoading = false,
                proofs = previewProofs,
                pendingRevoke = previewProofs.first(),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
