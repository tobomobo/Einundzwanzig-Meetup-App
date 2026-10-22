package space.einundzwanzig.meetup.feature.bitcoin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Labels the Flutter screens hard-code in every language (spec 13 §14 item 14). They are not
 * translated on purpose, so they live here instead of in the string resources.
 */
internal object BitcoinLabels {
    const val TITLE = "Bitcoin"
    const val BLOCK = "BLOCK"
    const val FEES = "FEES  L·M·H"
    const val MOSCOW = "MOSCOW"
    const val SUPPLY = "SUPPLY"
    const val HASHRATE = "HASHRATE"
    const val DIFFICULTY = "DIFFICULTY"
    const val LIGHTNING = "LIGHTNING"
    const val CAPACITY = "KAPAZITÄT"
    const val NODES = "NODES"
    const val CHANNELS = "CHANNELS"
    const val SATS = "sats"
    const val BTC = "BTC"
    const val CLEARNET_SUBTITLE = "mempool.space"
    const val CUSTOM_URL_HINT = "https://mempool.mein-node.local"
    const val SLIDER_MIN = "−20%"
    const val SLIDER_MID = "0"
    const val SLIDER_MAX = "+20%"

    /** `"EUR/BTC"` price row label. */
    fun priceRow(currency: String): String = "$currency/BTC"
}

/**
 * Flutter `_hint` card of the settings screen (spec 13 §2.3): accent-tinted background
 * (α 0.09), accent border (α 0.3), radius 10, icon 17 dp, 12 sp secondary text.
 */
@Composable
internal fun HintCard(
    text: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.09f), shape)
            .border(0.7.dp, accent.copy(alpha = 0.3f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
        )
    }
}
