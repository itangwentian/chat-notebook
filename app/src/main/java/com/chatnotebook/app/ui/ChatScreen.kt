package com.chatnotebook.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.chatnotebook.app.R
import com.chatnotebook.app.data.BubbleColor
import com.chatnotebook.app.data.BubbleFormat
import com.chatnotebook.app.data.BubbleTextStyle
import com.chatnotebook.app.data.DocumentBlock
import com.chatnotebook.app.data.EditRecord
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageFormatSegment
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.QuoteRecord
import com.chatnotebook.app.data.ReactionRecord
import com.chatnotebook.app.data.REMINDER_SCHEDULED
import com.chatnotebook.app.data.ReminderPreview
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.SessionDocumentBlocks
import com.chatnotebook.app.data.SessionRecord
import com.chatnotebook.app.data.buildMergedSegments
import com.chatnotebook.app.reminder.LocalReminderSuggestion
import com.chatnotebook.app.reminder.defaultReminderInstant
import com.chatnotebook.app.reminder.reminderWheelInitialIndex
import com.chatnotebook.app.reminder.reminderWheelItemCount
import com.chatnotebook.app.reminder.reminderWheelValue
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class ChatActions(
    val onPickDirectory: () -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onCloseSettings: () -> Unit = {},
    val onRequestRename: (SessionRecord) -> Unit = {},
    val onCancelRename: () -> Unit = {},
    val onConfirmRename: (String) -> Unit = {},
    val onRequestDelete: (SessionRecord) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onMessageClick: (String) -> Unit = {},
    val onInputChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onImagesPicked: (List<Uri>) -> Unit = {},
    val onRemoveDraftAttachment: (MessageAttachment) -> Unit = {},
    val onVoiceRecorded: (File, Long) -> Unit = { _, _ -> },
    val attachmentUri: (String) -> Uri? = { null },
    val saveImage: (MessageAttachment, Uri) -> Unit = { _, _ -> },
    val shareImage: (MessageAttachment, Uri) -> Unit = { _, _ -> },
    val onShowNotice: (String) -> Unit = {},
    val onRequestEdit: (MessageRecord) -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onRequestQuote: (MessageRecord) -> Unit = {},
    val onCancelQuote: () -> Unit = {},
    val onApplyBubbleFormat: (String, String?) -> Unit = { _, _ -> },
    val onRequestDeleteMessages: (Set<String>) -> Unit = {},
    val onCancelDeleteMessages: () -> Unit = {},
    val onConfirmDeleteMessages: () -> Unit = {},
    val onStartMultiSelect: (MessageRecord) -> Unit = {},
    val onCancelMultiSelect: () -> Unit = {},
    val onShowHistory: (MessageRecord) -> Unit = {},
    val onDismissHistory: () -> Unit = {},
    val onToggleSessionSort: () -> Unit = {},
    val onOpenOrganizationResults: (String) -> Unit = {},
    val onRequestMergeMessages: () -> Unit = {},
    val onCancelMergeMessages: () -> Unit = {},
    val onConfirmMergeMessages: () -> Unit = {},
    val onRequestForwardMessages: () -> Unit = {},
    val onCancelForwardMessages: () -> Unit = {},
    val onConfirmForwardMessages: (String) -> Unit = {},
    val onShareForwardMessages: (ComponentName) -> Unit = {},
    val onRequestMessageReminder: (MessageRecord) -> Unit = {},
    val onCancelReminder: (String) -> Unit = {},
    val onConfirmReminder: (Long) -> Unit = {},
    val onDismissReminder: () -> Unit = {},
    val onOpenSuggestedReminder: (String) -> Unit = {},
    val onDismissReminderSuggestion: (String) -> Unit = {},
    val onOpenReminderList: () -> Unit = {},
    val onCloseReminderList: () -> Unit = {},
    val onOpenReminder: (String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottom > 0
    val compactScreen = LocalConfiguration.current.screenHeightDp < 600
    val compactIme = imeVisible && compactScreen
    val audio = rememberAudioController()
    val currentState by rememberUpdatedState(state)

    LaunchedEffect(snackbar) {
        snapshotFlow { currentState.notice }
            .filterNotNull()
            .collect { message ->
                actions.onDismissNotice()
                snackbar.showSnackbar(message)
            }
    }

    LaunchedEffect(state.messages) {
        val key = audio.playingKey ?: return@LaunchedEffect
        val stillPresent = state.messages.any { message ->
            message.attachments.any { it.id == key }
        }
        if (!stillPresent) audio.stop()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.multiSelect,
        drawerContent = {
            ChatDrawer(
                state = state,
                actions = actions,
                onCloseDrawer = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (state.multiSelect) {
                    MultiSelectBar(
                        state = state,
                        actions = actions,
                        onCopy = {
                            val text = state.messages
                                .filter { it.id in state.selectedMessages }
                                .joinToString("\n") { it.currentContent }
                            if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                            actions.onCancelMultiSelect()
                        },
                    )
                } else if (!compactIme) {
                    TopAppBar(
                        title = {
                            Text(state.activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("open_drawer"),
                            ) {
                                Text("☰", style = MaterialTheme.typography.headlineSmall)
                            }
                        },
                        actions = {
                            if (!state.directoryReady && !state.restoringDirectory && compactScreen) {
                                TextButton(onClick = actions.onPickDirectory, modifier = Modifier.testTag("pick_directory")) {
                                    Text(stringResource(R.string.directory_shortcut))
                                }
                            }
                            if (state.directoryReady && state.scheduledReminders.isNotEmpty()) {
                                IconButton(
                                    onClick = actions.onOpenReminderList,
                                    modifier = Modifier.testTag("reminder_list_button"),
                                ) {
                                    Text("⏰", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(if (compactIme) Modifier.statusBarsPadding() else Modifier)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (!state.directoryReady && !state.restoringDirectory && !imeVisible && !compactScreen) {
                    DirectoryPrompt(actions.onPickDirectory)
                }
                MessageList(
                    state = state,
                    modifier = Modifier.weight(1f),
                    actions = actions,
                    audio = audio,
                    imeBottom = imeBottom,
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                )
                if (!state.multiSelect) {
                    Composer(state, actions, compact = compactIme)
                }
            }
        }
    }

    state.renaming?.let { RenameDialog(it, actions) }
    state.pendingDelete?.let { DeleteSessionDialog(it, actions) }
    state.pendingDeleteMessages?.let { DeleteMessagesDialog(it, actions) }
    state.history?.let { HistoryDialog(it, actions) }
    if (state.pendingMergeMessages != null) {
        MergeMessagesDialog(state, actions)
    }
    if (state.forwardPickerVisible) {
        ForwardMessagesSheet(state, actions)
    }
    state.reminderTarget?.let { ReminderDialog(it, state.reminders, actions) }
    if (state.reminderListVisible) {
        ReminderListSheet(state, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectBar(state: ChatUiState, actions: ChatActions, onCopy: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.selected_count, state.selectedMessages.size)) },
        navigationIcon = {
            IconButton(onClick = actions.onCancelMultiSelect, modifier = Modifier.testTag("cancel_multi")) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        },
        actions = {
            TextButton(
                onClick = onCopy,
                enabled = state.selectedMessages.isNotEmpty(),
                modifier = Modifier.testTag("copy_selected"),
            ) {
                Text(stringResource(R.string.copy))
            }
            TextButton(
                onClick = { actions.onRequestDeleteMessages(state.selectedMessages) },
                enabled = state.selectedMessages.isNotEmpty(),
                modifier = Modifier.testTag("delete_selected"),
            ) {
                Text(stringResource(R.string.delete))
            }
            TextButton(
                onClick = actions.onRequestMergeMessages,
                enabled = state.selectedMessages.size >= 2,
                modifier = Modifier.testTag("merge_selected"),
            ) {
                Text("合并")
            }
            TextButton(
                onClick = actions.onRequestForwardMessages,
                enabled = state.selectedMessages.isNotEmpty(),
                modifier = Modifier.testTag("forward_selected"),
            ) {
                Text("转发")
            }
        },
    )
}

@Composable
private fun DirectoryPrompt(onPickDirectory: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.directory_required), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.directory_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = onPickDirectory, modifier = Modifier.testTag("pick_directory")) {
                Text(stringResource(R.string.choose_directory))
            }
        }
    }
}

private data class DateGroup(val date: LocalDate, val messages: List<MessageRecord>)

/** Index in the flattened list, counting the date header and any suggestion bubble before the target. */
private fun itemIndexOf(
    groups: List<DateGroup>,
    messageId: String,
    suggestions: Map<String, LocalReminderSuggestion>,
): Int? {
    var index = 0
    groups.forEach { group ->
        index += 1
        group.messages.forEach { message ->
            if (message.id == messageId) return index
            index += 1
            if (suggestions[message.id]?.contentVersion == message.contentVersion) index += 1
        }
    }
    return null
}

@Composable
private fun MessageList(
    state: ChatUiState,
    modifier: Modifier,
    actions: ChatActions,
    audio: AudioController,
    imeBottom: Int,
    onCopy: (String) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val groups = remember(state.messages, zone) {
        state.messages
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (date, messages) -> DateGroup(date, messages) }
    }
    val today = remember { LocalDate.now(zone) }
    val itemCount = groups.sumOf { 1 + it.messages.size }
    val listCount = itemCount + state.messages.count { message ->
        state.reminderSuggestions[message.id]?.contentVersion == message.contentVersion
    }
    val listState = rememberLazyListState()
    var previousSession by remember { mutableStateOf<String?>(null) }
    var lastScrollSignal by remember { mutableStateOf(state.scrollSignal) }
    var previousItemCount by remember { mutableStateOf(0) }

    LaunchedEffect(state.activeSessionId, state.scrollSignal, listCount) {
        if (listCount == 0) {
            previousItemCount = 0
            return@LaunchedEffect
        }
        val switched = previousSession != state.activeSessionId
        previousSession = state.activeSessionId
        val moved = state.scrollSignal != lastScrollSignal
        lastScrollSignal = state.scrollSignal
        val grew = !switched && listCount > previousItemCount
        previousItemCount = listCount
        if (switched || moved || grew) {
            val target = state.scrollTargetId?.let { id -> itemIndexOf(groups, id, state.reminderSuggestions) }
            listState.animateScrollToItem((target ?: listCount - 1).coerceIn(0, listCount - 1))
        }
    }

    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var atBottomBeforeIme by remember { mutableStateOf(true) }
    LaunchedEffect(imeBottom, atBottom, itemCount) {
        if (imeBottom == 0 && itemCount > 0) atBottomBeforeIme = atBottom
    }
    // 键盘弹出动画期间，输入法高度每帧变化都会触发一次贴底；动画结束后不再干预，手动滚动不会被拉回。
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && itemCount > 0 && atBottomBeforeIme) {
            listState.scrollToItem(listCount - 1)
        }
    }

    if (groups.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.restoringDirectory && !state.directoryReady) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).testTag("restoring_directory"),
                )
            } else {
                Text(
                    stringResource(
                        if (state.directoryReady) R.string.no_messages else R.string.empty_chat,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag("chat_messages"),
    ) {
        groups.forEach { group ->
            item(key = "date-${group.date}") {
                Text(
                    text = dateLabel(group.date, today),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            group.messages.forEach { message ->
                item(key = message.id) {
                    MessageBubble(
                        message = message,
                        quote = state.quotes[message.id],
                        reactions = state.reactions[message.id].orEmpty(),
                        sourceTitle = state.messageSources[message.id],
                        revealed = state.revealedMessageId == message.id,
                        highlighted = state.highlightMessageId == message.id,
                        hasReminder = message.id in state.reminderMessageIds,
                        multiSelect = state.multiSelect,
                        selected = message.id in state.selectedMessages,
                        directoryReady = state.directoryReady,
                        actions = actions,
                        audio = audio,
                        zone = zone,
                        today = today,
                        onCopy = onCopy,
                    )
                }
                val suggestion = state.reminderSuggestions[message.id]
                    ?.takeIf { it.contentVersion == message.contentVersion }
                if (suggestion != null) {
                    item(key = "suggestion-${message.id}") {
                        ReminderSuggestionBubble(
                            suggestion = suggestion,
                            zone = zone,
                            today = today,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.today)
    today.minusDays(1) -> stringResource(R.string.yesterday)
    else -> if (date.year == today.year) {
        "${date.monthValue}月${date.dayOfMonth}日"
    } else {
        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val documentTimeFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
private const val MAX_PICK_IMAGES = 9

@Composable
private fun ReminderSuggestionBubble(
    suggestion: LocalReminderSuggestion,
    zone: ZoneId,
    today: LocalDate,
    actions: ChatActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp).testTag("suggestion_${suggestion.messageId}"),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                Text("提醒建议", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = "${reminderTimeLabel(suggestion.triggerAt, zone, today)}提醒你",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp).testTag("suggestion_reminder"),
                )
                if (suggestion.phrase.isNotBlank()) {
                    Text(
                        text = "「${suggestion.phrase}」",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                SuggestionActions(
                    onApply = { actions.onOpenSuggestedReminder(suggestion.messageId) },
                    onIgnore = { actions.onDismissReminderSuggestion(suggestion.messageId) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionActions(
    onApply: () -> Unit,
    onIgnore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onIgnore,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.testTag("suggestion_ignore_reminder"),
        ) {
            Text("忽略", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(
            onClick = onApply,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.testTag("suggestion_apply_reminder"),
        ) {
            Text("创建日程", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun reminderTimeLabel(triggerAt: Long, zone: ZoneId, today: LocalDate): String {
    val time = Instant.ofEpochMilli(triggerAt).atZone(zone)
    val clock = time.format(clockFormat)
    return when (time.toLocalDate()) {
        today -> "今天 $clock"
        today.plusDays(1) -> "明天 $clock"
        else -> time.format(dateTimeFormat)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageRecord,
    quote: QuoteRecord?,
    reactions: List<ReactionRecord>,
    sourceTitle: String?,
    revealed: Boolean,
    highlighted: Boolean,
    hasReminder: Boolean,
    multiSelect: Boolean,
    selected: Boolean,
    directoryReady: Boolean,
    actions: ChatActions,
    audio: AudioController,
    zone: ZoneId,
    today: LocalDate,
    onCopy: (String) -> Unit,
) {
    val isUser = message.role == "user"
    val format = reactions.firstNotNullOfOrNull { BubbleFormat.fromId(it.emoji) }
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var preview by remember(message.id) { mutableStateOf<MessageAttachment?>(null) }
    val images = message.attachments.filter { it.kind == "image" }
    val recording = message.attachments.firstOrNull { it.kind == "audio" }
    val hasText = message.currentContent.isNotBlank() || message.formatSegments.isNotEmpty()
    val baseColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    // 有尚未触发的提醒时底色略偏红，便于在列表中一眼识别。
    val restingColor = if (hasReminder) {
        lerp(baseColor, MaterialTheme.colorScheme.errorContainer, 0.5f)
    } else {
        baseColor
    }
    // 通知定位到该消息时，底色在常态色与高亮色之间来回切换，约 5 秒后由 ViewModel 清除。
    val bubbleColor = if (highlighted) {
        val blink by rememberInfiniteTransition(label = "message_highlight")
            .animateFloat(
                initialValue = 0.15f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 400), RepeatMode.Reverse),
                label = "message_highlight_alpha",
            )
        lerp(restingColor, MaterialTheme.colorScheme.tertiaryContainer, blink)
    } else {
        restingColor
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            if (sourceTitle != null) {
                Text(
                    text = "来源：$sourceTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp).testTag("source_${message.id}"),
                )
            }
            quote?.let { QuotePreview(it) }
            Box {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp),
                    border = if (selected) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .testTag("message_${message.id}")
                        .combinedClickable(
                            onClick = { actions.onMessageClick(message.id) },
                            onLongClick = { if (!multiSelect) menuOpen = true },
                        ),
                ) {
                    Column {
                        if (hasText) {
                            Text(
                                text = if (message.formatSegments.isEmpty()) {
                                    AnnotatedString(message.currentContent)
                                } else {
                                    buildSegmentsText(message.formatSegments)
                                },
                                style = formatTextStyle(format),
                                color = formatColor(format),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        if (images.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = if (hasText) 0.dp else 8.dp,
                                    bottom = 8.dp,
                                ),
                            ) {
                                items(images, key = { it.id }) { attachment ->
                                    AttachmentThumbnail(
                                        attachment = attachment,
                                        uri = actions.attachmentUri(attachment.relativePath),
                                        directoryReady = directoryReady,
                                        onClick = {
                                            if (multiSelect) {
                                                actions.onMessageClick(message.id)
                                            } else {
                                                preview = attachment
                                            }
                                        },
                                        onLongClick = { if (!multiSelect) menuOpen = true },
                                    )
                                }
                            }
                        }
                        if (recording != null) {
                            AudioAttachmentRow(
                                attachment = recording,
                                playing = audio.playingKey == recording.id,
                                onToggle = {
                                    if (multiSelect) {
                                        actions.onMessageClick(message.id)
                                    } else {
                                        audio.toggle(recording.id, actions.attachmentUri(recording.relativePath))
                                    }
                                },
                            )
                        }
                    }
                }
                if (menuOpen) {
                    MessageActionSheet(
                        message = message,
                        reactions = reactions,
                        actions = actions,
                        onDismiss = { menuOpen = false },
                        onCopy = onCopy,
                    )
                }
            }
            if (revealed) {
                val dateTime = Instant.ofEpochMilli(message.createdAt).atZone(zone)
                val label = if (dateTime.toLocalDate() == today) {
                    dateTime.format(clockFormat)
                } else {
                    dateTime.format(dateTimeFormat)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                        .testTag("time_${message.id}"),
                )
            }
        }
    }
    preview?.let { attachment ->
        AttachmentPreviewDialog(
            attachment = attachment,
            uri = actions.attachmentUri(attachment.relativePath),
            onDismiss = { preview = null },
            actions = actions,
        )
    }
}

@Composable
private fun QuotePreview(quote: QuoteRecord) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(bottom = 2.dp).widthIn(max = 320.dp),
    ) {
        Text(
            text = if (quote.preview.isEmpty()) {
                stringResource(R.string.original_message_deleted)
            } else {
                quote.preview
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp).testTag("quote_${quote.messageId}"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentThumbnail(
    attachment: MessageAttachment,
    uri: Uri?,
    directoryReady: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val image by produceState(AttachmentImageState(), uri, directoryReady) {
        value = AttachmentImageState(
            bitmap = uri?.let {
                withContext(Dispatchers.IO) { decodeSampledBitmap(context.contentResolver, it, 360) }
            },
            loaded = true,
        )
    }
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("message_image_${attachment.id}"),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image.bitmap
        when {
            loaded != null -> Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            image.loaded -> Text(
                text = stringResource(R.string.attachment_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

private data class AttachmentImageState(val bitmap: Bitmap? = null, val loaded: Boolean = false)

@Composable
private fun AttachmentPreviewDialog(
    attachment: MessageAttachment,
    uri: Uri?,
    onDismiss: () -> Unit,
    actions: ChatActions = ChatActions(),
) {
    val context = LocalContext.current
    val image by produceState(AttachmentImageState(), uri) {
        value = AttachmentImageState(
            bitmap = uri?.let {
                withContext(Dispatchers.IO) { decodeSampledBitmap(context.contentResolver, it, 1600) }
            },
            loaded = true,
        )
    }
    val canAct = image.bitmap != null && uri != null
    var pendingSave by remember { mutableStateOf(false) }

    fun save() {
        val target = uri ?: return
        actions.saveImage(attachment, target)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) {
            save()
        } else if (!granted) {
            actions.onShowNotice(context.getString(R.string.save_to_gallery_permission_denied))
        }
        pendingSave = false
    }

    fun requestSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            save()
        } else {
            pendingSave = true
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.image_preview),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("preview_close")) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 480.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val loaded = image.bitmap
                    when {
                        loaded != null -> Image(
                            bitmap = loaded.asImageBitmap(),
                            contentDescription = attachment.relativePath,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                                .testTag("attachment_preview"),
                        )
                        image.loaded -> Text(
                            text = stringResource(R.string.attachment_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("attachment_unavailable"),
                        )
                        else -> CircularProgressIndicator()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { requestSave() },
                        enabled = canAct,
                        modifier = Modifier.size(48.dp).testTag("preview_save"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_save_to_gallery),
                            contentDescription = stringResource(R.string.preview_save),
                        )
                    }
                    IconButton(
                        onClick = { uri?.let { actions.shareImage(attachment, it) } },
                        enabled = canAct,
                        modifier = Modifier.size(48.dp).testTag("preview_share"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.preview_share),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioAttachmentRow(
    attachment: MessageAttachment,
    playing: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("message_audio_${attachment.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.testTag("play_audio_${attachment.id}"),
            ) {
                Text(if (playing) "■" else "▶")
            }
            Text(
                text = formatDuration(attachment.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun DraftAttachmentStrip(attachments: List<MessageAttachment>, actions: ChatActions) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth().testTag("draft_attachments"),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            DraftAttachmentItem(attachment, actions)
        }
    }
}

@Composable
private fun DraftAttachmentItem(attachment: MessageAttachment, actions: ChatActions) {
    val context = LocalContext.current
    val uri = actions.attachmentUri(attachment.relativePath)
    Box(modifier = Modifier.testTag("draft_attachment_${attachment.id}")) {
        if (attachment.kind == "image") {
            val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
                value = uri?.let {
                    withContext(Dispatchers.IO) { decodeSampledBitmap(context.contentResolver, it, 200) }
                }
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                val loaded = bitmap
                if (loaded != null) {
                    Image(
                        bitmap = loaded.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.image),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(72.dp).padding(horizontal = 12.dp),
                ) {
                    Text("▶", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = formatDuration(attachment.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .clickable { actions.onRemoveDraftAttachment(attachment) }
                .testTag("remove_draft_${attachment.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RecordingStrip(elapsedMs: Long, onStop: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("recording_strip"),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFD32F2F))
                .testTag("recording_dot"),
        )
        Text(
            text = stringResource(R.string.recording),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).testTag("recording_time"),
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onStop, modifier = Modifier.testTag("stop_recording")) {
            Text(stringResource(R.string.stop_recording))
        }
    }
}

private class AudioController(private val context: android.content.Context) {
    private var player: MediaPlayer? = null
    var playingKey by mutableStateOf<String?>(null)
        private set

    fun toggle(key: String, uri: Uri?) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        if (uri == null) return
        val next = MediaPlayer()
        try {
            next.setDataSource(context, uri)
            next.setOnCompletionListener { stop() }
            next.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            next.prepare()
            next.start()
            player = next
            playingKey = key
        } catch (_: Exception) {
            runCatching { next.release() }
            player = null
            playingKey = null
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        playingKey = null
    }

    fun release() = stop()
}

@Composable
private fun rememberAudioController(): AudioController {
    val context = LocalContext.current
    val controller = remember { AudioController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun buildSegmentsText(segments: List<MessageFormatSegment>): AnnotatedString =
    buildAnnotatedString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            val format = BubbleFormat.fromId(segment.formatId)
            withStyle(
                SpanStyle(
                    color = formatColor(format),
                    fontWeight = if (format != null && BubbleTextStyle.Bold in format.styles) {
                        FontWeight.Bold
                    } else {
                        null
                    },
                    fontStyle = if (format != null && BubbleTextStyle.Italic in format.styles) {
                        FontStyle.Italic
                    } else {
                        null
                    },
                    textDecoration = if (format != null && BubbleTextStyle.StrikeThrough in format.styles) {
                        TextDecoration.LineThrough
                    } else {
                        null
                    },
                ),
            ) {
                append(segment.text)
            }
        }
    }

@Composable
private fun formatTextStyle(
    format: BubbleFormat?,
    base: TextStyle = MaterialTheme.typography.bodyLarge,
): TextStyle = base.copy(
    fontWeight = if (format != null && BubbleTextStyle.Bold in format.styles) {
        FontWeight.Bold
    } else {
        base.fontWeight
    },
    fontStyle = if (format != null && BubbleTextStyle.Italic in format.styles) {
        FontStyle.Italic
    } else {
        base.fontStyle
    },
    textDecoration = if (format != null && BubbleTextStyle.StrikeThrough in format.styles) {
        TextDecoration.LineThrough
    } else {
        base.textDecoration
    },
)

@Composable
private fun FormatOption(
    format: BubbleFormat?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Text(
            text = "Aa",
            style = formatTextStyle(format, MaterialTheme.typography.titleLarge),
            color = formatColor(format),
        )
    }
}

@Composable
private fun formatColor(format: BubbleFormat?): Color = when (format?.color) {
    BubbleColor.Red -> Color(0xFFC62828)
    BubbleColor.Yellow -> Color(0xFFB26A00)
    BubbleColor.Green -> Color(0xFF2E7D32)
    null -> Color.Unspecified
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: MessageRecord,
    reactions: List<ReactionRecord>,
    actions: ChatActions,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val applied = reactions.firstNotNullOfOrNull { BubbleFormat.fromId(it.emoji) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                val apply = { next: BubbleFormat? ->
                    actions.onApplyBubbleFormat(message.id, next?.id)
                }
                items(BubbleTextStyle.entries, key = { it.id }) { style ->
                    FormatOption(
                        format = BubbleFormat(styles = setOf(style)),
                        selected = applied?.styles?.contains(style) == true,
                        tag = "format_option_${style.id}",
                    ) { apply((applied ?: BubbleFormat()).toggleStyle(style)) }
                }
                item { Spacer(modifier = Modifier.width(12.dp)) }
                items(BubbleColor.entries, key = { it.id }) { color ->
                    FormatOption(
                        format = BubbleFormat(color = color),
                        selected = applied?.color == color,
                        tag = "format_option_${color.id}",
                    ) { apply((applied ?: BubbleFormat()).toggleColor(color)) }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                MessageActionCard("↩", stringResource(R.string.quote), "menu_quote", Modifier.weight(1f)) {
                    onDismiss()
                    actions.onRequestQuote(message)
                }
                MessageActionCard("⧉", stringResource(R.string.copy), "menu_copy", Modifier.weight(1f)) {
                    onDismiss()
                    onCopy(message.currentContent)
                }
                if (message.role == "user") {
                    MessageActionCard("✎", stringResource(R.string.edit), "menu_edit", Modifier.weight(1f)) {
                        onDismiss()
                        actions.onRequestEdit(message)
                    }
                }
                MessageActionCard("☑", stringResource(R.string.multi_select), "menu_multi", Modifier.weight(1f)) {
                    onDismiss()
                    actions.onStartMultiSelect(message)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column {
                    SheetRow(
                        label = stringResource(R.string.set_reminder),
                        tag = "menu_reminder",
                    ) {
                        onDismiss()
                        actions.onRequestMessageReminder(message)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SheetRow(
                        label = stringResource(R.string.show_history),
                        enabled = message.contentVersion > 1,
                        tag = "menu_history",
                    ) {
                        onDismiss()
                        actions.onShowHistory(message)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SheetRow(
                        label = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                        tag = "menu_delete",
                    ) {
                        onDismiss()
                        actions.onRequestDeleteMessages(setOf(message.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionCard(
    symbol: String,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.testTag(tag).clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            Text(symbol, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    tag: String,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = color.copy(alpha = if (enabled) 1f else 0.38f),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationResultsScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onRegenerate: () -> Unit,
    onExport: (DocumentExportFormat) -> Unit,
    actions: ChatActions = ChatActions(),
) {
    val sessionId = state.resultSessionId ?: return
    val title = state.sessions.firstOrNull { it.id == sessionId }?.title ?: "整理结果"
    val document = state.resultDocument
    val organizing = sessionId in state.organizingSessions
    val error = state.resultError ?: state.organizationErrors[sessionId]
    var preview by remember { mutableStateOf<MessageAttachment?>(null) }
    val blocks = remember(document, state.resultMessages) {
        document?.let { SessionDocumentBlocks.build(it, state.resultMessages) }.orEmpty()
    }
    val imageCache = remember { mutableMapOf<String, Bitmap>() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$title · 整理结果", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(
                        onClick = onRegenerate,
                        enabled = !organizing,
                        modifier = Modifier.testTag("regenerate_organization"),
                    ) {
                        Text(if (organizing) "整理中…" else "重新整理")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).testTag("organization_results")) {
            if (document == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (organizing) "正在整理…" else error ?: "尚未生成整理文档",
                        color = if (!organizing && error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp).testTag("organization_empty"),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "生成于 " +
                            documentTimeFormat.format(Instant.ofEpochMilli(document.generatedAt).atZone(ZoneId.systemDefault())) +
                            " · 共 ${document.messageCount} 条消息" +
                            if (document.modelSnapshot.isNotBlank()) " · ${document.modelSnapshot}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (document.isMock) {
                        Text(
                            text = "模拟结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (organizing) {
                        Text(
                            text = "正在整理…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).testTag("organization_running"),
                        )
                    }
                    if (error != null) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp).testTag("organization_error"),
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(blocks) { index, block ->
                        when (block) {
                            is DocumentBlock.Text -> Text(
                                text = markdownAnnotatedString(block.markdown),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    .then(if (index == 0) Modifier.testTag("organization_document") else Modifier),
                            )
                            is DocumentBlock.Images -> Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                block.attachments.forEach { attachment ->
                                    DocumentImage(
                                        attachment = attachment,
                                        uri = actions.attachmentUri(attachment.relativePath),
                                        cache = imageCache,
                                        onClick = { preview = attachment },
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DocumentExportFormat.values().forEach { format ->
                        TextButton(
                            onClick = { onExport(format) },
                            enabled = !organizing,
                            modifier = Modifier.testTag("export_${format.extension}"),
                        ) {
                            Text(format.label)
                        }
                    }
                }
            }
        }
    }
    preview?.let { attachment ->
        AttachmentPreviewDialog(
            attachment = attachment,
            uri = actions.attachmentUri(attachment.relativePath),
            onDismiss = { preview = null },
            actions = actions,
        )
    }
}

@Composable
private fun DocumentImage(
    attachment: MessageAttachment,
    uri: Uri?,
    cache: MutableMap<String, Bitmap>,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val image by produceState(AttachmentImageState(), uri) {
        val cached = cache[attachment.relativePath]
        if (cached != null) {
            value = AttachmentImageState(cached, true)
            return@produceState
        }
        val bitmap = uri?.let {
            withContext(Dispatchers.IO) { decodeSampledBitmap(context.contentResolver, it, 1600) }
        }
        if (bitmap != null) cache[attachment.relativePath] = bitmap
        value = AttachmentImageState(bitmap, true)
    }
    Box(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 80.dp, max = 320.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .clickable(enabled = image.bitmap != null, onClick = onClick)
            .testTag("document_image_${attachment.id}"),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image.bitmap
        when {
            loaded != null -> Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = attachment.relativePath,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            )
            image.loaded -> Text(
                text = stringResource(R.string.attachment_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, actions: ChatActions, compact: Boolean) {
    val context = LocalContext.current
    val inputFocusRequester = rememberEditInputFocus(state.editing?.id)
    var inputValue by remember {
        mutableStateOf(TextFieldValue(state.input, TextRange(state.input.length)))
    }
    // 进入编辑时把光标放到正文末尾，避免落在开头。
    LaunchedEffect(state.editing?.id) {
        if (state.editing != null) {
            inputValue = TextFieldValue(state.input, TextRange(state.input.length))
        }
    }
    LaunchedEffect(state.input) {
        if (state.input != inputValue.text) {
            inputValue = TextFieldValue(state.input, TextRange(state.input.length))
        }
    }

    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    fun startRecording() {
        recorder.start()
            .onSuccess {
                recording = true
                elapsedMs = 0L
            }
            .onFailure { actions.onShowNotice(it.message ?: context.getString(R.string.record_failed)) }
    }

    fun stopRecording(auto: Boolean = false) {
        if (!recording) return
        recording = false
        elapsedMs = 0L
        recorder.stop()
            .onSuccess { clip ->
                if (clip == null) {
                    actions.onShowNotice(context.getString(R.string.record_too_short))
                } else {
                    if (auto) actions.onShowNotice(context.getString(R.string.record_auto_stopped))
                    actions.onVoiceRecorded(clip.file, clip.durationMs)
                }
            }
            .onFailure { actions.onShowNotice(it.message ?: context.getString(R.string.record_failed)) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            actions.onShowNotice(context.getString(R.string.record_permission_denied))
        }
    }

    fun requestOrStartRecording() {
        when {
            recording -> stopRecording()
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_IMAGES),
    ) { uris -> if (uris.isNotEmpty()) actions.onImagesPicked(uris) }

    DisposableEffect(recorder) {
        onDispose { recorder.cancel() }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recording) {
            delay(200)
            elapsedMs = recorder.elapsedMs()
            if (elapsedMs >= VoiceRecorder.MAX_DURATION_MS) {
                stopRecording(auto = true)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp),
    ) {
        if (state.editing != null) {
            StatusStrip(
                text = stringResource(R.string.editing_hint),
                onCancel = actions.onCancelEdit,
                testTag = "cancel_edit",
            )
        }
        if (state.quoteTarget != null) {
            StatusStrip(
                text = stringResource(R.string.quoting_hint),
                onCancel = actions.onCancelQuote,
                testTag = "cancel_quote",
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("composer"),
            color = if (isSystemInDarkTheme()) Color(0xFF26272A) else Color(0xFFF0F2F2),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = if (compact) 4.dp else 14.dp,
                    bottom = if (compact) 2.dp else 8.dp,
                ),
            ) {
                if (recording) {
                    RecordingStrip(elapsedMs = elapsedMs, onStop = { stopRecording() })
                } else if (state.draftAttachments.isNotEmpty()) {
                    DraftAttachmentStrip(state.draftAttachments, actions)
                }
                BasicTextField(
                    value = inputValue,
                    onValueChange = {
                        inputValue = it
                        actions.onInputChange(it.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = if (compact) 24.dp else 36.dp)
                        .padding(horizontal = 12.dp)
                        .focusRequester(inputFocusRequester)
                        .testTag("chat_input"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = compact,
                    maxLines = if (compact) 1 else 4,
                    decorationBox = { innerTextField ->
                        Box {
                            if (inputValue.text.isEmpty()) {
                                Text(
                                    stringResource(R.string.input_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = !state.busy && state.editing == null && !recording,
                            modifier = Modifier.testTag("image_button"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_attach),
                                contentDescription = stringResource(R.string.image),
                            )
                        }
                        if (!recording && (state.draftAttachments.isNotEmpty() || state.input.isNotBlank())) {
                            IconButton(
                                onClick = { requestOrStartRecording() },
                                enabled = !state.busy && state.editing == null,
                                modifier = Modifier.testTag("voice_attach_button"),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_waveform),
                                    contentDescription = stringResource(R.string.voice),
                                )
                            }
                        }
                    }
                    val hasContent = state.input.isNotBlank() || state.draftAttachments.isNotEmpty()
                    if (state.editing != null) {
                        Button(
                            onClick = actions.onSend,
                            enabled = state.input.isNotBlank() && !state.busy,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("send_button"),
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    } else if (hasContent) {
                        FilledIconButton(
                            onClick = actions.onSend,
                            enabled = !state.busy && state.directoryReady && !recording,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.testTag("send_button"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_send_up),
                                contentDescription = stringResource(R.string.send),
                            )
                        }
                    } else {
                        Button(
                            onClick = { requestOrStartRecording() },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("voice_button"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_waveform),
                                contentDescription = null,
                            )
                            Text(
                                text = if (recording) {
                                    stringResource(R.string.stop_recording)
                                } else {
                                    stringResource(R.string.voice)
                                },
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(text: String, onCancel: () -> Unit, testTag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel, modifier = Modifier.testTag(testTag)) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatDrawer(state: ChatUiState, actions: ChatActions, onCloseDrawer: () -> Unit) {
    var menuSessionId by remember { mutableStateOf<String?>(null) }
    // 长按落点（相对当前行），让弹出的菜单出现在手指附近。
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                IconButton(
                    onClick = {
                        onCloseDrawer()
                        actions.onOpenSettings()
                    },
                    modifier = Modifier.testTag("settings_entry"),
                ) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = {
                    onCloseDrawer()
                    actions.onNewSession()
                },
                enabled = state.directoryReady,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("new_chat"),
            ) {
                Text(stringResource(R.string.new_chat))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.history),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                val byCreated = state.sessionSortOrder == SessionSortOrder.Created
                val context = LocalContext.current
                FilledTonalIconButton(
                    onClick = {
                        actions.onToggleSessionSort()
                        Toast.makeText(
                            context,
                            if (byCreated) "已按修改时间排序" else "已按创建时间排序",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.testTag("session_sort"),
                ) {
                    Icon(
                        painter = painterResource(
                            if (byCreated) R.drawable.ic_sort_created else R.drawable.ic_sort_modified,
                        ),
                        contentDescription = if (byCreated) {
                            "当前按创建时间排序，点击切换为按修改时间"
                        } else {
                            "当前按修改时间排序，点击切换为按创建时间"
                        },
                    )
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.sessions.isEmpty()) {
                    item {
                        Text(
                            text = "暂无对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                items(state.sessions, key = { it.id }) { session ->
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (session.id == state.activeSessionId) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .pointerInput(session.id) {
                                    // 只记录按下位置，不消费事件，保持点击与长按原有行为。
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        menuAnchor = down.position
                                        do {
                                            val event = awaitPointerEvent()
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                                .combinedClickable(
                                    onClick = {
                                        onCloseDrawer()
                                        actions.onSelectSession(session.id)
                                    },
                                    onLongClick = { menuSessionId = session.id },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .testTag("session_${session.id}"),
                        ) {
                            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (session.id in state.unreadOrganizationSessions) {
                                Text(
                                    "●",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 12.dp)
                                        .testTag("organization_unread_${session.id}")
                                        .clickable {
                                            onCloseDrawer()
                                            actions.onOpenOrganizationResults(session.id)
                                        },
                                )
                            }
                        }
                        // 以落点为锚：零尺寸容器定位到手指处，菜单从该点向右下展开。
                        Box(
                            modifier = Modifier.offset {
                                IntOffset(menuAnchor.x.roundToInt(), menuAnchor.y.roundToInt())
                            },
                        ) {
                            DropdownMenu(
                                expanded = menuSessionId == session.id,
                                onDismissRequest = { menuSessionId = null },
                                offset = DpOffset(8.dp, 8.dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = {
                                        menuSessionId = null
                                        actions.onRequestRename(session)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        menuSessionId = null
                                        actions.onRequestDelete(session)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export)) },
                                    onClick = {
                                        menuSessionId = null
                                        onCloseDrawer()
                                        actions.onOpenOrganizationResults(session.id)
                                    },
                                    modifier = Modifier.testTag("export_session_${session.id}"),
                                )
                            }
                        }
                    }
                }
                if (state.sessions.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.empty_history),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 主界面闹钟入口：逐行展示全部待触发提醒，支持快捷取消与跳转。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderListSheet(state: ChatUiState, actions: ChatActions) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    ModalBottomSheet(
        onDismissRequest = actions.onCloseReminderList,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.reminder_list_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.scheduledReminders.isEmpty()) {
                Text(
                    stringResource(R.string.reminder_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                state.scheduledReminders.forEach { preview ->
                    ReminderPreviewRow(preview, zone, timeFormatter, actions)
                }
            }
        }
    }
}

@Composable
private fun ReminderPreviewRow(
    preview: ReminderPreview,
    zone: ZoneId,
    formatter: DateTimeFormatter,
    actions: ChatActions,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { actions.onOpenReminder(preview.id) }
            .testTag("reminder_preview_${preview.id}")
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Instant.ofEpochMilli(preview.triggerAt).atZone(zone).format(formatter),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                preview.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = { actions.onCancelReminder(preview.id) },
            modifier = Modifier.testTag("reminder_preview_cancel_${preview.id}"),
        ) {
            Text(stringResource(R.string.reminder_cancel))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDialog(
    target: ReminderTarget,
    reminders: List<ReminderRecord>,
    actions: ChatActions,
) {
    val zone = remember { ZoneId.systemDefault() }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateTimeFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val now = remember { LocalDateTime.now() }
    val nowMillis = remember { System.currentTimeMillis() }
    val initialTime = remember {
        defaultReminderInstant(nowMillis, target.suggestedAt, zone)
            .atZone(zone)
            .toLocalDateTime()
    }
    var date by remember { mutableStateOf(initialTime.toLocalDate()) }
    var time by remember {
        mutableStateOf(initialTime.toLocalTime().withSecond(0).withNano(0))
    }
    var preset by remember { mutableStateOf<String?>(null) }
    var choosingDate by remember { mutableStateOf(false) }
    var choosingTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pastTimeError = stringResource(R.string.reminder_past_time)
    val pending = reminders.filter {
        it.status == REMINDER_SCHEDULED && it.triggerAt > nowMillis
    }
    val triggerAt = remember(date, time) {
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    }
    val applyPreset: (String) -> Unit = { key ->
        val targetTime = when (key) {
            "hour" -> now.plusHours(1)
            "tomorrow" -> now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0))
            else -> now.toLocalDate().plusDays(2).atTime(LocalTime.of(9, 0))
        }.withSecond(0).withNano(0)
        preset = key
        date = targetTime.toLocalDate()
        time = targetTime.toLocalTime()
        error = null
    }
    ModalBottomSheet(
        onDismissRequest = actions.onDismissReminder,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.reminder_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                target.messagePreview?.let {
                    stringResource(R.string.reminder_target_message, it)
                } ?: target.sessionTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ReminderPresetRow(
                        label = stringResource(R.string.reminder_preset_hour),
                        selected = preset == "hour",
                        tag = "reminder_preset_hour",
                    ) { applyPreset("hour") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ReminderPresetRow(
                        label = stringResource(R.string.reminder_preset_tomorrow),
                        selected = preset == "tomorrow",
                        tag = "reminder_preset_tomorrow",
                    ) { applyPreset("tomorrow") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ReminderPresetRow(
                        label = stringResource(R.string.reminder_preset_day_after),
                        selected = preset == "dayAfter",
                        tag = "reminder_preset_day_after",
                    ) { applyPreset("dayAfter") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { choosingDate = true },
                    modifier = Modifier.weight(1f),
                ) { Text(date.format(dateFormatter)) }
                OutlinedButton(
                    onClick = { choosingTime = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reminder_time_button"),
                ) { Text(time.format(timeFormatter)) }
            }
            Text(
                stringResource(
                    R.string.reminder_scheduled_at,
                    LocalDateTime.of(date, time).format(dateTimeFormatter),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.reminder_background_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pending.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(R.string.reminder_pending_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    pending.forEach { reminder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                Instant.ofEpochMilli(reminder.triggerAt)
                                    .atZone(zone)
                                    .format(dateTimeFormatter),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { actions.onCancelReminder(reminder.id) }) {
                                Text("取消提醒")
                            }
                        }
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = actions.onDismissReminder) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        if (triggerAt <= System.currentTimeMillis()) {
                            error = pastTimeError
                        } else {
                            actions.onConfirmReminder(triggerAt)
                        }
                    },
                    modifier = Modifier.testTag("reminder_confirm"),
                ) { Text(stringResource(R.string.reminder_confirm)) }
            }
        }
    }
    if (choosingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        preset = null
                    }
                    choosingDate = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { choosingDate = false }) { Text("取消") }
            },
        ) { DatePicker(state = pickerState) }
    }
    if (choosingTime) {
        ReminderTimeWheelDialog(
            initial = time,
            onDismiss = { choosingTime = false },
            onConfirm = {
                time = it
                preset = null
                choosingTime = false
            },
        )
    }
}

private val ReminderWheelItemHeight = 40.dp
private const val ReminderWheelVisibleItems = 5

/** 时、分用上下滚轮选择；圆盘（表盘）在小屏上拖动不便。 */
@Composable
private fun ReminderTimeWheelDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ReminderWheelColumn(
                    count = 24,
                    initialIndex = initial.hour,
                    tag = "reminder_hour_wheel",
                    onSelected = { hour = it },
                    modifier = Modifier.weight(1f),
                )
                Text(":", style = MaterialTheme.typography.headlineSmall)
                ReminderWheelColumn(
                    count = 60,
                    initialIndex = initial.minute,
                    tag = "reminder_minute_wheel",
                    onSelected = { minute = it },
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(hour, minute)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 单列滚轮：停在中间的一行即选中值，滑动结束后吸附到整行；虚拟长列表实现首尾循环。 */
@Composable
private fun ReminderWheelColumn(
    count: Int,
    initialIndex: Int,
    tag: String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemCount = remember(count) { reminderWheelItemCount(count) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember(count, initialIndex) {
            reminderWheelInitialIndex(count, initialIndex)
        },
    )
    val wheelHeight = ReminderWheelItemHeight * ReminderWheelVisibleItems
    val selected by remember(count, itemCount) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2f - center) }
                ?.index
                ?.coerceIn(0, itemCount - 1)
                ?: reminderWheelInitialIndex(count, initialIndex)
        }
    }
    val selectedValue = reminderWheelValue(selected, count)
    LaunchedEffect(selectedValue) { onSelected(selectedValue) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(wheelHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReminderWheelItemHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                vertical = (wheelHeight - ReminderWheelItemHeight) / 2,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            modifier = Modifier
                .fillMaxSize()
                .testTag(tag),
        ) {
            items(itemCount) { index ->
                val value = reminderWheelValue(index, count)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(ReminderWheelItemHeight),
                ) {
                    Text(
                        value.toString().padStart(2, '0'),
                        style = if (index == selected) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (index == selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderPresetRow(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                "✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun RenameDialog(session: SessionRecord, actions: ChatActions) {
    var title by remember(session.id) { mutableStateOf(session.title) }
    AlertDialog(
        onDismissRequest = actions.onCancelRename,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            val focusRequester = rememberEditInputFocus(session.id, focusBeforeWindow = true)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focusRequester).testTag("rename_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onConfirmRename(title) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelRename) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DeleteSessionDialog(session: SessionRecord, actions: ChatActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelDelete,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_message, session.title)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmDelete) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelDelete) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DeleteMessagesDialog(ids: Set<String>, actions: ChatActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelDeleteMessages,
        title = {
            Text(
                if (ids.size > 1) {
                    stringResource(R.string.delete_messages_title, ids.size)
                } else {
                    stringResource(R.string.delete_message_title)
                },
            )
        },
        text = { Text(stringResource(R.string.delete_message_hint)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmDeleteMessages, modifier = Modifier.testTag("confirm_delete")) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelDeleteMessages) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun MergeMessagesDialog(state: ChatUiState, actions: ChatActions) {
    val ids = state.pendingMergeMessages ?: return
    val selected = state.messages.filter { it.id in ids }
        .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })
    val previewSegments = buildMergedSegments(selected) { id ->
        state.reactions[id].orEmpty().firstNotNullOfOrNull { BubbleFormat.fromId(it.emoji) }
    }
    AlertDialog(
        onDismissRequest = actions.onCancelMergeMessages,
        title = { Text("合并 ${selected.size} 条消息") },
        text = {
            Column(modifier = Modifier.heightIn(max = 300.dp)) {
                Text("所选消息将替换为一条合并消息，原消息保留在记录文件中。",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    item {
                        Text(buildSegmentsText(previewSegments), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmMergeMessages,
                enabled = selected.size >= 2 && !state.busy,
                modifier = Modifier.testTag("confirm_merge_messages"),
            ) {
                Text("合并")
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelMergeMessages) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun HistoryDialog(history: List<EditRecord>, actions: ChatActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissHistory,
        title = { Text(stringResource(R.string.history_title)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.no_edit_history))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    item {
                        Text(
                            stringResource(R.string.original_content, history.first().oldContent),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(history, key = { it.toVersion }) { edit ->
                        Text(
                            text = stringResource(R.string.edit_version, edit.toVersion, edit.newContent),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismissHistory) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
