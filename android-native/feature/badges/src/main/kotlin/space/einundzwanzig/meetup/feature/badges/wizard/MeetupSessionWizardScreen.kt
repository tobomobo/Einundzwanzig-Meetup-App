package space.einundzwanzig.meetup.feature.badges.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NfcTagWriter
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.badges.common.Gap
import space.einundzwanzig.meetup.feature.badges.common.PrimaryActionButton
import space.einundzwanzig.meetup.feature.badges.common.PulsingCircle
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the wizard. */
object MeetupSessionWizardTags {
    const val WRITE = "wizard.write"
    const val SKIP = "wizard.skip"
}

/**
 * Stateful entry of `MeetupSessionWizard` (spec 02 §2.5, NFC flag): navigation only, so no
 * ViewModel. "NFC TAG BESCHREIBEN" pushes the writer; "ÜBERSPRINGEN" opens the rolling QR
 * from the root (`pushAndRemoveUntil(isFirst)`).
 */
@Composable
fun MeetupSessionWizardRoute() {
    val navigator = LocalNavigator.current
    MeetupSessionWizardScreen(
        onBack = navigator::back,
        onWriteTag = { navigator.navigate(NfcTagWriter) },
        onSkip = { navigator.navigateFromRoot(RollingQr) },
    )
}

/** Stateless wizard: step indicator (1 NFC active, 2 QR), intro and the two actions. */
@Composable
fun MeetupSessionWizardScreen(
    onBack: () -> Unit,
    onWriteTag: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar(title = stringResource(L10nR.string.mwStartMeetup), onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepIndicator()
            Gap(40.dp)
            PulsingCircle(Icons.Rounded.Nfc, size = 140.dp, minScale = 1f, maxScale = 1.08f)
            Gap(32.dp)
            Text(
                stringResource(L10nR.string.mwStep1Nfc),
                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.5.sp),
                modifier = Modifier.semantics { heading() },
            )
            Gap(12.dp)
            Text(
                stringResource(L10nR.string.mwNfcIntro1) + stringResource(L10nR.string.mwNfcIntro2),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            PrimaryActionButton(
                text = stringResource(L10nR.string.mwWriteNfcTag),
                icon = Icons.Rounded.Nfc,
                onClick = onWriteTag,
                modifier = Modifier.testTag(MeetupSessionWizardTags.WRITE),
            )
            Gap(12.dp)
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(MeetupSessionWizardTags.SKIP),
            ) {
                Text(stringResource(L10nR.string.mwSkipQrOnly), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepIndicator() {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        StepDot(number = 1, label = "NFC", active = true, accent = primary)
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .width(64.dp)
                .height(2.dp)
                .background(inactive),
        )
        StepDot(number = 2, label = "QR", active = false, accent = inactive)
    }
}

@Composable
private fun StepDot(number: Int, label: String, active: Boolean, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(36.dp)
                .background(if (active) accent else Color.Transparent, CircleShape)
                .border(2.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(4.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Session wizard", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 760)
@Composable
private fun MeetupSessionWizardPreview() {
    EinundzwanzigTheme { MeetupSessionWizardScreen(onBack = {}, onWriteTag = {}, onSkip = {}) }
}
