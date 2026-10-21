package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.format.Formats
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle

/** Inconsolata text for npubs, hashes, block heights and amounts. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = MonoStyle.fontSize,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = MonoStyle.copy(fontSize = fontSize, fontWeight = fontWeight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A sats amount, grouped German style (`1.779 sats`), in Inconsolata. */
@Composable
fun SatsAmount(
    amount: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    fontSize: TextUnit = MonoStyle.fontSize,
) {
    MonoText(Formats.sats(amount), modifier, color, fontSize, FontWeight.Bold, maxLines = 1)
}

@Preview(name = "MonoText / SatsAmount", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun MonoTextPreview() {
    EinundzwanzigTheme {
        Column(Modifier.padding(16.dp)) {
            MonoText("npub1dzu6ceug…7k4mjqyz", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SatsAmount(1_779, fontSize = 20.sp)
            MonoText(Formats.blockHeight(875_000), color = MaterialTheme.colorScheme.primary, fontSize = 26.sp)
        }
    }
}
