package space.einundzwanzig.meetup.core.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Dimension
import coil3.size.Size as CoilSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.ui.R
import space.einundzwanzig.meetup.core.ui.component.LoadingPlaceholder
import space.einundzwanzig.meetup.core.ui.share.LocalAllowHardwareBitmaps
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.Inconsolata
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.util.SafeUrl
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher

/** Limits and rules for Markdown images (spec 11 §8.6, DoS guards §9.10). */
object MarkdownImages {
    /** Data-URI payloads longer than this (base64 characters) are not decoded. */
    const val MAX_DATA_URI_BASE64 = 3 * 1024 * 1024

    /** Network images are decoded at most this wide (Flutter `cacheWidth`). */
    const val MAX_DECODE_WIDTH_PX = 1080

    /** Classification of an image target. */
    enum class Kind { DataUri, Network, Placeholder, None }

    /**
     * What to render for [url]: `data:image/` URIs (placeholder if oversized or without a
     * comma → none), `http(s)` URLs (HEIC/HEIF → placeholder), everything else nothing.
     */
    fun classify(url: String): Kind {
        val u = url.dartTrim()
        if (u.startsWith("data:image/")) {
            val comma = u.indexOf(',')
            if (comma == -1) return Kind.None
            return if (u.length - comma - 1 > MAX_DATA_URI_BASE64) Kind.Placeholder else Kind.DataUri
        }
        val colon = u.indexOf(':')
        if (u.isEmpty() || colon <= 0) return Kind.None
        val scheme = u.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return Kind.None
        val lower = u.lowercase()
        if (lower.contains(".heic") || lower.contains(".heif")) return Kind.Placeholder
        return Kind.Network
    }

    /**
     * Decodes the payload of a data URI (text after the first comma). Accepts the standard and
     * the URL-safe alphabet and missing padding; returns null for broken base64.
     */
    fun decodeDataUri(url: String): ByteArray? {
        val u = url.dartTrim()
        val comma = u.indexOf(',')
        if (comma == -1) return null
        var b64 = u.substring(comma + 1).replace('-', '+').replace('_', '/')
        if (b64.length % 4 != 0) b64 += "=".repeat(4 - b64.length % 4)
        return try {
            java.util.Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Safe Markdown renderer for article bodies (port of `lib/widgets/markdown_view.dart`;
 * parser rules in [MarkdownParser]).
 *
 * Links are only followed for `http(s)` URLs with a host; anything else (`javascript:`,
 * `intent:`, …) is silently ignored before [onLinkClick] is called. By default links open
 * externally through [SafeUrlLauncher]. A parse failure falls back to selectable plain text.
 */
@Composable
fun MarkdownView(
    data: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
) {
    if (data.dartTrim().isEmpty()) return
    val context = LocalContext.current
    val currentOnLink by rememberUpdatedState(onLinkClick)
    val openLink: (String) -> Unit = remember(context) {
        { url ->
            if (SafeUrl.isHttpUrl(url)) {
                val handler = currentOnLink
                if (handler != null) handler(url) else SafeUrlLauncher.open(context, url)
            }
        }
    }
    val parsed = remember(data) { runCatching { MarkdownParser.parse(data) } }
    val blocks = parsed.getOrNull()
    if (blocks == null) {
        SelectionContainer(modifier) {
            Text(
                text = remember(data) { MarkdownParser.plainFallback(data) },
                style = MarkdownStyles.paragraph(),
            )
        }
        return
    }
    Column(modifier) {
        blocks.forEach { block -> MarkdownBlock(block, openLink) }
    }
}

/** Text styles of the renderer (sizes from the Flutter widget). */
internal object MarkdownStyles {
    @Composable
    fun paragraph() = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )

    @Composable
    fun heading(level: Int) = MaterialTheme.typography.titleLarge.copy(
        fontSize = when (level) {
            1 -> 24.sp
            2 -> 20.sp
            else -> 17.sp
        },
        lineHeight = when (level) {
            1 -> 31.sp
            2 -> 26.sp
            else -> 22.sp
        },
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MarkdownBlock(block: MdBlock, openLink: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    when (block) {
        is MdBlock.Heading -> InlineText(
            block.text,
            MarkdownStyles.heading(block.level),
            openLink,
            Modifier.padding(top = if (block.level == 1) 8.dp else 16.dp, bottom = 8.dp),
        )
        is MdBlock.Paragraph -> InlineText(block.text, MarkdownStyles.paragraph(), openLink, Modifier.padding(bottom = 12.dp))
        is MdBlock.Quote -> InlineText(
            block.text,
            MarkdownStyles.paragraph().copy(
                lineHeight = 23.25.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            openLink,
            Modifier
                .padding(bottom = 12.dp)
                .drawBehind { drawRect(primary, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 17.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
        )
        is MdBlock.ListBlock -> Column(Modifier.padding(bottom = 12.dp)) {
            block.items.forEachIndexed { n, item ->
                Row(Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = if (block.ordered) "${n + 1}." else "•",
                        color = primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 1.dp, end = 8.dp),
                    )
                    InlineText(
                        item,
                        MarkdownStyles.paragraph().copy(lineHeight = 22.5.sp),
                        openLink,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        is MdBlock.Code -> {
            val shape = RoundedCornerShape(Dimens.TileRadius)
            Box(
                Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                    .border(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant, shape)
                    .padding(12.dp),
            ) {
                Text(
                    block.code,
                    fontFamily = Inconsolata,
                    fontSize = 13.sp,
                    lineHeight = 19.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is MdBlock.Image -> MarkdownImage(block.url)
        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            thickness = Dimens.Hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, openLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, style, primary) {
        // Problematic content: plain text instead of a crash.
        runCatching { buildInline(text, style, primary, openLink) }.getOrElse { AnnotatedString(text) }
    }
    Text(annotated, modifier = modifier, style = style)
}

/** Builds the inline [AnnotatedString]; links carry a clickable annotation calling [openLink]. */
internal fun buildInline(text: String, base: TextStyle, accent: Color, openLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        val codeSize = if (base.fontSize.value.isNaN()) 14.sp else (base.fontSize.value - 1).sp
        MarkdownParser.inline(text).forEach { span ->
            when (span) {
                is MdSpan.Plain -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.Code -> withStyle(SpanStyle(fontFamily = Inconsolata, color = accent, fontSize = codeSize)) {
                    append(span.text)
                }
                is MdSpan.Link -> withLink(
                    LinkAnnotation.Clickable(
                        tag = span.url,
                        styles = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { openLink(span.url) },
                    ),
                ) { append(span.label) }
            }
        }
    }

@Composable
private fun MarkdownImage(url: String) {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    val allowHardware = LocalAllowHardwareBitmaps.current
    val context = LocalContext.current
    when (MarkdownImages.classify(url)) {
        MarkdownImages.Kind.None -> Unit
        MarkdownImages.Kind.Placeholder -> ImagePlaceholder()
        MarkdownImages.Kind.DataUri -> {
            val bytes by produceState<ByteArray?>(null, url) {
                value = withContext(Dispatchers.Default) { MarkdownImages.decodeDataUri(url) }
            }
            val data = bytes ?: return // still decoding, or broken base64 → nothing
            val request = remember(data, allowHardware) {
                ImageRequest.Builder(context).data(data).allowHardware(allowHardware).build()
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(shape),
                error = {},
            )
        }
        MarkdownImages.Kind.Network -> {
            val target = url.dartTrim()
            val request = remember(target, allowHardware) {
                ImageRequest.Builder(context)
                    .data(target)
                    .size(CoilSize(Dimension(MarkdownImages.MAX_DECODE_WIDTH_PX), Dimension.Undefined))
                    .allowHardware(allowHardware)
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(shape),
                loading = { LoadingPlaceholder(height = 160.dp) },
                error = {},
            )
        }
    }
}

@Composable
private fun ImagePlaceholder() {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    Box(
        Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .height(120.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.native_markdown_image_placeholder),
            fontSize = 13.sp,
            color = MaterialTheme.extendedColors.textTertiary,
        )
    }
}

@Preview(name = "MarkdownView", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 720)
@Composable
private fun MarkdownViewPreview() {
    EinundzwanzigTheme {
        Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownView(
                """
                # Die Ausnahme
                Ein Zoll, der sich **selbst** ausnimmt, *kursiv* und `code`.
                Mehr auf [der Webseite](https://einundzwanzig.space).
                #bitcoin

                ## Liste
                - erstens
                - zweitens
                5. a
                7. b

                > Zitat über
                > zwei Zeilen

                ```kotlin
                val sats = 21_000_000
                ```
                ---
                ![](https://example.com/photo.HEIC)
                """.trimIndent(),
            )
        }
    }
}
