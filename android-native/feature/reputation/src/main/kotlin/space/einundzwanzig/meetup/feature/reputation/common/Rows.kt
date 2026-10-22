package space.einundzwanzig.meetup.feature.reputation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Card container of the result sections (cCard, radius 14, accent border). */
@Composable
internal fun SectionCard(
    borderColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, borderColor, shape)
            .padding(14.dp),
        content = content,
    )
}

/** Section header row: icon + uppercase title in [color]. */
@Composable
internal fun SectionHeader(icon: ImageVector, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { heading() }) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
}

/** One evidence row: tinted icon, title and an optional subtitle. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EvidenceRow(icon: ImageVector, title: String, subtitle: String, color: Color, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .background(color.copy(alpha = 0.12f), MaterialShapes.Cookie6Sided.toShape()),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, color = color, fontSize = 11.sp)
            }
        }
    }
}
