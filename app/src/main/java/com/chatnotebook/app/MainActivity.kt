package com.chatnotebook.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatnotebook.app.compliance.PrivacyConsent
import com.chatnotebook.app.data.DocumentBlock
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.PersistentChatRepository
import com.chatnotebook.app.data.SessionDocumentBlocks
import com.chatnotebook.app.reminder.ReminderNotifications
import com.chatnotebook.app.reminder.ReminderScheduler
import com.chatnotebook.app.ui.AppBackHandler
import com.chatnotebook.app.ui.ChatActions
import com.chatnotebook.app.ui.ChatScreen
import com.chatnotebook.app.ui.ChatViewModel
import com.chatnotebook.app.ui.DocumentExportFormat
import com.chatnotebook.app.ui.DocumentExporter
import com.chatnotebook.app.ui.GalleryImageWriter
import com.chatnotebook.app.ui.ImageSharing
import com.chatnotebook.app.ui.MessageSharing
import com.chatnotebook.app.ui.PrivacyConsentScreen
import com.chatnotebook.app.ui.ReminderPermission
import com.chatnotebook.app.ui.SettingsRoute
import com.chatnotebook.app.ui.OrganizationResultsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repository by lazy { PersistentChatRepository.shared(this) }

    /** 通知点击带来的跳转目标，Compose 侧消费后置空。 */
    private val notificationRoute = MutableStateFlow<ReminderNotifications.Route?>(null)

    private fun handleIntent(intent: Intent?) {
        val route = ReminderNotifications.consumeRoute(intent) ?: return
        notificationRoute.value = route
        showOverLockScreen()
    }

    /** 从提醒进入时允许越过锁屏展示对话，确保点亮的屏幕直接显示提醒。 */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    /** 跳转完成后撤销锁屏可见，避免应用之后长期停留在锁屏之上。 */
    private fun clearLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    /** 删除导出失败留下的零字节文件；已有内容的文件保持不变。 */
    private fun discardEmptyDocument(uri: Uri) {
        val empty = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length == 0L } ?: false
        }.getOrDefault(false)
        if (empty) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    override fun onStart() {
        super.onStart()
        if (PrivacyConsent.isAccepted(this)) {
            repository.lanSync.foreground(true)
            lifecycleScope.launch { runCatching { repository.rescheduleReminders() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        repository.lanSync.foreground(false)
        super.onStop()
    }

    /** 应用固定浅色外观，状态栏改用深色内容，避免白色图标在浅色背景上看不清。 */
    private fun applyLightStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        // Android 15 起强制全屏边到边，状态栏透明并由应用背景填充，无需再设置颜色。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val value = TypedValue()
            val background = if (
                theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
                value.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                value.type <= TypedValue.TYPE_LAST_COLOR_INT
            ) {
                value.data
            } else {
                Color.WHITE
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = background
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLightStatusBar()
        handleIntent(intent)
        setContent {
            MaterialTheme {
                var consented by remember {
                    mutableStateOf(PrivacyConsent.isAccepted(this@MainActivity))
                }
                if (!consented) {
                    PrivacyConsentScreen(
                        onAgree = {
                            PrivacyConsent.accept(this@MainActivity)
                            consented = true
                            repository.lanSync.foreground(true)
                        },
                        onExit = { finish() },
                    )
                } else {
                val model: ChatViewModel = viewModel(
                    factory = ChatViewModel.factory(repository),
                )
                val state by model.uiState.collectAsState()
                val incomingRoute by notificationRoute.collectAsState()
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> model.notifyPermissionResult(granted) }
                val exactAlarmLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    if (ReminderScheduler(this@MainActivity).canScheduleExact()) {
                        model.rescheduleReminders()
                    } else {
                        model.showNotice(getString(R.string.reminder_exact_denied))
                    }
                }
                LaunchedEffect(incomingRoute) {
                    val route = incomingRoute
                    if (route != null) {
                        model.openSessionFromNotification(route.sessionId, route.messageId)
                        notificationRoute.value = null
                        clearLockScreenFlags()
                    }
                }
                LaunchedEffect(state.reminderPermission) {
                    when (state.reminderPermission) {
                        ReminderPermission.Notifications -> {
                            model.consumeReminderPermission()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                model.showNotice(getString(R.string.reminder_notification_denied))
                            }
                        }
                        ReminderPermission.ExactAlarm -> {
                            model.consumeReminderPermission()
                            runCatching {
                                exactAlarmLauncher.launch(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }.onFailure {
                                model.showNotice(getString(R.string.reminder_exact_denied))
                            }
                        }
                        null -> Unit
                    }
                }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri -> uri?.let { model.selectDirectory(it.toString()) } }
                val scope = rememberCoroutineScope()
                val saveImage: (MessageAttachment, Uri) -> Unit = { attachment, uri ->
                    scope.launch {
                        runCatching { GalleryImageWriter(applicationContext).save(attachment, uri) }
                            .onSuccess { showToast(R.string.save_to_gallery_done) }
                            .onFailure { showToast(R.string.save_to_gallery_failed) }
                    }
                }
                val shareImage: (MessageAttachment, Uri) -> Unit = { attachment, _ ->
                    scope.launch {
                        runCatching { ImageSharing.prepare(applicationContext, attachment, model::attachmentUri) }
                            .onSuccess { send ->
                                runCatching { startActivity(Intent.createChooser(send, getString(R.string.preview_share))) }
                                    .onFailure { showToast(R.string.share_image_failed) }
                            }
                            .onFailure { showToast(R.string.share_image_failed) }
                    }
                }
                var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
                val exportPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val request = pendingExport
                    pendingExport = null
                    val uri = result.data?.data
                    if (result.resultCode != Activity.RESULT_OK || uri == null || request == null) {
                        return@rememberLauncherForActivityResult
                    }
                    scope.launch {
                        DocumentExporter.export(
                            applicationContext,
                            uri,
                            request.format,
                            request.content,
                            request.blocks,
                            model::attachmentUri,
                        )
                            .onSuccess { model.showNotice("已保存到所选位置") }
                            .onFailure {
                                discardEmptyDocument(uri)
                                model.showNotice(it.message ?: "导出失败，请重试")
                            }
                    }
                }
                AppBackHandler(
                    settingsOpen = state.settingsOpen,
                    resultOpen = state.resultSessionId != null,
                    onCloseSettings = model::closeSettings,
                    onCloseResults = model::closeOrganizationResults,
                )
                if (state.settingsOpen) {
                    SettingsRoute(
                        repository = repository,
                        onBack = model::closeSettings,
                        onPickDirectory = { picker.launch(null) },
                    )
                } else if (state.resultSessionId != null) {
                    OrganizationResultsScreen(
                        state = state,
                        onBack = model::closeOrganizationResults,
                        onRegenerate = model::regenerateOrganization,
                        actions = ChatActions(
                            attachmentUri = model::attachmentUri,
                            saveImage = saveImage,
                            shareImage = shareImage,
                            onShowNotice = model::showNotice,
                        ),
                        onExport = { format ->
                            val document = state.resultDocument
                            if (document != null) {
                                val title = state.sessions
                                    .firstOrNull { it.id == state.resultSessionId }?.title ?: "整理结果"
                                pendingExport = PendingExport(
                                    format = format,
                                    content = document.content,
                                    blocks = SessionDocumentBlocks.build(document, state.resultMessages),
                                )
                                exportPicker.launch(
                                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = format.mimeType
                                        putExtra(
                                            Intent.EXTRA_TITLE,
                                            DocumentExporter.defaultFileName(title, format),
                                        )
                                    },
                                )
                            }
                        },
                    )
                } else {
                    ChatScreen(
                        state = state,
                        actions = ChatActions(
                            onPickDirectory = { picker.launch(null) },
                            onNewSession = model::createSession,
                            onSelectSession = model::selectSession,
                            onOpenSettings = model::openSettings,
                            onCloseSettings = model::closeSettings,
                            onRequestRename = model::requestRename,
                            onCancelRename = model::cancelRename,
                            onConfirmRename = model::confirmRename,
                            onRequestDelete = model::requestDelete,
                            onCancelDelete = model::cancelDelete,
                            onConfirmDelete = model::confirmDelete,
                            onMessageClick = model::onMessageClick,
                            onInputChange = model::updateInput,
                            onSend = model::send,
                            onImagesPicked = model::attachImages,
                            onRemoveDraftAttachment = model::removeDraftAttachment,
                            onVoiceRecorded = model::addVoiceRecording,
                            attachmentUri = model::attachmentUri,
                            saveImage = saveImage,
                            shareImage = shareImage,
                            onShowNotice = model::showNotice,
                            onRequestEdit = model::requestEdit,
                            onCancelEdit = model::cancelEdit,
                            onRequestQuote = model::requestQuote,
                            onCancelQuote = model::cancelQuote,
                            onApplyBubbleFormat = model::applyBubbleFormat,
                            onRequestDeleteMessages = model::requestDeleteMessages,
                            onCancelDeleteMessages = model::cancelDeleteMessages,
                            onConfirmDeleteMessages = model::confirmDeleteMessages,
                            onStartMultiSelect = model::startMultiSelect,
                            onCancelMultiSelect = model::cancelMultiSelect,
                            onShowHistory = model::showHistory,
                            onDismissHistory = model::dismissHistory,
                            onGoToQuotedMessage = model::goToMessage,
                            onToggleSessionSort = model::toggleSessionSort,
                            onRequestForwardMessages = model::requestForwardMessages,
                            onCancelForwardMessages = model::cancelForwardMessages,
                            onConfirmForwardMessages = model::confirmForwardMessages,
                            onShareForwardMessages = { target ->
                                model.shareForwardMessages { messages ->
                                    val send = MessageSharing.prepare(applicationContext, messages, attachmentUri = model::attachmentUri)
                                    try {
                                        startActivity(send.setComponent(target))
                                    } catch (error: ActivityNotFoundException) {
                                        throw IllegalStateException(getString(R.string.share_launch_failed), error)
                                    } catch (error: SecurityException) {
                                        throw IllegalStateException(getString(R.string.share_launch_failed), error)
                                    }
                                }
                            },
                            onOpenOrganizationResults = model::openOrganizationResults,
                            onRequestMergeMessages = model::requestMergeMessages,
                            onCancelMergeMessages = model::cancelMergeMessages,
                            onConfirmMergeMessages = model::confirmMergeMessages,
                            onRequestMessageReminder = model::requestMessageReminder,
                            onCancelReminder = model::cancelReminder,
                            onConfirmReminder = model::saveReminder,
                            onDismissReminder = model::dismissReminder,
                            onOpenSuggestedReminder = model::openSuggestedReminder,
                            onDismissReminderSuggestion = model::dismissReminderSuggestion,
                            onOpenReminderList = model::openReminderList,
                            onCloseReminderList = model::closeReminderList,
                            onOpenReminder = model::openReminder,
                            onDismissNotice = model::dismissNotice,
                        ),
                    )
                }
                }
            }
        }
    }
}

private data class PendingExport(
    val format: DocumentExportFormat,
    val content: String,
    val blocks: List<DocumentBlock> = emptyList(),
)

