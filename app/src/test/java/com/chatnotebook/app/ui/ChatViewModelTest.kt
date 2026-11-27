package com.chatnotebook.app.ui

import android.net.Uri
import com.chatnotebook.app.data.ChatRepository
import com.chatnotebook.app.data.EditRecord
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.QuoteRecord
import com.chatnotebook.app.data.ReactionRecord
import com.chatnotebook.app.data.REMINDER_CANCELLED
import com.chatnotebook.app.data.ReminderDelivery
import com.chatnotebook.app.data.ReminderPreview
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.ReminderSaveOutcome
import com.chatnotebook.app.data.SessionAttachmentCleanupException
import com.chatnotebook.app.data.SessionDocumentRecord
import com.chatnotebook.app.data.SessionRecord
import com.chatnotebook.app.reminder.LocalReminderSuggestion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ChatViewModelTest {
    private lateinit var repository: FakeChatRepository
    private lateinit var model: ChatViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeChatRepository()
        model = ChatViewModel(repository)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun newConversationCreatesIndependentBlankSession() = runTest {
        model.createSession()
        val first = model.uiState.value.activeSessionId!!
        model.createSession()
        val second = model.uiState.value.activeSessionId!!

        assertNotEquals(first, second)
        assertEquals(2, model.uiState.value.sessions.size)
        assertTrue(model.uiState.value.messages.isEmpty())
    }

    @Test fun renameUpdatesListAndTitle() = runTest {
        model.createSession()
        val session = model.uiState.value.sessions.single()

        model.requestRename(session)
        assertEquals(session.id, model.uiState.value.renaming?.id)
        model.confirmRename("工作笔记")

        assertNull(model.uiState.value.renaming)
        assertEquals("工作笔记", model.uiState.value.activeTitle)
        assertEquals("工作笔记", repository.sessionState.value.single().title)
    }

    @Test fun externallyRenamedSessionUpdatesOpenChatTitle() = runTest {
        model.createSession()
        val id = model.uiState.value.activeSessionId!!

        repository.sessionState.update { sessions ->
            sessions.map { if (it.id == id) it.copy(title = "同步后的名称") else it }
        }
        advanceUntilIdle()

        assertEquals("同步后的名称", model.uiState.value.activeTitle)
        assertEquals("同步后的名称", model.uiState.value.sessions.single { it.id == id }.title)
    }

    @Test fun sessionSortTogglesBetweenCreatedAndModified() = runTest {
        repository.sessionState.value = listOf(
            SessionRecord("old-created", "旧建新改", createdAt = 1, updatedAt = 100),
            SessionRecord("new-created", "新建旧改", createdAt = 2, updatedAt = 50),
        )
        advanceUntilIdle()

        assertEquals(listOf("new-created", "old-created"), model.uiState.value.sessions.map { it.id })
        model.toggleSessionSort()
        assertEquals(SessionSortOrder.Modified, model.uiState.value.sessionSortOrder)
        assertEquals(listOf("old-created", "new-created"), model.uiState.value.sessions.map { it.id })

        model.toggleSessionSort()
        assertEquals(SessionSortOrder.Created, model.uiState.value.sessionSortOrder)
        assertEquals(listOf("new-created", "old-created"), model.uiState.value.sessions.map { it.id })
    }

    @Test fun deleteRequiresConfirmationAndSwitchesToRemainingSession() = runTest {
        model.createSession()
        val first = model.uiState.value.sessions.single()
        model.createSession()
        val secondId = model.uiState.value.activeSessionId!!

        model.requestDelete(first)
        model.cancelDelete()
        assertEquals(2, model.uiState.value.sessions.size)

        model.requestDelete(first)
        model.confirmDelete()
        assertNull(model.uiState.value.pendingDelete)
        assertEquals(1, model.uiState.value.sessions.size)
        assertEquals(secondId, model.uiState.value.activeSessionId)
    }

    @Test fun deletionDismissesConfirmationAndReportsAttachmentCleanupFailure() = runTest {
        model.createSession()
        val session = model.uiState.value.sessions.single()
        repository.cleanupError = SessionAttachmentCleanupException(
            mapOf("attachments/locked.jpg" to IOException("Attachment file is read-only")),
        )

        model.requestDelete(session)
        model.confirmDelete()

        assertTrue(model.uiState.value.sessions.isEmpty())
        assertNull(model.uiState.value.pendingDelete)
        assertEquals("对话已删除，1 个附件清理失败", model.uiState.value.notice)
    }

    @Test fun forwardsSelectedMessagesToChosenSession() = runTest {
        model.createSession()
        val sourceId = model.uiState.value.activeSessionId!!
        model.updateInput("要转发的消息")
        model.send()
        val selected = model.uiState.value.messages.single()

        model.createSession()
        val targetId = model.uiState.value.activeSessionId!!
        model.selectSession(sourceId)
        advanceUntilIdle()

        model.startMultiSelect(selected)
        model.requestForwardMessages()
        assertTrue(model.uiState.value.forwardPickerVisible)
        model.confirmForwardMessages(targetId)
        advanceUntilIdle()

        assertEquals(1, repository.forwardCalls)
        assertFalse(model.uiState.value.forwardPickerVisible)
        assertFalse(model.uiState.value.multiSelect)
        assertTrue(model.uiState.value.selectedMessages.isEmpty())
        assertEquals(sourceId, model.uiState.value.activeSessionId)

        model.selectSession(targetId)
        advanceUntilIdle()
        assertEquals(listOf("要转发的消息"), model.uiState.value.messages.map { it.currentContent })
    }

    @Test fun sharingUsesMessageOrderAndKeepsSelectionAfterHandoff() = runTest {
        model.createSession()
        model.updateInput("第一条")
        model.send()
        model.updateInput("第二条")
        model.send()
        val (first, second) = model.uiState.value.messages
        model.startMultiSelect(second)
        model.onMessageClick(first.id)
        model.requestForwardMessages()

        var shared = emptyList<MessageRecord>()
        model.shareForwardMessages { shared = it }

        assertEquals(listOf(first, second), shared)
        assertEquals(setOf(first.id, second.id), model.uiState.value.selectedMessages)
        assertTrue(model.uiState.value.multiSelect)
        assertFalse(model.uiState.value.forwardPickerVisible)
        assertNull(model.uiState.value.notice)
        assertEquals(0, repository.forwardCalls)
    }

    @Test fun sharingFailureShowsNoticeAndKeepsSelection() = runTest {
        model.createSession()
        model.updateInput("待分享")
        model.send()
        val message = model.uiState.value.messages.single()
        model.startMultiSelect(message)
        model.requestForwardMessages()

        model.shareForwardMessages { throw IOException("附件不可读取") }

        assertEquals(setOf(message.id), model.uiState.value.selectedMessages)
        assertTrue(model.uiState.value.multiSelect)
        assertFalse(model.uiState.value.forwardPickerVisible)
        assertEquals("附件不可读取", model.uiState.value.notice)
    }

    @Test fun blankInputKeepsDraftAndCreatesNoMessage() = runTest {
        model.createSession()
        model.updateInput("   ")
        model.send()

        assertTrue(model.uiState.value.messages.isEmpty())
        assertEquals("   ", model.uiState.value.input)
    }

    @Test fun repeatedUpdatesKeepOrderAndResetInput() = runTest {
        model.createSession()
        model.updateInput("第一条")
        model.send()
        model.updateInput("第二条")
        model.send()

        val messages = model.uiState.value.messages
        assertEquals(listOf("第一条", "第二条"), messages.map { it.currentContent })
        assertEquals("", model.uiState.value.input)

        model.toggleMessageTime(messages[0].id)
        assertEquals(messages[0].id, model.uiState.value.revealedMessageId)
        model.toggleMessageTime(messages[1].id)
        assertEquals(messages[1].id, model.uiState.value.revealedMessageId)
        model.toggleMessageTime(messages[1].id)
        assertNull(model.uiState.value.revealedMessageId)
    }

    @Test fun organizingIsExplicitAndResultsReadStateIsIndependentOfActiveChat() = runTest {
        model.createSession()
        val firstId = model.uiState.value.activeSessionId!!
        model.updateInput("第一条")
        model.send()
        model.createSession()
        val secondId = model.uiState.value.activeSessionId!!

        model.organizeSession(firstId)
        model.organizeSession(firstId)
        assertEquals(1, repository.organizeCalls)
        assertEquals(secondId, model.uiState.value.activeSessionId)
        assertTrue(firstId in model.uiState.value.organizingSessions)
        assertEquals("后台整理中", model.uiState.value.notice)

        repository.unreadOrganizationSessions.value = setOf(firstId)
        assertTrue(firstId in model.uiState.value.unreadOrganizationSessions)
        model.openOrganizationResults(firstId)
        assertEquals(firstId, model.uiState.value.resultSessionId)
        assertTrue(firstId !in model.uiState.value.unreadOrganizationSessions)
        model.closeOrganizationResults()
        assertNull(model.uiState.value.resultSessionId)
    }

    @Test fun openingOrganizationResultsStartsOneOrganization() = runTest {
        model.createSession()
        val id = model.uiState.value.activeSessionId!!
        model.updateInput("一条消息")
        model.send()
        assertEquals(0, repository.organizeCalls)

        model.openOrganizationResults(id)
        advanceUntilIdle()

        assertEquals(1, repository.organizeCalls)
        assertEquals(id, model.uiState.value.resultSessionId)
    }

    @Test fun sendingWithoutSessionCreatesOne() = runTest {
        model.updateInput("自动开始")
        model.send()

        assertEquals(1, model.uiState.value.sessions.size)
        assertEquals(listOf("自动开始"), model.uiState.value.messages.map { it.currentContent })
    }

    @Test fun sendingMessageRequestsScrollToLatest() = runTest {
        model.createSession()
        val before = model.uiState.value.scrollSignal
        model.updateInput("新消息")
        model.send()

        assertEquals(before + 1, model.uiState.value.scrollSignal)
    }

    @Test fun blankInputDoesNotRequestScroll() = runTest {
        model.createSession()
        val before = model.uiState.value.scrollSignal
        model.updateInput("   ")
        model.send()

        assertEquals(before, model.uiState.value.scrollSignal)
    }

    @Test fun failedSendDoesNotRequestScroll() = runTest {
        model.createSession()
        val before = model.uiState.value.scrollSignal
        repository.sendError = IllegalStateException("写入失败")
        model.updateInput("无法保存")
        model.send()

        assertEquals(before, model.uiState.value.scrollSignal)
        assertTrue(model.uiState.value.messages.isEmpty())
        assertTrue(!model.uiState.value.busy)
    }

    @Test fun switchingSessionRequestsScroll() = runTest {
        model.createSession()
        val firstId = model.uiState.value.activeSessionId!!
        model.createSession()
        val before = model.uiState.value.scrollSignal

        model.selectSession(firstId)

        assertEquals(firstId, model.uiState.value.activeSessionId)
        assertEquals(before + 1, model.uiState.value.scrollSignal)
    }

    @Test fun mergingMessagesRequestsScrollToResult() = runTest {
        model.createSession()
        model.updateInput("第一条")
        model.send()
        model.updateInput("第二条")
        model.send()
        val messages = model.uiState.value.messages
        model.startMultiSelect(messages.first())
        model.toggleSelected(messages.last().id)
        model.requestMergeMessages()
        assertEquals(2, model.uiState.value.pendingMergeMessages?.size)
        val before = model.uiState.value.scrollSignal

        model.confirmMergeMessages()

        assertEquals(1, repository.mergeCalls)
        assertEquals(listOf("第一条\n第二条"), model.uiState.value.messages.map { it.currentContent })
        assertEquals(before + 1, model.uiState.value.scrollSignal)
    }

    @Test fun switchingSessionClearsDraftAndOldMessageActions() = runTest {
        model.createSession()
        val firstId = model.uiState.value.activeSessionId!!
        model.updateInput("旧对话内容")
        model.send()
        val oldMessage = model.uiState.value.messages.single()
        model.createSession()
        val secondId = model.uiState.value.activeSessionId!!

        model.selectSession(firstId)
        model.requestEdit(oldMessage)
        model.requestQuote(oldMessage)
        model.updateInput("未发送的旧草稿")
        model.selectSession(secondId)

        assertEquals("", model.uiState.value.input)
        assertNull(model.uiState.value.editing)
        assertNull(model.uiState.value.quoteTarget)
        model.updateInput("新对话内容")
        model.send()
        assertEquals(listOf("新对话内容"), model.uiState.value.messages.map { it.currentContent })
        model.selectSession(firstId)
        assertEquals(listOf("旧对话内容"), model.uiState.value.messages.map { it.currentContent })
    }

    @Test fun messageMergeRequiresTwoSelectedAndCancelDoesNotWrite() = runTest {
        model.createSession()
        model.updateInput("第一条")
        model.send()
        val first = model.uiState.value.messages.single()
        model.updateInput("第二条")
        model.send()
        val second = model.uiState.value.messages.last()

        model.startMultiSelect(first)
        model.requestMergeMessages()
        assertNull(model.uiState.value.pendingMergeMessages)
        model.toggleSelected(second.id)
        model.requestMergeMessages()
        assertEquals(setOf(first.id, second.id), model.uiState.value.pendingMergeMessages)
        model.cancelMergeMessages()
        assertEquals(0, repository.mergeCalls)
        assertEquals(2, model.uiState.value.messages.size)

        model.requestMergeMessages()
        model.confirmMergeMessages()
        assertEquals(1, repository.mergeCalls)
        assertEquals(listOf("第一条\n第二条"), model.uiState.value.messages.map { it.currentContent })
        assertEquals(false, model.uiState.value.multiSelect)
        assertNull(model.uiState.value.pendingMergeMessages)
    }

    @Test fun oldSendCompletionDoesNotClearNewSessionDraft() = runTest {
        model.createSession()
        val firstId = model.uiState.value.activeSessionId!!
        model.createSession()
        val secondId = model.uiState.value.activeSessionId!!
        model.selectSession(firstId)
        val gate = CompletableDeferred<Unit>()
        repository.sendGate = gate
        model.updateInput("相同文字")
        model.send()

        model.selectSession(secondId)
        model.updateInput("相同文字")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(secondId, model.uiState.value.activeSessionId)
        assertEquals("相同文字", model.uiState.value.input)
        assertTrue(model.uiState.value.messages.isEmpty())
    }
    @Test fun addingTwoImagesThenRemovingOneDeletesOnlyThatFile() = runTest {
        model.createSession()
        model.attachImages(listOf(Uri.parse("content://test/1"), Uri.parse("content://test/2")))
        advanceUntilIdle()

        val drafts = model.uiState.value.draftAttachments
        assertEquals(2, drafts.size)

        model.removeDraftAttachment(drafts.first())
        advanceUntilIdle()

        assertEquals(listOf(drafts.last().id), model.uiState.value.draftAttachments.map { it.id })
        assertEquals(listOf(drafts.first().relativePath), repository.deletedAttachments)
    }

    @Test fun attachmentOnlyMessageIsSentAndClearsDraft() = runTest {
        model.createSession()
        model.attachImages(listOf(Uri.parse("content://test/1")))
        advanceUntilIdle()

        model.send()
        advanceUntilIdle()

        assertTrue(model.uiState.value.draftAttachments.isEmpty())
        val message = model.uiState.value.messages.single()
        assertEquals("", message.currentContent)
        assertEquals(1, message.attachments.size)
    }

    @Test fun failedSendKeepsDraftAttachments() = runTest {
        model.createSession()
        model.attachImages(listOf(Uri.parse("content://test/1")))
        advanceUntilIdle()
        repository.sendError = IllegalStateException("写入失败")

        model.send()
        advanceUntilIdle()

        assertEquals(1, model.uiState.value.draftAttachments.size)
        assertTrue(model.uiState.value.messages.isEmpty())
    }

    @Test fun switchingSessionClearsDraftAttachments() = runTest {
        model.createSession()
        val first = model.uiState.value.activeSessionId!!
        model.attachImages(listOf(Uri.parse("content://test/1")))
        advanceUntilIdle()
        model.createSession()

        model.selectSession(first)
        advanceUntilIdle()

        assertTrue(model.uiState.value.draftAttachments.isEmpty())
    }

    @Test fun notificationTapSelectsLoadedSessionOrWaitsForIt() = runTest {
        val sessions = listOf(SessionRecord("s1", "工作", 1, 1), SessionRecord("s2", "生活", 2, 2))
        repository.sessionState.value = sessions
        advanceUntilIdle()

        model.openSessionFromNotification("s2")
        assertEquals("s2", model.uiState.value.activeSessionId)

        model.openSessionFromNotification("s3")
        assertEquals("s2", model.uiState.value.activeSessionId)

        repository.sessionState.value = sessions + SessionRecord("s3", "新对话", 3, 3)
        advanceUntilIdle()
        assertEquals("s3", model.uiState.value.activeSessionId)
    }

    @Test fun notificationWithMessageScrollsToBubbleAndClearsHighlight() = runTest {
        repository.sessionState.value = listOf(
            SessionRecord("s1", "工作", 1, 1),
            SessionRecord("s2", "生活", 2, 2),
        )
        advanceUntilIdle()
        model.selectSession("s1")
        model.updateInput("今天要买牛奶")
        model.send()
        advanceUntilIdle()
        val target = model.uiState.value.messages.single()

        model.selectSession("s2")
        advanceUntilIdle()

        model.openSessionFromNotification("s1", target.id)
        runCurrent()

        assertEquals("s1", model.uiState.value.activeSessionId)
        assertEquals(target.id, model.uiState.value.scrollTargetId)
        assertEquals(target.id, model.uiState.value.highlightMessageId)

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(target.id, model.uiState.value.highlightMessageId)

        advanceTimeBy(1_500)
        runCurrent()
        assertNull(model.uiState.value.highlightMessageId)
    }

    @Test fun notificationInActiveSessionFocusesMessageImmediately() = runTest {
        model.createSession()
        model.updateInput("今天要买牛奶")
        model.send()
        advanceUntilIdle()
        val target = model.uiState.value.messages.single()
        val sessionId = model.uiState.value.activeSessionId!!

        model.openSessionFromNotification(sessionId, target.id)
        runCurrent()

        assertEquals(target.id, model.uiState.value.scrollTargetId)
        assertEquals(target.id, model.uiState.value.highlightMessageId)
    }

    @Test fun reminderEntriesCaptureMessageTargets() = runTest {
        repository.sessionState.value = listOf(SessionRecord("s1", "工作", 1, 1))
        advanceUntilIdle()

        model.requestMessageReminder(MessageRecord("m1", "s1", "user", "今天要买牛奶", createdAt = 1))
        assertEquals("s1", model.uiState.value.reminderTarget?.sessionId)
        assertEquals("工作", model.uiState.value.reminderTarget?.sessionTitle)
        assertEquals("m1", model.uiState.value.reminderTarget?.messageId)
        assertEquals("今天要买牛奶", model.uiState.value.reminderTarget?.messagePreview)
        model.dismissReminder()
        assertNull(model.uiState.value.reminderTarget)
    }

    @Test fun pastReminderTimeIsRejectedWithoutWriting() = runTest {
        repository.sessionState.value = listOf(SessionRecord("s1", "工作", 1, 1))
        advanceUntilIdle()
        model.requestMessageReminder(MessageRecord("m1", "s1", "user", "今天要买牛奶", createdAt = 1))

        model.saveReminder(System.currentTimeMillis() - 1_000)

        assertEquals("提醒时间必须晚于当前时间", model.uiState.value.notice)
        assertTrue(repository.reminderState.value.isEmpty())
        assertNotNull(model.uiState.value.reminderTarget)
    }

    @Test fun savingReminderReportsMissingExactAlarmOrNotificationPermission() = runTest {
        repository.sessionState.value = listOf(SessionRecord("s1", "工作", 1, 1))
        advanceUntilIdle()
        val message = MessageRecord("m1", "s1", "user", "今天要买牛奶", createdAt = 1)
        val future = System.currentTimeMillis() + 60_000

        model.requestMessageReminder(message)
        model.saveReminder(future)
        assertEquals("提醒已设置", model.uiState.value.notice)
        assertNull(model.uiState.value.reminderTarget)
        assertNull(model.uiState.value.reminderPermission)

        repository.reminderAllowed = false
        model.requestMessageReminder(message)
        model.saveReminder(future)
        assertEquals("提醒已保存，但通知权限未开启，到点可能看不到通知", model.uiState.value.notice)
        assertEquals(ReminderPermission.Notifications, model.uiState.value.reminderPermission)
        model.consumeReminderPermission()
        assertNull(model.uiState.value.reminderPermission)

        repository.reminderAllowed = true
        repository.reminderExact = false
        model.requestMessageReminder(message)
        model.saveReminder(future)
        assertEquals(
            "提醒已保存，但系统可能延迟通知；可开启“精确闹钟”权限提高准时率",
            model.uiState.value.notice,
        )
        assertEquals(ReminderPermission.ExactAlarm, model.uiState.value.reminderPermission)
    }

    @Test fun cancellingReminderDelegatesToRepository() = runTest {
        model.cancelReminder("r1")

        assertEquals(1, repository.cancelReminderCalls)
        assertEquals("提醒已取消", model.uiState.value.notice)
    }

    @Test fun reminderMarkerTracksMessageUntilReminderIsCancelled() = runTest {
        model.createSession()
        model.updateInput("今天要买牛奶")
        model.send()
        val message = model.uiState.value.messages.single()

        model.requestMessageReminder(message)
        model.saveReminder(System.currentTimeMillis() + 60_000)
        advanceUntilIdle()

        assertEquals(setOf(message.id), model.uiState.value.reminderMessageIds)

        model.cancelReminder(repository.reminderState.value.single().id)
        advanceUntilIdle()

        assertTrue(model.uiState.value.reminderMessageIds.isEmpty())
    }

    @Test fun localSuggestionAppearsAfterSendAndCanBeDismissed() = runTest {
        repository.reminderSuggestionFactory = { id ->
            LocalReminderSuggestion(id, 1, System.currentTimeMillis() + 3_600_000, "明天下午三点")
        }
        model.createSession()
        model.updateInput("明天下午三点提醒我交周报")
        model.send()
        advanceUntilIdle()

        val message = model.uiState.value.messages.single()
        assertEquals(setOf(message.id), model.uiState.value.reminderSuggestions.keys)
        assertEquals(1, repository.detectReminderCalls)

        model.dismissReminderSuggestion(message.id)
        assertTrue(model.uiState.value.reminderSuggestions.isEmpty())
        assertEquals("明天下午三点提醒我交周报", model.uiState.value.messages.single().currentContent)
    }

    @Test fun openingLocalSuggestionPrefillsPanelAndClearsAfterSave() = runTest {
        val suggested = System.currentTimeMillis() + 3_600_000
        repository.reminderSuggestionFactory = { id ->
            LocalReminderSuggestion(id, 1, suggested, "下午三点")
        }
        model.createSession()
        model.updateInput("明天下午三点提醒我交周报")
        model.send()
        advanceUntilIdle()
        val message = model.uiState.value.messages.single()

        model.openSuggestedReminder(message.id)

        assertEquals(suggested, model.uiState.value.reminderTarget?.suggestedAt)
        assertEquals(message.id, model.uiState.value.reminderTarget?.messageId)
        assertTrue(repository.reminderState.value.isEmpty())

        val custom = suggested + 1_800_000
        model.saveReminder(custom)
        advanceUntilIdle()

        assertEquals(custom, repository.reminderState.value.single().triggerAt)
        assertNull(model.uiState.value.reminderTarget)
        assertTrue(model.uiState.value.reminderSuggestions.isEmpty())
    }

    @Test fun editingMessageDropsLocalSuggestion() = runTest {
        repository.reminderSuggestionFactory = { id ->
            LocalReminderSuggestion(id, 1, System.currentTimeMillis() + 3_600_000, "下午三点")
        }
        model.createSession()
        model.updateInput("明天下午三点提醒我")
        model.send()
        advanceUntilIdle()
        val first = model.uiState.value.messages.single()
        assertEquals(1, model.uiState.value.reminderSuggestions.size)

        model.requestEdit(first)
        model.updateInput("明天下午四点提醒我")
        model.send()
        advanceUntilIdle()

        assertTrue(model.uiState.value.reminderSuggestions.isEmpty())
    }

    @Test fun openingSuggestionForDeletedMessageDoesNothing() = runTest {
        repository.reminderSuggestionFactory = { id ->
            LocalReminderSuggestion(id, 1, System.currentTimeMillis() + 60_000, "下午三点")
        }
        model.createSession()
        model.updateInput("明天下午三点提醒我交周报")
        model.send()
        advanceUntilIdle()
        val message = model.uiState.value.messages.single()
        repository.deleteMessages(setOf(message.id))
        advanceUntilIdle()

        model.openSuggestedReminder(message.id)
        advanceUntilIdle()

        assertTrue(repository.reminderState.value.isEmpty())
        assertNull(model.uiState.value.reminderTarget)
        assertTrue(model.uiState.value.reminderSuggestions.isEmpty())
    }

    @Test fun expiredLocalSuggestionIsRejectedWhenOpening() = runTest {
        repository.reminderSuggestionFactory = { id ->
            LocalReminderSuggestion(id, 1, System.currentTimeMillis() - 1_000, "已过期")
        }
        model.createSession()
        model.updateInput("昨天下午三点提醒我交周报")
        model.send()
        advanceUntilIdle()
        val message = model.uiState.value.messages.single()

        model.openSuggestedReminder(message.id)
        advanceUntilIdle()

        assertTrue(repository.reminderState.value.isEmpty())
        assertNull(model.uiState.value.reminderTarget)
        assertTrue(model.uiState.value.reminderSuggestions.isEmpty())
    }

    @Test fun savedDirectoryStartsInRestoringStateAndClearsAfterRestore() = runTest {
        repository.savedDirectory = true
        repository.directoryReady.value = false
        repository.restoreResult = true
        repository.restoreGate = CompletableDeferred()
        val restoring = ChatViewModel(repository)

        assertTrue(restoring.uiState.value.restoringDirectory)
        assertFalse(restoring.uiState.value.directoryReady)

        repository.restoreGate?.complete(Unit)
        advanceUntilIdle()

        assertFalse(restoring.uiState.value.restoringDirectory)
        assertTrue(restoring.uiState.value.directoryReady)
    }

    @Test fun noSavedDirectoryDoesNotEnterRestoringState() {
        assertFalse(model.uiState.value.restoringDirectory)
    }

    @Test fun reminderListShowsUpcomingRemindersAndCancelRemovesLastOne() = runTest {
        model.createSession()
        val sessionId = model.uiState.value.activeSessionId!!
        repository.scheduledReminderState.value = listOf(
            ReminderPreview(
                id = "r1",
                sessionId = sessionId,
                messageId = null,
                triggerAt = System.currentTimeMillis() + 600_000,
                text = "买牛奶",
                sessionTitle = "新对话",
            ),
            ReminderPreview(
                id = "r2",
                sessionId = sessionId,
                messageId = null,
                triggerAt = System.currentTimeMillis() - 1_000,
                text = "已过期",
                sessionTitle = "新对话",
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("r1"), model.uiState.value.scheduledReminders.map { it.id })

        model.openReminderList()
        assertTrue(model.uiState.value.reminderListVisible)

        model.cancelReminder("r1")
        advanceUntilIdle()

        assertTrue(model.uiState.value.scheduledReminders.isEmpty())
        assertFalse(model.uiState.value.reminderListVisible)
    }

    @Test fun openingReminderSwitchesToItsSession() = runTest {
        model.createSession()
        val targetSession = model.uiState.value.activeSessionId!!
        model.createSession()
        advanceUntilIdle()
        repository.scheduledReminderState.value = listOf(
            ReminderPreview(
                id = "r1",
                sessionId = targetSession,
                messageId = null,
                triggerAt = System.currentTimeMillis() + 600_000,
                text = "买牛奶",
                sessionTitle = "新对话",
            ),
        )
        advanceUntilIdle()
        model.openReminderList()

        model.openReminder("r1")
        advanceUntilIdle()

        assertEquals(targetSession, model.uiState.value.activeSessionId)
        assertFalse(model.uiState.value.reminderListVisible)
    }

    @Test fun goToQuotedMessageFocusesOriginalMessage() = runTest {
        model.createSession()
        model.updateInput("原文")
        model.send()
        model.updateInput("回复")
        model.send()
        advanceUntilIdle()
        val original = model.uiState.value.messages.first()

        model.goToMessage(original.id)
        runCurrent()

        assertEquals(original.id, model.uiState.value.scrollTargetId)
        assertEquals(original.id, model.uiState.value.highlightMessageId)
    }

    @Test fun goToMissingQuotedMessageShowsNotice() = runTest {
        model.createSession()
        model.updateInput("原文")
        model.send()
        advanceUntilIdle()

        model.goToMessage("missing")
        runCurrent()

        assertEquals("原消息已删除", model.uiState.value.notice)
        assertNull(model.uiState.value.scrollTargetId)
    }
}

private class FakeChatRepository : ChatRepository {
    val sessionState = MutableStateFlow<List<SessionRecord>>(emptyList())
    private val messageState = MutableStateFlow<List<MessageRecord>>(emptyList())
    private var counter = 0
    var sendGate: CompletableDeferred<Unit>? = null
    var sendError: Exception? = null
    var cleanupError: SessionAttachmentCleanupException? = null

    override val directoryReady = MutableStateFlow(true)
    override val sessions: Flow<List<SessionRecord>> = sessionState
    override val unreadOrganizationSessions = MutableStateFlow<Set<String>>(emptySet())
    override val organizingSessions = MutableStateFlow<Set<String>>(emptySet())
    override val organizationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    var organizeCalls = 0
    var reminderSuggestionFactory: ((String) -> LocalReminderSuggestion?)? = null
    var detectReminderCalls = 0
    var mergeCalls = 0
    var forwardCalls = 0
    val deletedAttachments = mutableListOf<String>()

    override fun messages(sessionId: String): Flow<List<MessageRecord>> =
        messageState.map { messages -> messages.filter { it.sessionId == sessionId } }

    override fun sessionDocument(sessionId: String): Flow<SessionDocumentRecord?> = flowOf(null)

    override fun quotes(sessionId: String): Flow<Map<String, QuoteRecord>> = flowOf(emptyMap())

    override fun reactions(sessionId: String): Flow<Map<String, List<ReactionRecord>>> = flowOf(emptyMap())

    val reminderState = MutableStateFlow<List<ReminderRecord>>(emptyList())
    val scheduledReminderState = MutableStateFlow<List<ReminderPreview>>(emptyList())
    var savedDirectory = false
    var restoreResult: Boolean? = null
    var restoreGate: CompletableDeferred<Unit>? = null
    var cancelReminderCalls = 0
    var rescheduleCalls = 0
    var reminderExact = true
    var reminderAllowed = true

    override fun reminders(sessionId: String): Flow<List<ReminderRecord>> =
        reminderState.map { list -> list.filter { it.sessionId == sessionId } }

    override fun scheduledReminders(): Flow<List<ReminderPreview>> = scheduledReminderState

    override suspend fun saveReminder(
        sessionId: String,
        messageId: String?,
        triggerAt: Long,
    ): Result<ReminderSaveOutcome> {
        val record = ReminderRecord(
            id = "r${++counter}",
            sessionId = sessionId,
            messageId = messageId,
            triggerAt = triggerAt,
            createdAt = 1L,
            updatedAt = 1L,
        )
        reminderState.update { it + record }
        return Result.success(
            ReminderSaveOutcome(record, exact = reminderExact, notificationsAllowed = reminderAllowed),
        )
    }

    override suspend fun cancelReminder(id: String): Result<Unit> {
        cancelReminderCalls++
        reminderState.update { list ->
            list.map { if (it.id == id) it.copy(status = REMINDER_CANCELLED) else it }
        }
        scheduledReminderState.update { list -> list.filterNot { it.id == id } }
        return Result.success(Unit)
    }

    override suspend fun rescheduleReminders() {
        rescheduleCalls++
    }

    override suspend fun reminderDelivery(id: String): ReminderDelivery? = null

    override suspend fun markReminderFired(id: String): Boolean = false

    override fun messageSources(sessionId: String): Flow<Map<String, String>> = flowOf(emptyMap())

    override suspend fun mergeMessages(sessionId: String, messageIds: Set<String>): String {
        mergeCalls++
        val selected = messageState.value.filter { it.sessionId == sessionId && it.id in messageIds }
        val id = "m${++counter}"
        messageState.update { messages ->
            messages.filterNot { it.id in messageIds } + MessageRecord(
                id, sessionId, "user", selected.joinToString("\n") { it.currentContent },
                createdAt = selected.minOf { it.createdAt },
            )
        }
        return id
    }

    override suspend fun forwardMessages(
        sourceSessionId: String,
        messageIds: Set<String>,
        targetSessionId: String,
    ): Int {
        forwardCalls++
        val selected = messageState.value.filter { it.sessionId == sourceSessionId && it.id in messageIds }
        val base = selected.maxOfOrNull { it.createdAt } ?: 0L
        val copies = selected.mapIndexed { index, message ->
            MessageRecord(
                id = "f${++counter}",
                sessionId = targetSessionId,
                role = message.role,
                rawContent = message.currentContent,
                createdAt = base + 100 + index,
            )
        }
        messageState.update { it + copies }
        return copies.size
    }

    override suspend fun detectReminder(messageId: String): LocalReminderSuggestion? {
        detectReminderCalls++
        val message = messageState.value.firstOrNull { it.id == messageId } ?: return null
        val suggestion = reminderSuggestionFactory?.invoke(messageId) ?: return null
        return suggestion.copy(contentVersion = message.contentVersion)
    }

    override suspend fun organizeSession(sessionId: String): Result<Boolean> {
        organizeCalls++
        organizingSessions.value = organizingSessions.value + sessionId
        return Result.success(true)
    }

    override suspend fun markOrganizationViewed(sessionId: String) {
        unreadOrganizationSessions.value = unreadOrganizationSessions.value - sessionId
    }

    override suspend fun editMessage(messageId: String, text: String) {
        messageState.update { messages ->
            messages.map { if (it.id == messageId) it.copy(currentContent = text, contentVersion = it.contentVersion + 1) else it }
        }
    }

    override suspend fun deleteMessages(messageIds: Set<String>) {
        messageState.update { messages -> messages.filterNot { it.id in messageIds } }
    }

    override suspend fun setBubbleFormat(messageId: String, formatId: String?) = Unit

    override suspend fun editHistory(messageId: String): List<EditRecord> = emptyList()

    override suspend fun selectDirectory(treeUri: String): Result<Unit> = Result.success(Unit)

    override fun hasSavedDirectory(): Boolean = savedDirectory

    override suspend fun restoreSavedDirectory(): Boolean? {
        restoreGate?.await()
        return restoreResult?.also { ok -> if (ok) directoryReady.value = true }
    }

    override suspend fun createSession(title: String): String {
        val id = "s${++counter}"
        sessionState.update { sessions ->
            sessions + SessionRecord(id, title, createdAt = counter.toLong(), updatedAt = counter.toLong())
        }
        return id
    }

    override suspend fun renameSession(id: String, title: String) {
        sessionState.update { sessions ->
            sessions.map { if (it.id == id) it.copy(title = title) else it }
        }
    }

    override suspend fun deleteSession(id: String) {
        sessionState.update { sessions -> sessions.filterNot { it.id == id } }
        cleanupError?.let { throw it }
    }

    override suspend fun markSessionOpened(id: String) {
        sessionState.update { sessions ->
            sessions.map { if (it.id == id) it.copy(lastOpenedAt = it.lastOpenedAt + 1) else it }
        }
    }

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment>,
        quotedMessageId: String?,
    ): String? {
        sendGate?.await()
        sendError?.let { throw it }
        if (text.isBlank() && attachments.isEmpty()) return null
        val id = "m${++counter}"
        messageState.update { messages ->
            messages + MessageRecord(
                id, sessionId, "user", text,
                createdAt = counter.toLong(),
                attachments = attachments,
            )
        }
        return id
    }

    override suspend fun importImage(uri: Uri): Result<MessageAttachment> {
        val id = "attachment${++counter}"
        return Result.success(
            MessageAttachment(id, "image", "attachments/$id.jpg", "image/jpeg", 3, 0, 1),
        )
    }

    override suspend fun importVoiceRecording(file: File, durationMs: Long): Result<MessageAttachment> {
        file.delete()
        val id = "attachment${++counter}"
        return Result.success(
            MessageAttachment(id, "audio", "attachments/$id.m4a", "audio/mp4", 3, durationMs, 1),
        )
    }

    override suspend fun deleteAttachment(relativePath: String) {
        deletedAttachments += relativePath
    }

    override fun attachmentUri(relativePath: String): Uri? = null
}
