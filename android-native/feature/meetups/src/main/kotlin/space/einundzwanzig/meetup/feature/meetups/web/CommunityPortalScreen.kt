package space.einundzwanzig.meetup.feature.meetups.web

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBarDefaults
import space.einundzwanzig.meetup.core.ui.component.BackNavigationButton
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.IconTile
import space.einundzwanzig.meetup.feature.meetups.common.LinkFailure
import space.einundzwanzig.meetup.feature.meetups.common.SectionLabel
import space.einundzwanzig.meetup.feature.meetups.common.rememberLinkOpener
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Every URL of the "Portal-Webseite" hub (spec 09 §2.7), hard-coded as in Flutter. */
internal object PortalWebUrls {
    const val PORTAL = "https://portal.einundzwanzig.space"
    const val PORTAL_HOME = "https://portal.einundzwanzig.space/"
    const val MEETUPS = "https://einundzwanzig.space/meetups/"
    const val PODCAST = "https://einundzwanzig.space/podcast/"
    const val SHOUTOUT = "https://shoutout.einundzwanzig.space"
    const val DONATE = "https://einundzwanzig.space/spenden/"
    const val SHOPS = "https://einundzwanzig.space/shops/"
    const val SOUNDBOARD = "https://einundzwanzig.space/soundboard/"
    const val ASSOCIATION = "https://einundzwanzig.space/verein/"
    const val MEMBERSHIP = "https://verein.einundzwanzig.space/"
    const val STATUTES = "https://einundzwanzig.space/files/Statuten_v1.3.pdf"
    const val CONTACT = "https://einundzwanzig.space/kontakt/"
    const val PRIVACY = "https://einundzwanzig.space/datenschutz/"
    const val BEGINNER = "https://einundzwanzig.space/podcast/der-weg/"
    const val INTERVIEWS = "https://einundzwanzig.space/podcast/interviews/"
    const val YOUTUBE = "https://www.youtube.com/c/EinundzwanzigPodcast"
    const val MEDIA = "https://einundzwanzig.space/media/"
    const val SOUNDCLOUD = "https://soundcloud.com/einundzwanzig_beats"
    const val NOSTR = "https://njump.me/npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc"
    const val X = "https://x.com/_einundzwanzig_"
    const val INSTAGRAM = "https://www.instagram.com/einundzwanzig_podcast"
    const val SITE = "https://einundzwanzig.space"
}

internal object CommunityPortalTags {
    fun link(url: String) = "portal_web_$url"
}

/** A link row: [label] resource, or [rawLabel] for brand names that are not translated. */
private data class WebLink(
    @param:StringRes val label: Int?,
    val url: String,
    val icon: ImageVector,
    val rawLabel: String? = null,
)

/** Stateful "Portal-Webseite" (spec 09 §2.7). Static content, so there is no ViewModel. */
@Composable
fun CommunityPortalRoute() {
    val navigator = LocalNavigator.current
    val snackbar = remember { SnackbarHostState() }
    val links = rememberLinkOpener(snackbar)
    CommunityPortalScreen(
        snackbarHostState = snackbar,
        onBack = navigator::back,
        onOpen = { url -> links.open(url, LinkFailure.SnackbarWithUrl) },
    )
}

/** Stateless "Portal-Webseite". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPortalScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { EinundzwanzigLogo(Modifier.height(16.dp)) },
                    navigationIcon = { BackNavigationButton(onBack) },
                    actions = {
                        IconButton(onClick = { onOpen(PortalWebUrls.PORTAL) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(L10nR.string.portalOpen))
                        }
                    },
                    colors = AppTopBarDefaults.colors(),
                    scrollBehavior = scrollBehavior,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            QuickAccess(onOpen)
            Discover(onOpen)
            LinkSection(
                L10nR.string.portalAssociation,
                listOf(
                    WebLink(L10nR.string.portalAboutAssoc, PortalWebUrls.ASSOCIATION, Icons.Rounded.Info),
                    WebLink(L10nR.string.portalMembership, PortalWebUrls.MEMBERSHIP, Icons.Rounded.CardMembership),
                    WebLink(L10nR.string.portalStatutes, PortalWebUrls.STATUTES, Icons.Rounded.Description),
                    WebLink(L10nR.string.portalContact, PortalWebUrls.CONTACT, Icons.Rounded.Mail),
                    WebLink(L10nR.string.portalPrivacy, PortalWebUrls.PRIVACY, Icons.Rounded.PrivacyTip),
                ),
                onOpen,
            )
            LinkSection(
                L10nR.string.portalPodcastMedia,
                listOf(
                    WebLink(L10nR.string.portalPodcast, PortalWebUrls.PODCAST, Icons.Rounded.Podcasts),
                    WebLink(L10nR.string.portalBeginnerPath, PortalWebUrls.BEGINNER, Icons.Rounded.Explore),
                    WebLink(L10nR.string.portalInterviews, PortalWebUrls.INTERVIEWS, Icons.Rounded.Mic),
                    WebLink(null, PortalWebUrls.YOUTUBE, Icons.Rounded.PlayCircle, rawLabel = "YouTube"),
                    WebLink(L10nR.string.portalMediaArticles, PortalWebUrls.MEDIA, Icons.AutoMirrored.Rounded.Article),
                    WebLink(null, PortalWebUrls.SOUNDCLOUD, Icons.Rounded.MusicNote, rawLabel = "Soundcloud"),
                ),
                onOpen,
            )
            LinkSection(
                L10nR.string.portalSocialNetworks,
                listOf(
                    WebLink(null, PortalWebUrls.NOSTR, Icons.Rounded.Bolt, rawLabel = "Nostr"),
                    WebLink(null, PortalWebUrls.X, Icons.Rounded.Tag, rawLabel = "X / Twitter"),
                    WebLink(null, PortalWebUrls.INSTAGRAM, Icons.Rounded.PhotoCamera, rawLabel = "Instagram"),
                    WebLink(L10nR.string.portalShoutoutSend, PortalWebUrls.SHOUTOUT, Icons.Rounded.Campaign),
                ),
                onOpen,
            )
            Footer(onOpen)
        }
    }
}

@Composable
private fun QuickAccess(onOpen: (String) -> Unit) {
    val tiles = listOf(
        Triple(R.string.native_portal_qa_profile, PortalWebUrls.PORTAL_HOME, Icons.Rounded.Person),
        Triple(R.string.native_portal_qa_meetups, PortalWebUrls.MEETUPS, Icons.Rounded.Groups),
        Triple(R.string.native_portal_qa_podcast, PortalWebUrls.PODCAST, Icons.Rounded.Podcasts),
        Triple(R.string.native_portal_qa_shoutout, PortalWebUrls.SHOUTOUT, Icons.Rounded.Campaign),
        Triple(R.string.native_portal_qa_donate, PortalWebUrls.DONATE, Icons.Rounded.VolunteerActivism),
    )
    Column {
        SectionLabel(stringResource(L10nR.string.portalQuickAccess), Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tiles, key = { it.second + it.first }) { (label, url, icon) ->
                Tile(
                    modifier = Modifier
                        .width(68.dp)
                        .height(82.dp)
                        .testTag(CommunityPortalTags.link(url) + "_qa"),
                    onClick = { onOpen(url) },
                    contentPadding = PaddingValues(6.dp),
                ) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(label), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun Discover(onOpen: (String) -> Unit) {
    data class Card(val title: Int, val sub: Int, val url: String, val icon: ImageVector, val accent: Color)
    val ext = MaterialTheme.extendedColors
    val cards = listOf(
        Card(L10nR.string.portalMeetupMap, L10nR.string.portalMeetupMapSub, PortalWebUrls.MEETUPS, Icons.Rounded.Map, MaterialTheme.colorScheme.primary),
        Card(L10nR.string.portalShop, L10nR.string.portalMerch, PortalWebUrls.SHOPS, Icons.Rounded.Store, ext.community),
        Card(L10nR.string.portalProfile2, L10nR.string.portalProfile, PortalWebUrls.PORTAL_HOME, Icons.Rounded.Hub, ext.purple),
        Card(L10nR.string.portalSoundboard, L10nR.string.portalClipsSounds, PortalWebUrls.SOUNDBOARD, Icons.Rounded.RecordVoiceOver, MaterialTheme.colorScheme.primary),
    )
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionLabel(stringResource(L10nR.string.portalDiscover))
        Spacer(Modifier.height(10.dp))
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { c ->
                    Tile(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.65f)
                            .testTag(CommunityPortalTags.link(c.url) + "_d"),
                        onClick = { onOpen(c.url) },
                        borderColor = c.accent.copy(alpha = 0.3f),
                    ) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            IconTile(c.icon, tint = c.accent, size = 36.dp, iconSize = 20.dp)
                            Column {
                                Text(stringResource(c.title), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(c.sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkSection(@StringRes title: Int, links: List<WebLink>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionLabel(stringResource(title))
        Spacer(Modifier.height(10.dp))
        Tile(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
            Column {
                links.forEachIndexed { i, link ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val label = link.rawLabel ?: stringResource(link.label!!)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.Button) { onOpen(link.url) }
                            .padding(horizontal = 14.dp)
                            .testTag(CommunityPortalTags.link(link.url)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(link.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.extendedColors.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(L10nR.string.portalInfotainment), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(L10nR.string.portalTagline),
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "einundzwanzig.space",
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onOpen(PortalWebUrls.SITE) }
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .testTag(CommunityPortalTags.link(PortalWebUrls.SITE)),
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}

/** The Einundzwanzig wordmark (`assets/images/einundzwanzig_logo.svg`) in theme colours. */
@Composable
internal fun EinundzwanzigLogo(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val vector = remember(accent, ink) {
        ImageVector.Builder(
            name = "EinundzwanzigLogo",
            defaultWidth = (EinundzwanzigLogoPaths.VIEWPORT_WIDTH * 16f / EinundzwanzigLogoPaths.VIEWPORT_HEIGHT).dp,
            defaultHeight = 16.dp,
            viewportWidth = EinundzwanzigLogoPaths.VIEWPORT_WIDTH,
            viewportHeight = EinundzwanzigLogoPaths.VIEWPORT_HEIGHT,
        )
            .addPath(addPathNodes(EinundzwanzigLogoPaths.BITCOIN_DISC), fill = SolidColor(accent))
            .addPath(addPathNodes(EinundzwanzigLogoPaths.BITCOIN_B), fill = SolidColor(ink))
            .addPath(addPathNodes(EinundzwanzigLogoPaths.WORDMARK), fill = SolidColor(ink))
            .build()
    }
    Image(
        vector,
        contentDescription = "Einundzwanzig",
        modifier = modifier.aspectRatio(EinundzwanzigLogoPaths.VIEWPORT_WIDTH / EinundzwanzigLogoPaths.VIEWPORT_HEIGHT),
    )
}

@Preview
@Composable
private fun CommunityPortalPreview() {
    EinundzwanzigTheme {
        CommunityPortalScreen(SnackbarHostState(), onBack = {}, onOpen = {})
    }
}
