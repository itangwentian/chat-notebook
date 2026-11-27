package com.chatnotebook.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextRange
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.ReminderPreview
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.SessionDocumentRecord
import com.chatnotebook.app.data.SessionRecord
import com.chatnotebook.app.reminder.LocalReminderSuggestion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = ChatUiState(
        directoryReady = true,
        sessions = listOf(SessionRecord("s1", "工作", 1, 1)),
        activeSessionId = "s1",
        activeTitle = "工作",
        messages = listOf(
            MessageRecord("m1", "s1", "user", "今天要买牛奶", createdAt = 1_700_000_000_000),
        ),
    )

    @Test
    fun chatStartsWithoutKeyboardFocusAndEnablesAttachmentEntries() {
        compose.setContent {
            MaterialTheme { ChatScreen(state, ChatActions()) }
        }

        compose.onNodeWithTag("chat_input").assertIsNotFocused()
        compose.onNodeWithTag("voice_button").assertIsEnabled()
        compose.onNodeWithTag("image_button").assertIsEnabled()
        compose.onNodeWithTag("send_button").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertIsDisplayed()
    }

    @Test
    fun renamingFocusesTitleAsSoonAsDialogOpens() {
        val renaming = mutableStateOf<SessionRecord?>(null)
        compose.setContent {
            MaterialTheme { ChatScreen(state.copy(renaming = renaming.value), ChatActions()) }
        }

        compose.runOnIdle { renaming.value = state.sessions.single() }

        compose.onNodeWithTag("rename_input").assertIsFocused()
    }

    @Test
    fun typingReplacesVoicePlaceholderWithSendButton() {
        val input = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(input = input.value),
                    ChatActions(onInputChange = { input.value = it }),
                )
            }
        }

        compose.onNodeWithTag("chat_input").performTextInput("记录一件事")
        compose.onNodeWithTag("voice_button").assertDoesNotExist()
        compose.onNodeWithTag("send_button").assertIsDisplayed()
        compose.onNodeWithTag("image_button").assertIsEnabled()
    }

    @Test
    fun draftAttachmentsRenderAndCanBeRemoved() {
        val image = MessageAttachment("img1", "image", "attachments/img1.jpg", "image/jpeg", 3, 0, 1)
        val audio = MessageAttachment("aud1", "audio", "attachments/aud1.m4a", "audio/mp4", 3, 5_000, 1)
        var removed: MessageAttachment? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(draftAttachments = listOf(image, audio)),
                    ChatActions(onRemoveDraftAttachment = { removed = it }),
                )
            }
        }

        compose.onNodeWithTag("draft_attachments").assertIsDisplayed()
        compose.onNodeWithTag("draft_attachment_img1").assertIsDisplayed()
        compose.onNodeWithTag("draft_attachment_aud1").assertIsDisplayed()
        compose.onNodeWithTag("voice_attach_button").assertIsDisplayed()
        compose.onNodeWithTag("send_button").assertIsEnabled()

        compose.onNodeWithTag("remove_draft_aud1").performClick()
        assertEquals(audio, removed)
    }

    @Test
    fun copyingMessageWithAttachmentsOnlyCopiesText() {
        val image = MessageAttachment("img1", "image", "attachments/img1.jpg", "image/jpeg", 3, 0, 1)
        val withImage = state.messages.single().copy(attachments = listOf(image))
        compose.setContent {
            MaterialTheme {
                ChatScreen(state.copy(messages = listOf(withImage)), ChatActions())
            }
        }

        compose.onNodeWithText("今天要买牛奶").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_copy").performClick()

        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("今天要买牛奶", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun multiSelectDisablesImagePreviewAndAudioPlayback() {
        val image = MessageAttachment("img1", "image", "attachments/img1.jpg", "image/jpeg", 3, 0, 1)
        val audio = MessageAttachment("aud1", "audio", "attachments/aud1.m4a", "audio/mp4", 3, 5_000, 1)
        val withAttachments = state.messages.single().copy(attachments = listOf(image, audio))
        var clickedId: String? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(messages = listOf(withAttachments), multiSelect = true),
                    ChatActions(onMessageClick = { clickedId = it }),
                )
            }
        }

        compose.onNodeWithTag("message_image_img1").performClick()
        assertEquals("m1", clickedId)
        compose.onNodeWithTag("attachment_preview").assertDoesNotExist()

        compose.onNodeWithTag("play_audio_aud1").performClick()
        assertEquals("m1", clickedId)
        compose.onNodeWithText("▶").assertIsDisplayed()
        compose.onNodeWithTag("attachment_preview").assertDoesNotExist()
    }

    @Test
    fun imagePreviewShowsSaveAndShareIcons() {
        val image = MessageAttachment("img1", "image", "attachments/img1.jpg", "image/jpeg", 3, 0, 1)
        val withImage = state.messages.single().copy(attachments = listOf(image))
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "preview-image.png")
        val bitmap = android.graphics.Bitmap.createBitmap(16, 12, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var saved: MessageAttachment? = null
        var shared: MessageAttachment? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(messages = listOf(withImage)),
                    ChatActions(
                        attachmentUri = { Uri.fromFile(file) },
                        saveImage = { attachment, _ -> saved = attachment },
                        shareImage = { attachment, _ -> shared = attachment },
                    ),
                )
            }
        }

        compose.onNodeWithTag("message_image_img1").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("attachment_preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("preview_save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("preview_share").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("preview_save").performClick()
        compose.onNodeWithTag("preview_share").performClick()
        assertEquals(image, saved)
        assertEquals(image, shared)
        compose.onNodeWithTag("preview_close").performClick()
        compose.onNodeWithTag("preview_save").assertDoesNotExist()
    }

    @Test
    fun imagePreviewDisablesActionsWhenImageUnavailable() {
        val image = MessageAttachment("img1", "image", "attachments/missing.jpg", "image/jpeg", 3, 0, 1)
        val withImage = state.messages.single().copy(attachments = listOf(image))
        compose.setContent {
            MaterialTheme {
                ChatScreen(state.copy(messages = listOf(withImage)), ChatActions())
            }
        }

        compose.onNodeWithTag("message_image_img1").performClick()
        compose.onNodeWithTag("attachment_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("preview_save").assertIsNotEnabled()
        compose.onNodeWithTag("preview_share").assertIsNotEnabled()
    }

    @Test
    fun menuOpensDrawerWithSettingsNewChatAndHistory() {
        compose.setContent {
            MaterialTheme { ChatScreen(state, ChatActions()) }
        }

        compose.onNodeWithTag("open_drawer").performClick()

        compose.onNodeWithTag("settings_entry").assertIsDisplayed()
        compose.onNodeWithTag("new_chat").assertIsDisplayed()
        compose.onNodeWithText("历史对话").assertIsDisplayed()
        compose.onNodeWithTag("session_s1").assertIsDisplayed()
        compose.onNodeWithTag("start_merge").assertDoesNotExist()
    }

    @Test
    fun drawerSortButtonTogglesWithoutText() {
        var toggled = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(state, ChatActions(onToggleSessionSort = { toggled = true }))
            }
        }

        compose.onNodeWithTag("open_drawer").performClick()
        compose.onNodeWithTag("session_sort").assertIsDisplayed()
        compose.onNodeWithTag("session_sort").performClick()

        assertTrue(toggled)
    }

    @Test
    fun multiSelectShowsForwardAction() {
        var forwardRequested = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(multiSelect = true, selectedMessages = setOf("m1")),
                    ChatActions(onRequestForwardMessages = { forwardRequested = true }),
                )
            }
        }

        compose.onNodeWithTag("forward_selected").assertIsDisplayed()
        compose.onNodeWithTag("forward_selected").performClick()
        assertTrue(forwardRequested)
    }

    @Test
    fun forwardPickerTargetsExcludeCurrentSession() {
        val other = SessionRecord("s2", "另一个对话", 2, 2)
        var forwardedTo: String? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        sessions = state.sessions + other,
                        multiSelect = true,
                        selectedMessages = setOf("m1"),
                        forwardPickerVisible = true,
                    ),
                    ChatActions(onConfirmForwardMessages = { forwardedTo = it }),
                )
            }
        }

        compose.onNodeWithTag("forward_target_s1").assertDoesNotExist()
        compose.onNodeWithTag("forward_target_s2").performClick()
        assertEquals("s2", forwardedTo)
    }

    @Test
    fun forwardPickerOffersExternalSharingWithOnlyOneSession() {
        val destination = ComponentName("test.receiver", "test.receiver.ShareActivity")
        val resolver = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        resolver.addResolveInfoForIntent(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            ResolveInfo().apply {
                isDefault = true
                nonLocalizedLabel = "测试接收应用"
                activityInfo = ActivityInfo().apply {
                    packageName = destination.packageName
                    name = destination.className
                    exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = destination.packageName }
                }
            },
        )
        val other = SessionRecord("s2", "另一个对话", 2, 2)
        val chat = mutableStateOf(
            state.copy(multiSelect = true, selectedMessages = setOf("m1"), forwardPickerVisible = true),
        )
        var requested: ComponentName? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    chat.value,
                    ChatActions(onShareForwardMessages = { requested = it }),
                )
            }
        }

        val appTag = "forward_app_${destination.flattenToShortString()}"
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(appTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(appTag).assertIsDisplayed()
        compose.onNodeWithText("暂无其他对话").assertIsDisplayed()

        compose.runOnIdle { chat.value = chat.value.copy(sessions = state.sessions + other) }
        compose.onNodeWithTag("forward_target_s2").assertIsDisplayed()
        compose.onNodeWithTag(appTag).performClick()
        assertEquals(destination, requested)
    }

    @Test
    fun forwardSheetShowsWhenNoExternalAppCanReceiveContent() {
        val resolver = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        resolver.setResolveInfosForIntent(Intent(Intent.ACTION_SEND).setType("text/plain"), emptyList())
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(multiSelect = true, selectedMessages = setOf("m1"), forwardPickerVisible = true),
                    ChatActions(),
                )
            }
        }

        val empty = androidx.compose.ui.test.hasText("没有可接收当前内容的应用")
        compose.waitUntil(5_000) { compose.onAllNodes(empty).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("没有可接收当前内容的应用").assertIsDisplayed()
    }

    @Test
    fun messageMultiSelectOffersCopyDeleteAndMergeWithConfirmation() {
        val second = MessageRecord("m2", "s1", "user", "第二条", createdAt = 1_700_000_000_001)
        val current = mutableStateOf(state.copy(messages = state.messages + second))
        var merged = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    current.value,
                    ChatActions(
                        onStartMultiSelect = { message ->
                            current.value = current.value.copy(multiSelect = true, selectedMessages = setOf(message.id))
                        },
                        onMessageClick = { id ->
                            val selected = current.value.selectedMessages
                            current.value = current.value.copy(selectedMessages = if (id in selected) selected - id else selected + id)
                        },
                        onRequestMergeMessages = {
                            current.value = current.value.copy(pendingMergeMessages = current.value.selectedMessages)
                        },
                        onCancelMergeMessages = { current.value = current.value.copy(pendingMergeMessages = null) },
                        onConfirmMergeMessages = { merged = true },
                    ),
                )
            }
        }
        compose.onNodeWithTag("message_m1").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_multi").performClick()
        compose.onNodeWithTag("copy_selected").assertIsDisplayed()
        compose.onNodeWithTag("delete_selected").assertIsDisplayed()
        compose.onNodeWithTag("merge_selected").assertIsNotEnabled()
        compose.onNodeWithTag("message_m2").performClick()
        compose.onNodeWithTag("merge_selected").assertIsEnabled().performClick()
        compose.onNodeWithText("合并 2 条消息").assertIsDisplayed()
        compose.onNodeWithTag("confirm_merge_messages").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(false, merged)
        compose.onNodeWithTag("merge_selected").performClick()
        compose.onNodeWithTag("confirm_merge_messages").performClick()
        assertEquals(true, merged)
    }

    @Test
    fun tappingMessageTogglesItsTime() {
        val revealed: MutableState<String?> = mutableStateOf(null)
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state.copy(revealedMessageId = revealed.value),
                    actions = ChatActions(
                        onMessageClick = { id ->
                            revealed.value = if (revealed.value == id) null else id
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("message_m1").performClick()
        compose.onNodeWithTag("time_m1").assertExists()

        compose.onNodeWithTag("message_m1").performClick()
        compose.onNodeWithTag("time_m1").assertDoesNotExist()
    }

    @Test
    fun derivedResultIsNotShownBesideOriginalMessage() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(state, ChatActions())
            }
        }

        compose.onNodeWithTag("message_m1").assertIsDisplayed()
        compose.onNodeWithTag("ai_result_m1").assertDoesNotExist()
        compose.onNodeWithText("历史结果").assertDoesNotExist()
    }

    @Test
    fun drawerExportAndUnreadResultActionsAreSeparate() {
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(unreadOrganizationSessions = setOf("s1")),
                    ChatActions(onOpenOrganizationResults = { opened = it }),
                )
            }
        }
        compose.onNodeWithTag("open_drawer").performClick()
        compose.onNodeWithTag("session_s1").performTouchInput { longClick() }
        compose.onNodeWithTag("organize_s1").assertDoesNotExist()
        compose.onNodeWithTag("view_organization_s1").assertDoesNotExist()
        compose.onNodeWithTag("export_session_s1").performClick()
        assertEquals("s1", opened)
        compose.onNodeWithTag("export_session_s1").assertDoesNotExist()

        // 未读圆点独立于长按菜单，重新打开抽屉后仍可直接查看。
        compose.onNodeWithTag("open_drawer").performClick()
        compose.onNodeWithTag("organization_unread_s1").performClick()
        assertEquals("s1", opened)
    }

    @Test
    fun organizationResultsRenderDocumentAndExports() {
        var exported: DocumentExportFormat? = null
        val document = SessionDocumentRecord(
            sessionId = "s1",
            content = "# 工作\n\n10:00 确认需求。",
            messageCount = 3,
            generatedAt = 1_700_000_000_000,
            modelSnapshot = "mock",
            isMock = true,
        )
        compose.setContent {
            MaterialTheme {
                OrganizationResultsScreen(
                    state.copy(resultSessionId = "s1", resultDocument = document),
                    onBack = {},
                    onRegenerate = {},
                    onExport = { exported = it },
                )
            }
        }
        compose.onNodeWithTag("organization_results").assertIsDisplayed()
        compose.onNodeWithTag("organization_document").assertIsDisplayed()
        compose.onNodeWithText("历史结果").assertIsDisplayed()
        compose.onNodeWithTag("export_md").performClick()
        assertEquals(DocumentExportFormat.Markdown, exported)
        compose.onNodeWithTag("export_pdf").assertIsDisplayed()
        compose.onNodeWithTag("export_png").assertIsDisplayed()
    }

    @Test
    fun organizationResultsShowImagesInlineForLocalDocument() {
        val time = 1_700_000_000_000L
        val stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()))
        val image = File(RuntimeEnvironment.getApplication().cacheDir, "document-image.png")
        val bitmap = android.graphics.Bitmap.createBitmap(16, 12, android.graphics.Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val attachment = MessageAttachment(
            id = "a1",
            kind = "image",
            relativePath = "document-image.png",
            mimeType = "image/png",
            sizeBytes = image.length(),
            createdAt = time,
        )
        val message = MessageRecord(
            id = "m1",
            sessionId = "s1",
            role = "user",
            rawContent = "看这张图",
            createdAt = time,
            attachments = listOf(attachment),
        )
        val document = SessionDocumentRecord(
            sessionId = "s1",
            content = "# 工作\n\n『$stamp』看这张图",
            messageCount = 1,
            generatedAt = time,
            modelSnapshot = "本地整理",
        )
        compose.setContent {
            MaterialTheme {
                OrganizationResultsScreen(
                    state.copy(
                        resultSessionId = "s1",
                        resultDocument = document,
                        resultMessages = listOf(message),
                    ),
                    onBack = {},
                    onRegenerate = {},
                    onExport = {},
                    actions = ChatActions(attachmentUri = { Uri.fromFile(image) }),
                )
            }
        }
        compose.onNodeWithTag("organization_document").assertIsDisplayed()
        compose.onNodeWithTag("document_image_a1").assertIsDisplayed()
    }

    @Test
    fun failedOrganizationShowsErrorAndRegenerateOnResultsScreen() {        var regenerated = false
        compose.setContent {
            MaterialTheme {
                OrganizationResultsScreen(
                    state.copy(
                        resultSessionId = "s1",
                        organizationErrors = mapOf("s1" to "断网"),
                    ),
                    onBack = {},
                    onRegenerate = { regenerated = true },
                    onExport = {},
                )
            }
        }
        compose.onNodeWithTag("organization_empty").assertIsDisplayed()
        compose.onNodeWithText("断网").assertIsDisplayed()
        compose.onNodeWithTag("regenerate_organization").performClick()
        assertTrue(regenerated)
    }

    @Test
    fun userMessageMenuOffersEditAndCopiesCurrentText() {
        compose.setContent {
            MaterialTheme { ChatScreen(state, ChatActions()) }
        }

        compose.onNodeWithTag("message_m1").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_edit").assertIsDisplayed()
        compose.onNodeWithTag("menu_copy").performClick()

        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("今天要买牛奶", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun editingMessageFromMenuFocusesComposerWithCurrentText() {
        val chat = mutableStateOf(state)
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    chat.value,
                    ChatActions(onRequestEdit = { message ->
                        chat.value = chat.value.copy(editing = message, input = message.currentContent)
                    }),
                )
            }
        }

        compose.onNodeWithTag("message_m1").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_edit").performClick()

        compose.onNodeWithTag("chat_input").assertIsFocused()
        assertEquals(state.messages.single().currentContent, chat.value.input)
        compose.onNodeWithTag("chat_input").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange,
                TextRange(state.messages.single().currentContent.length),
            ),
        )
    }

    @Test
    fun assistantMessageMenuHidesEdit() {
        val assistant = MessageRecord("m2", "s1", "assistant", "助手回复", createdAt = 1)
        compose.setContent {
            MaterialTheme {
                ChatScreen(state.copy(messages = state.messages + assistant), ChatActions())
            }
        }

        compose.onNodeWithTag("message_m2").performTouchInput { longClick() }

        compose.onNodeWithTag("menu_copy").assertIsDisplayed()
        compose.onNodeWithTag("menu_edit").assertDoesNotExist()
    }

    @Test
    fun swipingFromLeftEdgeOpensDrawer() {
        compose.setContent {
            MaterialTheme { ChatScreen(state, ChatActions()) }
        }

        compose.onRoot().performTouchInput {
            swipeRight(startX = 0f, endX = width * 0.9f)
        }

        compose.onNodeWithTag("new_chat").assertIsDisplayed()
    }

    @Test
    fun appendedMessageScrollsIntoViewEvenWhenReadingOlderMessages() {
        val messages = (1..30).map { index ->
            MessageRecord("m$index", "s1", "user", "消息$index", createdAt = index.toLong())
        }
        val chat = mutableStateOf(state.copy(messages = messages))
        compose.setContent {
            MaterialTheme { ChatScreen(chat.value, ChatActions()) }
        }
        compose.waitForIdle()
        repeat(6) {
            compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }

        val appended = MessageRecord("m31", "s1", "user", "新同步的消息", createdAt = 31)
        chat.value = chat.value.copy(messages = messages + appended)
        compose.waitForIdle()

        compose.onNodeWithText("新同步的消息").assertIsDisplayed()
    }

    @Test
    fun editingOlderMessageKeepsScrollPosition() {
        val messages = (1..30).map { index ->
            MessageRecord("m$index", "s1", "user", "消息$index", createdAt = index.toLong())
        }
        val chat = mutableStateOf(state.copy(messages = messages))
        compose.setContent {
            MaterialTheme { ChatScreen(chat.value, ChatActions()) }
        }
        compose.waitForIdle()
        repeat(6) {
            compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }

        val edited = messages.map {
            if (it.id == "m1") it.copy(currentContent = "改写后的消息") else it
        }
        chat.value = chat.value.copy(messages = edited)
        compose.waitForIdle()

        compose.onNodeWithText("改写后的消息").assertIsDisplayed()
    }

    @Test
    fun messageMenuOffersReminderEntry() {
        var messageTarget: MessageRecord? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state,
                    ChatActions(onRequestMessageReminder = { messageTarget = it }),
                )
            }
        }

        compose.onNodeWithTag("message_m1").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_reminder").performClick()
        compose.runOnIdle { assertEquals("m1", messageTarget?.id) }
    }

    @Test
    fun reminderDialogShowsPendingTargetsAndDelegatesActions() {
        val pending = ReminderRecord(
            id = "r1",
            sessionId = "s1",
            messageId = "m1",
            triggerAt = System.currentTimeMillis() + 3_600_000,
            createdAt = 1,
            updatedAt = 1,
        )
        var cancelled: String? = null
        var confirmed: Long? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        reminders = listOf(pending),
                        reminderTarget = ReminderTarget("s1", "工作", "m1", "今天要买牛奶"),
                    ),
                    ChatActions(
                        onCancelReminder = { cancelled = it },
                        onConfirmReminder = { confirmed = it },
                    ),
                )
            }
        }

        compose.onNodeWithText("提醒消息：今天要买牛奶").assertIsDisplayed()
        compose.onNodeWithText("待触发提醒").assertIsDisplayed()
        compose.onNodeWithText("取消提醒").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("r1", cancelled) }

        compose.onNodeWithTag("reminder_preset_hour").performScrollTo().performClick()
        compose.onNodeWithTag("reminder_confirm").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(confirmed)
            assertTrue(confirmed!! > System.currentTimeMillis() + 3_000_000)
        }
    }

    @Test
    fun reminderDialogPrefillsAssistSuggestedTime() {
        val zone = java.time.ZoneId.systemDefault()
        val suggestedLocal = java.time.LocalDateTime.now(zone)
            .plusDays(2).withHour(14).withMinute(30).withSecond(0).withNano(0)
        val suggested = suggestedLocal.atZone(zone).toInstant().toEpochMilli()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        reminderTarget = ReminderTarget(
                            sessionId = "s1",
                            sessionTitle = "工作",
                            messageId = "m1",
                            messagePreview = "今天要买牛奶",
                            suggestedAt = suggested,
                        ),
                    ),
                    ChatActions(),
                )
            }
        }

        compose.onNodeWithTag("reminder_time_button").assertIsDisplayed()
        compose.onNodeWithText("14:30").assertIsDisplayed()
        compose.onNodeWithText(
            "提醒时间：${suggestedLocal.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun reminderDialogHidesExpiredScheduledReminders() {
        val future = ReminderRecord(
            id = "r1",
            sessionId = "s1",
            messageId = "m1",
            triggerAt = System.currentTimeMillis() + 3_600_000,
            createdAt = 1,
            updatedAt = 1,
        )
        val expired = ReminderRecord(
            id = "r2",
            sessionId = "s1",
            messageId = "m1",
            triggerAt = System.currentTimeMillis() - 60_000,
            createdAt = 1,
            updatedAt = 1,
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        reminders = listOf(future, expired),
                        reminderTarget = ReminderTarget("s1", "工作", "m1", "今天要买牛奶"),
                    ),
                    ChatActions(),
                )
            }
        }

        compose.onAllNodesWithText("取消提醒").assertCountEquals(1)
    }

    @Test
    fun reminderDialogHidesPendingSectionWhenOnlyExpiredRemain() {
        val expired = ReminderRecord(
            id = "r1",
            sessionId = "s1",
            messageId = "m1",
            triggerAt = System.currentTimeMillis() - 60_000,
            createdAt = 1,
            updatedAt = 1,
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        reminders = listOf(expired),
                        reminderTarget = ReminderTarget("s1", "工作", "m1", "今天要买牛奶"),
                    ),
                    ChatActions(),
                )
            }
        }

        compose.onNodeWithText("待触发提醒").assertDoesNotExist()
        compose.onNodeWithText("取消提醒").assertDoesNotExist()
    }

    @Test
    fun reminderTimeDialogOffersHourAndMinuteWheels() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        reminderTarget = ReminderTarget("s1", "工作", "m1", "今天要买牛奶"),
                    ),
                    ChatActions(),
                )
            }
        }

        compose.onNodeWithTag("reminder_time_button").performScrollTo().performClick()

        compose.onNodeWithTag("reminder_hour_wheel").assertIsDisplayed()
        compose.onNodeWithTag("reminder_minute_wheel").assertIsDisplayed()
    }

    @Test
    fun suggestionBubbleShowsReminderAndTriggersActions() {
        var applied: String? = null
        var ignored: String? = null
        val suggestion = LocalReminderSuggestion(
            messageId = "m1",
            contentVersion = 1,
            triggerAt = System.currentTimeMillis() + 3_600_000,
            phrase = "今天下午三点",
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(reminderSuggestions = mapOf("m1" to suggestion)),
                    ChatActions(
                        onOpenSuggestedReminder = { applied = it },
                        onDismissReminderSuggestion = { ignored = it },
                    ),
                )
            }
        }

        compose.onNodeWithText("提醒建议").assertIsDisplayed()
        compose.onNodeWithText("「今天下午三点」").assertIsDisplayed()
        compose.onNodeWithText("创建日程").assertIsDisplayed()
        compose.onNodeWithTag("suggestion_apply_reminder").performClick()
        compose.onNodeWithTag("suggestion_ignore_reminder").performClick()
        compose.runOnIdle {
            assertEquals("m1", applied)
            assertEquals("m1", ignored)
        }
    }

    @Test
    fun suggestionBubbleIsHiddenWithoutReminder() {
        compose.setContent {
            MaterialTheme { ChatScreen(state, ChatActions()) }
        }

        compose.onNodeWithTag("suggestion_m1").assertDoesNotExist()
    }

    @Test
    fun suggestionBubbleIsHiddenAfterVersionChange() {
        val stale = LocalReminderSuggestion(
            messageId = "m1",
            contentVersion = 2,
            triggerAt = System.currentTimeMillis() + 3_600_000,
            phrase = "今天下午三点",
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(state.copy(reminderSuggestions = mapOf("m1" to stale)), ChatActions())
            }
        }

        compose.onNodeWithTag("suggestion_m1").assertDoesNotExist()
    }

    @Test
    fun restoringDirectoryShowsLoadingInsteadOfPickerOrEmptyText() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(
                        directoryReady = false,
                        restoringDirectory = true,
                        messages = emptyList(),
                    ),
                    ChatActions(),
                )
            }
        }

        compose.onNodeWithTag("pick_directory").assertDoesNotExist()
        compose.onNodeWithTag("restoring_directory").assertIsDisplayed()
        compose.onNodeWithText("选择记录目录后，即可开始记录。").assertDoesNotExist()
    }

    @Test
    fun alarmButtonIsShownOnlyWhenRemindersExistAndOpensList() {
        var opened = 0
        val preview = ReminderPreview(
            id = "r1",
            sessionId = "s1",
            messageId = "m1",
            triggerAt = System.currentTimeMillis() + 600_000,
            text = "今天要买牛奶",
            sessionTitle = "工作",
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(scheduledReminders = listOf(preview)),
                    ChatActions(onOpenReminderList = { opened++ }),
                )
            }
        }

        compose.onNodeWithTag("reminder_list_button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun alarmButtonIsHiddenWithoutReminders() {
        compose.setContent {
            MaterialTheme { ChatScreen(state, ChatActions()) }
        }

        compose.onNodeWithTag("reminder_list_button").assertDoesNotExist()
    }

    @Test
    fun reminderListSupportsQuickCancelAndOpen() {
        val preview = ReminderPreview(
            id = "r1",
            sessionId = "s1",
            messageId = "m1",
            triggerAt = System.currentTimeMillis() + 600_000,
            text = "提醒内容示例",
            sessionTitle = "工作",
        )
        var cancelled: String? = null
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state.copy(scheduledReminders = listOf(preview), reminderListVisible = true),
                    ChatActions(
                        onCancelReminder = { cancelled = it },
                        onOpenReminder = { opened = it },
                    ),
                )
            }
        }

        compose.onNodeWithText("全部提醒").assertIsDisplayed()
        compose.onNodeWithText("提醒内容示例").assertIsDisplayed()
        compose.onNodeWithTag("reminder_preview_cancel_r1").performClick()
        compose.onNodeWithTag("reminder_preview_r1").performClick()
        compose.runOnIdle {
            assertEquals("r1", cancelled)
            assertEquals("r1", opened)
        }
    }
}
