package space.einundzwanzig.meetup.core.ui.nostr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.ui.R
import space.einundzwanzig.meetup.core.ui.share.LocalAllowHardwareBitmaps
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.util.UiLog

/**
 * Semantics key exposing the picture currently shown by an avatar (absent = letter fallback).
 * Used by UI tests; mirrors `CircleAvatar.backgroundImage.url` in the Flutter tests.
 */
val AvatarPictureKey = SemanticsPropertyKey<String>("AvatarPicture")

private var SemanticsPropertyReceiver.avatarPicture by AvatarPictureKey

/**
 * Profile picture of a Nostr identity with a letter fallback (port of
 * `lib/widgets/nostr_avatar.dart`, spec 07 §2.3).
 *
 * Behaviour:
 * - whenever [pubkeyHex], [fallbackText] or [pictureLoader] changes, the old picture is
 *   cleared **immediately** and a new lookup starts; a late result of a previous identity is
 *   discarded (the previous lookup is cancelled);
 * - [pubkeyHex] null/empty means "the active identity" ([NostrProfileSource.activePubkeyHex]);
 * - no URL, a failed lookup or a failed image download show the coloured circle with the
 *   first letter of [fallbackText] (or `?`).
 *
 * @param pictureLoader overrides [NostrProfileSource.pictureUrl] (test seam, embedding).
 */
@Composable
fun NostrAvatar(
    fallbackText: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.primary,
    radius: Dp = 20.dp,
    pubkeyHex: String? = null,
    pictureLoader: (suspend (String) -> String?)? = null,
    shape: Shape = CircleShape,
) {
    val source = LocalNostrProfileSource.current
    // A new state object per identity: the old picture disappears in the same frame.
    var pictureUrl by remember(pubkeyHex, fallbackText, pictureLoader, source) { mutableStateOf<String?>(null) }
    LaunchedEffect(pubkeyHex, fallbackText, pictureLoader, source) {
        val hex = pubkeyHex?.takeIf { it.isNotEmpty() }
            ?: loadOrNull { source.activePubkeyHex() }?.takeIf { it.isNotEmpty() }
            ?: return@LaunchedEffect
        pictureUrl = loadOrNull { (pictureLoader ?: source::pictureUrl).invoke(hex) }
    }
    AvatarImage(
        model = pictureUrl,
        fallbackText = fallbackText,
        modifier = modifier,
        backgroundColor = backgroundColor,
        size = radius * 2,
        shape = shape,
    )
}

private suspend fun <T> loadOrNull(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // No picture reachable — the letter stays.
    UiLog.warn("NostrAvatar", "Profilbild nicht ladbar", e)
    null
}

/**
 * Stateless avatar: shows [model] (URL, `File`, `Uri`, anything Coil loads) in a [shape] filled
 * with [backgroundColor], or the letter fallback when [model] is null/empty or fails to load.
 * While loading only the coloured shape is visible (Flutter `CircleAvatar` behaviour).
 */
@Composable
fun AvatarImage(
    model: Any?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    contentDescription: String? = stringResource(R.string.native_avatar_description),
) {
    val usable = model?.takeUnless { it is String && it.isEmpty() }
    var failedModel by remember { mutableStateOf<Any?>(null) }
    val showImage = usable != null && usable != failedModel
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(backgroundColor)
            .semantics { if (showImage) avatarPicture = usable.toString() },
        contentAlignment = Alignment.Center,
    ) {
        if (showImage) {
            val context = LocalContext.current
            val allowHardware = LocalAllowHardwareBitmaps.current
            val request = remember(usable, allowHardware) {
                ImageRequest.Builder(context)
                    .data(usable)
                    .crossfade(true)
                    .allowHardware(allowHardware)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                onError = { failedModel = usable },
            )
        } else {
            LetterFallback(fallbackText, backgroundColor, size)
        }
    }
}

@Composable
private fun LetterFallback(fallbackText: String, backgroundColor: Color, size: Dp) {
    val letter = remember(fallbackText) {
        if (fallbackText.isEmpty()) "?" else String(Character.toChars(fallbackText.codePointAt(0))).uppercase()
    }
    val fontSize = with(LocalDensity.current) { (size / 2 * 0.8f).toSp() }
    Text(
        text = letter,
        color = if (backgroundColor.luminance() > 0.18f) Color.Black else Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        maxLines = 1,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "NostrAvatar", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun NostrAvatarPreview() {
    EinundzwanzigTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NostrAvatar(fallbackText = "Alice", pubkeyHex = "abc")
            NostrAvatar(fallbackText = "satoshi", radius = 28.dp, backgroundColor = MaterialTheme.extendedColors.success)
            NostrAvatar(fallbackText = "", radius = 16.dp)
            AvatarImage(
                model = null,
                fallbackText = "Expressive",
                size = 56.dp,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                backgroundColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
