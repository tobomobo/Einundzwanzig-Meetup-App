package space.einundzwanzig.meetup.feature.profile.edit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.motion.pressScale
import space.einundzwanzig.meetup.core.ui.nostr.AvatarImage
import space.einundzwanzig.meetup.core.ui.nostr.NostrAvatar
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.profile.R
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerSheetState

/**
 * Stateless profile screen (spec 07 §2.2): read-only view for verified organizers, edit form for
 * everyone else, the key management of spec 01 §2.7 and every dialog/sheet of both specs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    state: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    modifier: Modifier = Modifier,
    bunkerState: BunkerSheetState = BunkerSheetState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTopBar(
                title = stringResource(if (state.isEditing) L10nR.string.profileEditTitle else L10nR.string.profileTitle),
                onBack = { onAction(ProfileEditAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        val content = when {
            state.isLoading -> ContentKind.LOADING
            state.loadError != null -> ContentKind.ERROR
            state.isEditing -> ContentKind.EDIT
            else -> ContentKind.READ_ONLY
        }
        val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = content,
            transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
            label = "profileContent",
            modifier = Modifier.padding(padding),
        ) { kind ->
            when (kind) {
                ContentKind.LOADING -> FullScreenLoading(Modifier.testTag(ProfileEditTags.LOADING))
                ContentKind.ERROR -> EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = stringResource(L10nR.string.errorGeneric, state.loadError.orEmpty()),
                    subtitle = "",
                    actionLabel = stringResource(L10nR.string.actionRetry),
                    onAction = { onAction(ProfileEditAction.Retry) },
                    accentColor = MaterialTheme.colorScheme.error,
                )
                ContentKind.READ_ONLY, ContentKind.EDIT -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag(ProfileEditTags.CONTENT)
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 100.dp),
                ) {
                    if (kind == ContentKind.READ_ONLY) ReadOnlyContent(state, onAction) else EditForm(state, onAction)
                }
            }
        }
    }
    ProfileDialogs(state = state, bunkerState = bunkerState, onAction = onAction)
}

private enum class ContentKind { LOADING, ERROR, READ_ONLY, EDIT }

// ------------------------------------------------------------------------------------ read-only

@Composable
private fun ReadOnlyContent(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val success = MaterialTheme.extendedColors.success
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileAvatar(state, size = 88.dp, background = success, editable = false, onAction = onAction)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Verified, contentDescription = null, tint = success, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(L10nR.string.profileVerified),
                color = success,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
    }
    Spacer(Modifier.height(30.dp))
    InfoTile(Icons.Rounded.Person, stringResource(L10nR.string.profileNickname), state.savedNickname)
    Spacer(Modifier.height(10.dp))
    InfoTile(Icons.Rounded.Home, stringResource(L10nR.string.profileHomeMeetup), state.selectedHome.ifEmpty { "-" })
    if (state.hasNostrKey) {
        Spacer(Modifier.height(10.dp))
        InfoTile(Icons.Rounded.Key, stringResource(R.string.native_nostr_label), NpubFormat.shortenNpub(state.npub), mono = true)
        SignerManagement(state, onAction)
    }
    Spacer(Modifier.height(24.dp))
    SectionLabel(stringResource(L10nR.string.profileStrengthen), MaterialTheme.extendedColors.success)
    Spacer(Modifier.height(12.dp))
    IdentityTiles(state, onAction)
    Spacer(Modifier.height(30.dp))
    OutlinedButton(
        onClick = { onAction(ProfileEditAction.RequestEdit) },
        shapes = ButtonDefaults.shapes(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(ProfileEditTags.EDIT_LOSE_STATUS),
    ) {
        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(L10nR.string.profileEditLoseStatus), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, mono: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = if (mono) space.einundzwanzig.meetup.core.ui.theme.MonoStyle else MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------ edit form

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditForm(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ProfileAvatar(state, size = 64.dp, background = MaterialTheme.colorScheme.primary, editable = true, onAction = onAction)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            SectionLabel(stringResource(L10nR.string.profileIdentity), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(L10nR.string.profileIntro),
                color = MaterialTheme.extendedColors.textTertiary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    NicknameField(state, onAction)
    Spacer(Modifier.height(16.dp))
    HomeMeetupField(state, onAction)

    Spacer(Modifier.height(30.dp))
    SectionLabel(stringResource(L10nR.string.profileNostrKey), MaterialTheme.extendedColors.nostr)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(L10nR.string.profileKeyDesc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    if (state.hasNostrKey) KeyActiveCard(state, onAction) else NoKeyCard(state, onAction)

    Spacer(Modifier.height(24.dp))
    SectionLabel(stringResource(L10nR.string.profileStrengthen), MaterialTheme.extendedColors.success)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(L10nR.string.profileStrengthenDesc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    IdentityTiles(state, onAction)

    Spacer(Modifier.height(30.dp))
    Button(
        onClick = { onAction(ProfileEditAction.Save) },
        enabled = !state.isSaving,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ProfileEditTags.SAVE),
    ) {
        if (state.isSaving) {
            AppLoadingIndicator(size = 24.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(stringResource(L10nR.string.profileSave), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun NicknameField(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val error = state.nicknameError
    TextField(
        value = state.nickname,
        onValueChange = { onAction(ProfileEditAction.NicknameChanged(it)) },
        label = { Text(stringResource(L10nR.string.profileNickname)) },
        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let {
            {
                Text(
                    stringResource(
                        when (it) {
                            NicknameError.REQUIRED -> L10nR.string.profileNicknameReq
                            NicknameError.ANON -> L10nR.string.profileNicknameAnon
                            NicknameError.TOO_SHORT -> L10nR.string.profileNicknameMin
                        },
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.NICKNAME),
    )
}

@Composable
private fun HomeMeetupField(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val missing = state.homeMissing
    val borderColor by animateColorAsState(
        if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "homeBorder",
    )
    val chooseLabel = stringResource(L10nR.string.profileChooseMeetup)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(if (missing) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = chooseLabel) { onAction(ProfileEditAction.OpenMeetupPicker) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(ProfileEditTags.HOME_FIELD),
        ) {
            Icon(
                Icons.Rounded.Home,
                contentDescription = stringResource(L10nR.string.profileHomeMeetup),
                tint = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (state.selectedHome.isEmpty()) {
                    Text(chooseLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                } else {
                    // Flutter shows the raw meetup ID; the resolved name is added as a second line.
                    Text(state.selectedHome, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (state.homeLabel.isNotEmpty() && state.homeLabel != state.selectedHome) {
                        Text(state.homeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.favorites.size > 1) {
                val more = state.favorites.size - 1
                val moreA11y = pluralStringResource(R.plurals.native_more_favorites_a11y, more, more)
                Text(
                    stringResource(R.string.native_more_favorites, more),
                    modifier = Modifier.semantics { contentDescription = moreA11y },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(missing) {
            Text(
                stringResource(L10nR.string.profileMeetupReq),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------------ avatar

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileAvatar(
    state: ProfileEditUiState,
    size: androidx.compose.ui.unit.Dp,
    background: Color,
    editable: Boolean,
    onAction: (ProfileEditAction) -> Unit,
) {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val fallback = state.savedNickname.ifEmpty { state.nickname }
    val description = stringResource(R.string.native_profile_avatar)
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (editable) {
                    Modifier
                        .clip(shape)
                        .clickable(role = Role.Button, onClickLabel = description) { onAction(ProfileEditAction.PickAvatar) }
                        .semantics { contentDescription = description }
                        .testTag(ProfileEditTags.AVATAR)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val local = state.localPicture
        if (!local.isNullOrEmpty()) {
            AvatarImage(model = File(local), fallbackText = fallback, backgroundColor = background, size = size, shape = shape)
        } else {
            NostrAvatar(
                fallbackText = fallback,
                backgroundColor = background,
                radius = size / 2,
                pubkeyHex = state.pubkeyHex,
                shape = shape,
            )
        }
        if (state.isImportingPicture) {
            Box(Modifier.matchParentSize().clip(shape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                AppLoadingIndicator(size = size / 2)
            }
        }
        if (editable) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------ identity tiles

@Composable
private fun IdentityTiles(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val n = state.platformProofCount
    IdentityTile(
        icon = Icons.Rounded.Link,
        color = MaterialTheme.extendedColors.success,
        title = stringResource(L10nR.string.profileLinkPlatforms),
        subtitle = if (n > 0) pluralStringResource(R.plurals.native_platforms_active, n, n) else stringResource(L10nR.string.profilePlatformsSub),
        done = n > 0,
        onClick = { onAction(ProfileEditAction.OpenPlatformProofs) },
        tag = ProfileEditTags.TILE_PLATFORMS,
    )
    Spacer(Modifier.height(10.dp))
    IdentityTile(
        icon = Icons.Rounded.Bolt,
        color = MaterialTheme.extendedColors.bitcoin,
        title = stringResource(L10nR.string.profileProofHumanity),
        subtitle = stringResource(if (state.humanityVerified) L10nR.string.profileLightningActive else L10nR.string.profileZapCheck),
        done = state.humanityVerified,
        onClick = { onAction(ProfileEditAction.OpenHumanityProof) },
        tag = ProfileEditTags.TILE_HUMANITY,
    )
}

@Composable
private fun IdentityTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    done: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (done) color.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth().pressScale(interaction).testTag(tag),
    ) {
        Row(Modifier.padding(14.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background((if (done) color else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (done) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    color = if (done) color.copy(alpha = 0.8f) else MaterialTheme.extendedColors.textTertiary,
                    fontSize = 12.sp,
                )
            }
            if (done) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

/** Test tags of the profile screen. */
object ProfileEditTags {
    const val LOADING = "profile_loading"
    const val CONTENT = "profile_content"
    const val NICKNAME = "profile_nickname"
    const val HOME_FIELD = "profile_home"
    const val SAVE = "profile_save"
    const val AVATAR = "profile_avatar"
    const val EDIT_LOSE_STATUS = "profile_edit_lose_status"
    const val TILE_PLATFORMS = "profile_tile_platforms"
    const val TILE_HUMANITY = "profile_tile_humanity"
    const val CREATE_KEY = "profile_create_key"
    const val IMPORT_NSEC = "profile_import_nsec"
    const val CONNECT_AMBER = "profile_connect_amber"
    const val CONNECT_BUNKER = "profile_connect_bunker"
    const val NPUB_COPY = "profile_npub_copy"
    const val NSEC_SHOW = "profile_nsec_show"
    const val EXPORT = "profile_export"
    const val CHECK_BUNKER = "profile_check_bunker"
    const val DISCONNECT = "profile_disconnect"
    const val NSEC_INPUT = "profile_nsec_input"
    const val NSEC_IMPORT = "profile_nsec_import"
    const val NCRYPTSEC_COPY = "profile_ncryptsec_copy"
    const val NCRYPTSEC_OTHER_PASSWORD = "profile_ncryptsec_other_password"
    const val PASSWORD = "profile_export_password"
    const val PASSWORD_CONFIRM = "profile_export_password_confirm"
    const val MEETUP_SEARCH = "profile_meetup_search"
    const val MEETUP_DONE = "profile_meetup_done"
    fun meetupRow(id: String) = "profile_meetup:$id"
}

// ------------------------------------------------------------------------------------ previews

private val previewMeetups = listOf(
    Meetup(id = "107", name = "Einundzwanzig Göttingen", city = "Göttingen", country = "DE", telegramLink = "", lat = 0.0, lng = 0.0),
    Meetup(id = "12", name = "Einundzwanzig München", city = "München", country = "DE", telegramLink = "", lat = 0.0, lng = 0.0),
)

@Preview(name = "Profile – edit, local key", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 1500)
@Composable
private fun ProfileEditPreview() {
    EinundzwanzigTheme {
        ProfileEditScreen(
            state = ProfileEditUiState(
                isLoading = false,
                nickname = "Satoshi",
                savedNickname = "Satoshi",
                selectedHome = "107",
                homeLabel = "Göttingen",
                favorites = listOf("107", "12"),
                meetups = previewMeetups,
                hasLocalKey = true,
                canSign = true,
                npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
                platformProofCount = 2,
            ),
            onAction = {},
        )
    }
}

@Preview(name = "Profile – edit, no key", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 1500)
@Composable
private fun ProfileEditNoKeyPreview() {
    EinundzwanzigTheme {
        ProfileEditScreen(
            state = ProfileEditUiState(isLoading = false, showValidation = true, homeMissing = true),
            onAction = {},
        )
    }
}

@Preview(name = "Profile – verified (read-only), bunker", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 1200)
@Composable
private fun ProfileReadOnlyPreview() {
    EinundzwanzigTheme {
        ProfileEditScreen(
            state = ProfileEditUiState(
                isLoading = false,
                isEditing = false,
                nickname = "Satoshi",
                savedNickname = "Satoshi",
                selectedHome = "107",
                signingMode = SigningMode.NIP46,
                canSign = true,
                npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
                humanityVerified = true,
            ),
            onAction = {},
        )
    }
}
