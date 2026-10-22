package space.einundzwanzig.meetup.feature.settings.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyFormat
import space.einundzwanzig.meetup.data.settings.locale.AppLocales
import space.einundzwanzig.meetup.feature.settings.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Localized currency name (`curEUR` …), the code itself for unknown codes (spec 12 §2.6). */
@Composable
internal fun currencyName(code: String): String = when (code) {
    "EUR" -> stringResource(L10nR.string.curEUR)
    "USD" -> stringResource(L10nR.string.curUSD)
    "CHF" -> stringResource(L10nR.string.curCHF)
    "GBP" -> stringResource(L10nR.string.curGBP)
    "CAD" -> stringResource(L10nR.string.curCAD)
    "AUD" -> stringResource(L10nR.string.curAUD)
    "JPY" -> stringResource(L10nR.string.curJPY)
    else -> code
}

/** Language options in Dart order: System, Deutsch, English, Español. */
internal val LanguageOptions: List<String?> = listOf<String?>(null) + AppLocales.SUPPORTED

/** Language dialog (`_showLanguagePopup`): selecting applies immediately and closes the dialog. */
@Composable
internal fun LanguageDialog(current: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(Modifier.testTag(SettingsSheetTags.LANGUAGE_DIALOG)) {
            DialogHeader(Icons.Rounded.Language, stringResource(L10nR.string.settingsLanguageChoose))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.selectableGroup()) {
                LanguageOptions.forEach { code ->
                    val selected = code == current
                    OptionRow(
                        selected = selected,
                        modifier = Modifier.testTag(SettingsSheetTags.language(code)),
                        onClick = { onSelect(code) },
                        leading = { Text(AppLocales.flagFor(code), fontSize = 22.sp) },
                        label = AppLocales.displayName(code),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Currency dialog (`_showCurrencyPopup`): seven currencies with hint; scrolls on small screens. */
@Composable
internal fun CurrencyDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(Modifier.testTag(SettingsSheetTags.CURRENCY_DIALOG)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogHeader(Icons.Rounded.CurrencyExchange, stringResource(L10nR.string.convSelectCurrency), bottom = 4.dp)
                Text(
                    stringResource(L10nR.string.settingsCurrencyHint),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                    fontSize = 12.sp,
                    lineHeight = 16.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.selectableGroup()) {
                    CurrencyFormat.supported.forEach { code ->
                        val selected = code == current
                        OptionRow(
                            selected = selected,
                            modifier = Modifier.testTag(SettingsSheetTags.currency(code)),
                            onClick = { onSelect(code) },
                            leading = {
                                Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        CurrencyFormat.symbol(code),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingGap = 8.dp,
                            label = "$code · ${currencyName(code)}",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DialogCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column { content() }
    }
}

@Composable
private fun DialogHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, bottom: androidx.compose.ui.unit.Dp = 8.dp) {
    Row(
        Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = bottom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.semantics { heading() }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingGap: androidx.compose.ui.unit.Dp = 16.dp,
    leading: @Composable () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(leadingGap))
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.native_settings_selected),
                tint = primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Preview(name = "Language dialog", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun LanguageDialogPreview() {
    EinundzwanzigTheme { LanguageDialog(current = "de", onSelect = {}, onDismiss = {}) }
}

@Preview(name = "Currency dialog", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun CurrencyDialogPreview() {
    EinundzwanzigTheme { CurrencyDialog(current = "CHF", onSelect = {}, onDismiss = {}) }
}
