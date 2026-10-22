package space.einundzwanzig.meetup.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Layout metrics carried over from the Flutter app (`lib/theme.dart`). */
object Dimens {
    /** Gap between dashboard tiles (`kTileGap`). */
    val TileGap = 10.dp

    /** Corner radius of tiles, cards, inputs and buttons (`kTileRadius`). */
    val TileRadius = 10.dp

    /** Hairline border width used on tiles and glass cards. */
    val Hairline = 0.5.dp

    /** Default corner radius of [space.einundzwanzig.meetup.core.ui.component.GlassCard]. */
    val GlassRadius = 20.dp

    /** Default screen content padding. */
    val ScreenPadding = 20.dp

    /** Shape of tiles and cards (`kTileRadius`). */
    val TileShape = RoundedCornerShape(TileRadius)
}
