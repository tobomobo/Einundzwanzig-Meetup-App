package space.einundzwanzig.meetup.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBarDefaults
import space.einundzwanzig.meetup.core.ui.component.BackNavigationButton
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.nostr.NostrAvatar
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.chat.VEREIN_URL
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Flutter's default `SnackBar` duration (4 s) for send failures and the npub copy confirmation. */
private const val DEFAULT_SNACK_MILLIS = 4_000L

/** Test tags of the chat screen. */
object ChatTestTags {
    const val LIST = "chat_list"
    const val INPUT = "chat_input"
    const val SEND = "chat_send"
    const val JOIN = "chat_join"
    const val MEMBER_LINK = "chat_member_link"
    const val NPUB_CHIP = "chat_npub_chip"
    const val LOADING = "chat_loading"
}

/** `MeetupChat` destination: NIP-29 room [roomId] (spec 11 §2.1 `ChatScreen.room`). */
@Composable
fun MeetupChatRoute(roomId: String, roomName: String) {
    ChatRoute(ChatTarget.Room(roomId, roomName))
}

/** `EventChat` destination: NIP-22 thread on [eventAddress] (spec 11 §2.1 `ChatScreen.event`). */
@Composable
fun EventChatRoute(eventAddress: String, title: String) {
    ChatRoute(ChatTarget.Event(eventAddress, title))
}

@Composable
internal fun ChatRoute(
    target: ChatTarget,
    // Keyed per conversation: without a per-entry ViewModelStore, a second chat must not reuse
    // the first one's ViewModel (room, send target and live socket).
    viewModel: ChatViewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(
        key = "chat:$target",
        creationCallback = { factory -> factory.create(target) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ChatEffect.SendFailed -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showAppSnackbar(
                        message = resources.getString(L10nR.string.chatSendFailed, effect.reason),
                        tone = Tone.Error,
                        durationMillis = DEFAULT_SNACK_MILLIS,
                    )
                }
                is ChatEffect.JoinFailed -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showAppSnackbar(
                        message = resources.getString(L10nR.string.chatJoinFailed, effect.reason),
                        tone = Tone.Error,
                        durationMillis = AppSnackbarDurations.Long,
                        actionLabel = resources.getString(L10nR.string.chatMemberLink),
                    )
                    if (result == SnackbarResult.ActionPerformed) navigator.openUrl(VEREIN_URL)
                }
                is ChatEffect.CopyNpub -> {
                    SecretClipboard.copyPlain(context, effect.npub, "npub")
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showAppSnackbar(
                            message = resources.getString(L10nR.string.chatNpubCopied),
                            durationMillis = DEFAULT_SNACK_MILLIS,
                        )
                    }
                }
                is ChatEffect.OpenUrl -> navigator.openUrl(effect.url)
            }
        }
    }

    ChatScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = navigator::back,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Stateless chat screen (spec 11 §2.1): header with title + relay hint, message list (loading /
 * empty / bubbles), footer (join for non-members, composer for members), author sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    clock: ChatClock = rememberChatClock(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                subtitle = {
                    Text(
                        text = stringResource(if (state.isEvent) L10nR.string.chatEventHint else L10nR.string.chatRelayHint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.extendedColors.textTertiary,
                    )
                },
                navigationIcon = { BackNavigationButton(onBack) },
                colors = AppTopBarDefaults.colors(),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val body = when {
                    state.loading -> ChatBody.Loading
                    state.messages.isEmpty() -> ChatBody.Empty
                    else -> ChatBody.Messages
                }
                AnimatedContent(
                    targetState = body,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "chatBody",
                ) { b ->
                    when (b) {
                        ChatBody.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AppLoadingIndicator(modifier = Modifier.testTag(ChatTestTags.LOADING))
                        }
                        ChatBody.Empty -> ChatEmpty(state.isEvent)
                        ChatBody.Messages -> MessageList(state, clock, onAction)
                    }
                }
            }
            ChatFooter(state, onAction)
        }
    }

    state.author?.let { author -> AuthorSheet(author, onAction) }
}

private enum class ChatBody { Loading, Empty, Messages }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatEmpty(isEvent: Boolean) = BoxWithConstraints(Modifier.fillMaxSize()) {
    // Scrollable so the text stays reachable on small screens with the keyboard open.
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.extendedColors.nostr.copy(alpha = 0.14f), MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Forum, contentDescription = null, tint = MaterialTheme.extendedColors.nostr, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(if (isEvent) L10nR.string.chatEventEmpty else L10nR.string.chatEmpty),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = MaterialTheme.extendedColors.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageList(state: ChatUiState, clock: ChatClock, onAction: (ChatAction) -> Unit) {
    val listState = rememberLazyListState()
    // Newest at the bottom: a reversed layout keeps the list anchored there (also with the IME).
    val newestFirst = remember(state.messages) { state.messages.asReversed() }
    // Only a new request scrolls; a recreated composition (rotation, theme, locale) keeps the
    // position rememberLazyListState restored.
    var handledScroll by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.scrollRequest) {
        if (newestFirst.isEmpty() || state.scrollRequest == handledScroll) return@LaunchedEffect
        handledScroll = state.scrollRequest
        if (listState.firstVisibleItemIndex <= 3) listState.animateScrollToItem(0) else listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag(ChatTestTags.LIST),
    ) {
        items(newestFirst, key = { it.id }) { m ->
            MessageRow(m, clock, onAction, Modifier.animateItem())
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessageItem, clock: ChatClock, onAction: (ChatAction) -> Unit, modifier: Modifier = Modifier) {
    val sender = if (m.mine) stringResource(L10nR.string.chatYou) else m.senderName
    val timeLabel = remember(m.createdAt, clock) { clock.label(m.createdAt) }
    val openLabel = stringResource(R.string.native_chat_author_open, sender)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!m.mine) {
            NostrAvatar(
                fallbackText = m.avatarLetter,
                radius = 14.dp,
                pubkeyHex = m.pubkey,
                backgroundColor = MaterialTheme.extendedColors.nostr,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = openLabel, role = Role.Button) { onAction(ChatAction.ShowAuthor(m.pubkey)) }
                .heightIn(min = 48.dp),
            horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
        ) {
            Row(
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sender,
                    color = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.nostr,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = timeLabel,
                    color = MaterialTheme.extendedColors.textTertiary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                )
            }
            val shape = if (m.mine) {
                RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
            }
            val primary = MaterialTheme.colorScheme.primary
            Text(
                text = m.content,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.6.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(if (m.mine) primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer, shape)
                    .border(
                        Dimens.Hairline,
                        if (m.mine) primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
                        shape,
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun ChatFooter(state: ChatUiState, onAction: (ChatAction) -> Unit) {
    when {
        // Membership unknown: deliberately no join button yet.
        state.loading -> Spacer(Modifier.height(24.dp))
        !state.member -> JoinFooter(state.joining, onAction)
        else -> Composer(state.input, state.sending, onAction)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JoinFooter(joining: Boolean, onAction: (ChatAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val joinLabel = stringResource(L10nR.string.chatJoin)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(L10nR.string.chatJoinHint),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            onClick = { onAction(ChatAction.OpenVerein) },
            shape = Dimens.TileShape,
            color = primary.copy(alpha = 0.08f),
            border = BorderStroke(Dimens.Hairline, primary.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().testTag(ChatTestTags.MEMBER_LINK),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(L10nR.string.chatMemberHint),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(L10nR.string.chatMemberLink),
                        color = primary,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onAction(ChatAction.Join) },
            enabled = !joining,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = primary.copy(alpha = 0.6f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                // The spinner replaces the text while joining; keep the button labelled.
                .semantics { if (joining) contentDescription = joinLabel }
                .testTag(ChatTestTags.JOIN),
        ) {
            if (joining) {
                AppLoadingIndicator(size = 28.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(joinLabel, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(input: String, sending: Boolean, onAction: (ChatAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val sendLabel = stringResource(R.string.native_chat_send)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { onAction(ChatAction.InputChanged(it)) },
            placeholder = { Text(stringResource(L10nR.string.chatPlaceholder)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            minLines = 1,
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onAction(ChatAction.Send) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = primary,
            ),
            modifier = Modifier
                .weight(1f)
                .testTag(ChatTestTags.INPUT),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { onAction(ChatAction.Send) },
            enabled = !sending,
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                disabledContainerColor = primary.copy(alpha = 0.6f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(52.dp)
                // Labelled on the button: the icon is replaced by a spinner while sending.
                .semantics { contentDescription = sendLabel }
                .testTag(ChatTestTags.SEND),
        ) {
            if (sending) {
                AppLoadingIndicator(size = 26.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthorSheet(author: ChatAuthor, onAction: (ChatAction) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onAction(ChatAction.DismissAuthor) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NostrAvatar(
                fallbackText = author.avatarLetter,
                radius = 32.dp,
                pubkeyHex = author.pubkey,
                backgroundColor = MaterialTheme.extendedColors.nostr,
                shape = MaterialShapes.Cookie9Sided.toShape(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = author.display,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            val copyLabel = stringResource(L10nR.string.chatCopyNpub)
            Surface(
                onClick = { onAction(ChatAction.CopyNpub) },
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag(ChatTestTags.NPUB_CHIP),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = author.shortNpub,
                        style = MonoStyle.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = copyLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

// region Previews

private val previewClock = ChatClock(LocalDate.of(2026, 10, 21), ZoneOffset.UTC)

private fun previewTime(hour: Int, minute: Int): Instant =
    previewClock.today.atTime(hour, minute).toInstant(ZoneOffset.UTC)

private val previewMessages = listOf(
    ChatMessageItem("1", "a".repeat(64), "Servus! Wer kommt heute zum Stammtisch?", false, "satoshi", "S", previewTime(18, 2)),
    ChatMessageItem("2", "b".repeat(64), "Bin dabei, bringe die Hardware-Wallet mit.", false, "bbbbbb…bbbb", "B", previewTime(18, 5)),
    ChatMessageItem("3", "c".repeat(64), "Super, ich reserviere einen Tisch für 8.", true, "", "C", previewTime(18, 7)),
)

@Preview(name = "Chat – member")
@Composable
private fun ChatScreenMemberPreview() {
    EinundzwanzigTheme {
        ChatScreen(
            state = ChatUiState(isEvent = false, title = "Einundzwanzig Wien", loading = false, member = true, messages = previewMessages),
            onAction = {},
            onBack = {},
            clock = previewClock,
        )
    }
}

@Preview(name = "Chat – not a member")
@Composable
private fun ChatScreenJoinPreview() {
    EinundzwanzigTheme {
        ChatScreen(
            state = ChatUiState(isEvent = false, title = "Einundzwanzig Wien", loading = false, member = false, messages = previewMessages),
            onAction = {},
            onBack = {},
            clock = previewClock,
        )
    }
}

@Preview(name = "Event chat – empty")
@Composable
private fun ChatScreenEventEmptyPreview() {
    EinundzwanzigTheme {
        ChatScreen(
            state = ChatUiState(isEvent = true, title = "Bitcoin-Workshop", loading = false, member = true),
            onAction = {},
            onBack = {},
            clock = previewClock,
        )
    }
}

// endregion
