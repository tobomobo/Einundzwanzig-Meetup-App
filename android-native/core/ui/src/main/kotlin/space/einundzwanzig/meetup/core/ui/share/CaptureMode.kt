package space.einundzwanzig.meetup.core.ui.share

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * False while a composable is rendered offscreen by [ComposableCapture]: software canvases
 * cannot draw hardware bitmaps, so image composables must request software bitmaps
 * (`ImageRequest.Builder.allowHardware(LocalAllowHardwareBitmaps.current)`). All image
 * components of :core:ui honour it; feature code using `AsyncImage` inside a capture must too.
 */
val LocalAllowHardwareBitmaps = staticCompositionLocalOf { true }
