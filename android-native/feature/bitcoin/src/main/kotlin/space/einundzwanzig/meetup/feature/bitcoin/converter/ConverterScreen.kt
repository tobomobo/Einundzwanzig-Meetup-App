package space.einundzwanzig.meetup.feature.bitcoin.converter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.mempool.converter.ConverterState
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyFormat
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import space.einundzwanzig.meetup.feature.bitcoin.R
import space.einundzwanzig.meetup.feature.bitcoin.ui.BitcoinLabels
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the converter. */
internal object ConverterTags {
    const val FIAT_FIELD = "converter_fiat"
    const val CRYPTO_FIELD = "converter_crypto"
    const val PREMIUM_FIELD = "converter_premium"
    const val PREMIUM_SIGN = "converter_premium_sign"
    const val SLIDER = "converter_slider"
    const val CURRENCY_CHIP = "converter_currency_chip"
    const val UNIT_CHIP = "converter_unit_chip"
    const val OFFLINE_BANNER = "converter_offline"
    const val PREMIUM_RESULT = "converter_premium_result"
    const val CURRENCY_SHEET = "converter_currency_sheet"
    fun currencyRow(code: String) = "converter_currency_$code"
}

/** Keeps only `[0-9.,]` (Flutter `FilteringTextInputFormatter.allow`, per character). */
internal fun filterAmount(text: String): String = text.filter { it in '0'..'9' || it == '.' || it == ',' }

/** Stateful converter entry (spec 13 §2.2). */
@Composable
fun ConverterRoute(viewModel: ConverterViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConverterScreen(state) { action ->
        if (action == ConverterAction.Back) navigator.back() else viewModel.onAction(action)
    }
}

/** Stateless converter screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConverterScreen(
    state: ConverterUiState,
    modifier: Modifier = Modifier,
    onAction: (ConverterAction) -> Unit,
) {
    val c = state.converter
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.convTitle),
                onBack = { onAction(ConverterAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { onAction(ConverterAction.Refresh) }, enabled = !c.loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(L10nR.string.convRefresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            AnimatedVisibility(c.error, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                OfflineBanner { onAction(ConverterAction.Refresh) }
            }
            AmountCard(
                label = stringResource(L10nR.string.convYouPay).uppercase(),
                field = rememberField(c.fiatText, state.fiatRevision),
                fieldTag = ConverterTags.FIAT_FIELD,
                onChange = { onAction(ConverterAction.FiatChanged(it)) },
            ) {
                CurrencyChip(c.currency) { sheetOpen = true }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                // Decorative only (no action), as in Flutter.
                Box(
                    Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, MaterialShapes.Cookie6Sided.toShape())
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialShapes.Cookie6Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            AmountCard(
                label = stringResource(if (c.cryptoIsBtc) L10nR.string.convUnitBtc else L10nR.string.convUnitSats).uppercase(),
                field = rememberField(c.cryptoText, state.cryptoRevision),
                fieldTag = ConverterTags.CRYPTO_FIELD,
                onChange = { onAction(ConverterAction.CryptoChanged(it)) },
            ) {
                UnitChip(c.cryptoIsBtc) { onAction(ConverterAction.SwapUnit) }
            }
            Spacer(Modifier.height(20.dp))
            PremiumCard(c, rememberField(c.premiumText, state.premiumRevision), onAction)
            Spacer(Modifier.height(20.dp))
            RateInfo(c)
        }
    }
    if (sheetOpen) {
        CurrencySheet(
            selected = c.currency,
            onDismiss = { sheetOpen = false },
            onPick = { code -> onAction(ConverterAction.PickCurrency(code)) },
        )
    }
}

/** Local field state, re-seeded (cursor at the end) when the ViewModel rewrote the text. */
@Composable
private fun rememberField(text: String, revision: Int): MutableState<TextFieldValue> =
    remember(revision) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }

@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .testTag(ConverterTags.OFFLINE_BANNER)
            .background(red.copy(alpha = 0.1f), shape)
            .border(0.5.dp, red.copy(alpha = 0.3f), shape)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(L10nR.string.convOffline), color = red, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) {
            Text(stringResource(L10nR.string.convRefresh), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AmountCard(
    label: String,
    field: MutableState<TextFieldValue>,
    fieldTag: String,
    onChange: (String) -> Unit,
    trailing: @Composable () -> Unit,
) {
    Tile(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column {
            Text(
                label,
                color = MaterialTheme.extendedColors.textTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmountField(field, label, fieldTag, onChange, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun AmountField(
    field: MutableState<TextFieldValue>,
    label: String,
    tag: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
    )
    BasicTextField(
        value = field.value,
        onValueChange = { v ->
            val filtered = filterAmount(v.text)
            val next = if (filtered == v.text) v else TextFieldValue(filtered, TextRange(filtered.length))
            val changed = next.text != field.value.text
            field.value = next
            if (changed) onChange(next.text)
        },
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
            .semantics { contentDescription = label },
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (field.value.text.isEmpty()) {
                    Text("0", style = style.copy(color = MaterialTheme.extendedColors.textTertiary))
                }
                inner()
            }
        },
    )
}

@Composable
private fun CurrencyChip(code: String, onClick: () -> Unit) {
    val label = stringResource(L10nR.string.convSelectCurrency)
    ChipButton(
        onClick = onClick,
        container = MaterialTheme.colorScheme.surfaceContainerLow,
        border = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        modifier = Modifier
            .testTag(ConverterTags.CURRENCY_CHIP)
            .semantics { contentDescription = "$code, $label" },
    ) {
        Text(code, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnitChip(isBtc: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val unit = if (isBtc) BitcoinLabels.BTC else BitcoinLabels.SATS
    val label = stringResource(R.string.native_bitcoin_toggle_unit)
    ChipButton(
        onClick = onClick,
        container = primary.copy(alpha = 0.12f),
        border = primary.copy(alpha = 0.4f),
        modifier = Modifier
            .testTag(ConverterTags.UNIT_CHIP)
            .semantics { contentDescription = "$unit, $label" },
    ) {
        Text(unit, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = primary)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp), tint = primary)
    }
}

@Composable
private fun ChipButton(
    onClick: () -> Unit,
    container: Color,
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Pressable(onClick = onClick, modifier = modifier) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .background(container, shape)
                    .border(0.5.dp, border, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumCard(c: ConverterState, field: MutableState<TextFieldValue>, onAction: (ConverterAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.extendedColors.textTertiary
    val premiumTitle = stringResource(L10nR.string.convPremiumTitle)
    Tile(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TrendingUp, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(L10nR.string.convPremiumTitle),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                // Compose has no signed number keyboard: keyboards honouring the decimal flag hide
                // the minus key, so a discount can also be typed via this sign toggle.
                IconButton(
                    onClick = { onAction(ConverterAction.TogglePremiumSign) },
                    modifier = Modifier.testTag(ConverterTags.PREMIUM_SIGN),
                ) {
                    Icon(
                        Icons.Rounded.Exposure,
                        contentDescription = stringResource(R.string.native_bitcoin_toggle_sign),
                        tint = primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                PremiumField(field, onAction)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(L10nR.string.convPremiumHint), color = tertiary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            val interaction = remember { MutableInteractionSource() }
            val dragged by interaction.collectIsDraggedAsState()
            val pressed by interaction.collectIsPressedAsState()
            // Value indicator while the thumb is held (Flutter `label`, unclamped value).
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(dragged || pressed, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        c.premiumLabel,
                        modifier = Modifier
                            .background(primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Slider(
                value = c.sliderValue.toFloat(),
                onValueChange = { onAction(ConverterAction.SliderChanged(it)) },
                valueRange = -ConverterState.PREMIUM_LIMIT.toFloat()..ConverterState.PREMIUM_LIMIT.toFloat(),
                steps = ConverterState.DIVISIONS - 1,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConverterTags.SLIDER)
                    .semantics {
                        contentDescription = premiumTitle
                        stateDescription = c.premiumLabel
                    },
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(BitcoinLabels.SLIDER_MIN, color = tertiary, fontSize = 10.sp)
                Text(BitcoinLabels.SLIDER_MID, color = tertiary, fontSize = 10.sp)
                Text(BitcoinLabels.SLIDER_MAX, color = tertiary, fontSize = 10.sp)
            }
            AnimatedVisibility(c.currentFiat > 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))
                    ResultRow(stringResource(L10nR.string.convPremiumResult), 13.sp, MaterialTheme.colorScheme.onSurfaceVariant) {
                        Text(
                            c.premiumResultText,
                            modifier = Modifier.testTag(ConverterTags.PREMIUM_RESULT),
                            color = primary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ResultRow(stringResource(L10nR.string.convPremiumSats), 13.sp, MaterialTheme.colorScheme.onSurfaceVariant) {
                        Text(c.premiumSatsText, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    ResultRow(stringResource(L10nR.string.convPremiumBase), 11.sp, tertiary) {
                        Text(c.premiumBaseText, color = tertiary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumField(field: MutableState<TextFieldValue>, onAction: (ConverterAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val title = stringResource(L10nR.string.convPremiumTitle)
    val shape = RoundedCornerShape(8.dp)
    val style = TextStyle(color = primary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    Box(
        Modifier
            .width(82.dp)
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = field.value,
            onValueChange = { v ->
                val changed = v.text != field.value.text
                field.value = v
                if (changed) onAction(ConverterAction.PremiumChanged(v.text))
            },
            modifier = Modifier
                .testTag(ConverterTags.PREMIUM_FIELD)
                .semantics { contentDescription = title },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (field.value.text.isEmpty()) Text("0,0", style = style.copy(color = primary.copy(alpha = 0.4f)))
                        inner()
                    }
                    Text("%", color = primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
        )
    }
}

@Composable
private fun ResultRow(label: String, labelSize: TextUnit, labelColor: Color, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = labelColor, fontSize = labelSize, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        value()
    }
}

@Composable
private fun RateInfo(c: ConverterState) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary = MaterialTheme.extendedColors.textTertiary
    when {
        c.loading -> Text(
            stringResource(L10nR.string.convLoading),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            textAlign = TextAlign.Center,
            color = secondary,
            fontSize = 13.sp,
        )
        c.btcPrice <= 0 -> Unit
        else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(L10nR.string.convRateInfo, BtcFormat.fmtInt(c.btcPrice.dartRound()), c.currency),
                color = secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            c.updatedAtMillis?.let {
                Text(stringResource(L10nR.string.convUpdated, BtcFormat.hhmm(it)), color = tertiary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(stringResource(L10nR.string.convSource), color = tertiary, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencySheet(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Spec 13 §2.2: the sheet is at most 80 % of the screen high.
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
    val pick: (String) -> Unit = { code ->
        onPick(code)
        // Animate the sheet away before removing it from the composition (expressive motion).
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag(ConverterTags.CURRENCY_SHEET),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text(
                stringResource(L10nR.string.convSelectCurrency),
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .semantics { heading() },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.selectableGroup()) {
                CurrencyFormat.supported.forEach { code ->
                    CurrencyRow(code, code == selected) { pick(code) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CurrencyRow(code: String, isSelected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    // Radio semantics (role RadioButton + selected) inside the sheet's selectableGroup; the
    // headline text stays the label. Pressable keeps the expressive press scale.
    Pressable(
        onClick = onClick,
        indication = ripple(),
        role = Role.RadioButton,
        modifier = Modifier
            .testTag(ConverterTags.currencyRow(code))
            .semantics(mergeDescendants = true) { selected = isSelected },
    ) {
        ListItem(
            headlineContent = {
                Text(
                    "$code · ${currencyNameRes(code)?.let { stringResource(it) } ?: code}",
                    color = if (isSelected) primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            },
            leadingContent = {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            if (isSelected) primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
                            if (isSelected) MaterialShapes.Cookie9Sided.toShape() else CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        CurrencyFormat.symbol(code),
                        color = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (CurrencyFormat.symbol(code).length > 1) 12.sp else 16.sp,
                    )
                }
            },
            trailingContent = if (isSelected) {
                { Icon(Icons.Rounded.Check, contentDescription = null, tint = primary) }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** `curEUR` … `curJPY` (spec 13 §2.4 `currencyName`); `null` for unknown codes (shown as the code). */
internal fun currencyNameRes(code: String): Int? = when (code) {
    "EUR" -> L10nR.string.curEUR
    "USD" -> L10nR.string.curUSD
    "CHF" -> L10nR.string.curCHF
    "GBP" -> L10nR.string.curGBP
    "CAD" -> L10nR.string.curCAD
    "AUD" -> L10nR.string.curAUD
    "JPY" -> L10nR.string.curJPY
    else -> null
}

@Preview(name = "Converter", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 1000)
@Composable
private fun ConverterPreview() {
    EinundzwanzigTheme {
        val base = ConverterState(currency = "EUR")
            .onPricesLoaded(mapOf("EUR" to 95_000.0, "USD" to 105_000.0), 1_760_000_000_000)
            .onFiatChanged("100")
            .onSlider(2.5f)
        ConverterScreen(ConverterUiState(base)) {}
    }
}

@Preview(name = "Converter offline", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 800)
@Composable
private fun ConverterOfflinePreview() {
    EinundzwanzigTheme {
        ConverterScreen(ConverterUiState(ConverterState(currency = "CHF").onPricesLoaded(emptyMap(), 0))) {}
    }
}
