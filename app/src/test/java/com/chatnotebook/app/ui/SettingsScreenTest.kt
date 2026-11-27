package com.chatnotebook.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.chatnotebook.app.data.sync.LanDevice
import com.chatnotebook.app.data.sync.LanSyncStatus
import com.chatnotebook.app.data.sync.PairingTicket
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lanSyncSettingsExposePairingAndShowStatusAndLastSuccess() {
        val sync = mutableStateOf(LanSyncStatus(directoryReady = true))
        var paired = false
        var scanned = false
        var unlinked = false
        var pickedDirectory = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(syncContent = {
                    LanSyncSection(
                        sync.value,
                        onShow = { paired = true },
                        onScan = { scanned = true },
                        onPick = {},
                        onCancel = {},
                        onPause = { sync.value = sync.value.copy(paused = it) },
                        onUnlink = { unlinked = true },
                        onPickDirectory = { pickedDirectory = true },
                    )
                })
            }
        }
        compose.onNodeWithText("局域网同步").assertIsDisplayed()
        compose.onNodeWithText("显示二维码").performClick()
        compose.onNodeWithText("扫码配对").performClick()
        assertTrue(paired)
        assertTrue(scanned)
        compose.runOnIdle {
            sync.value = sync.value.copy(qr = PairingTicket("a".repeat(64)) { 0L }.offer)
        }
        compose.onNodeWithText("配对二维码").assertIsDisplayed()
        compose.onNodeWithText(
            "只用自己的设备扫描。扫码成功后，两台设备会长期信任彼此并自动同步笔记。二维码两分钟内有效。",
        ).assertIsDisplayed()
        compose.onNodeWithText("确认配对").assertDoesNotExist()

        compose.runOnIdle {
            sync.value = sync.value.copy(
                qr = null,
                peers = listOf(LanDevice("peer-key", "另一台设备")),
                lastSuccess = 123456789,
                message = "目录写入失败",
            )
        }
        compose.onNodeWithText("自己的设备 · 另一台设备").assertIsDisplayed()
        compose.onNodeWithText("扫码配对").assertIsDisplayed()
        compose.onNodeWithText("更换记录目录").performScrollTo().performClick()
        assertTrue(pickedDirectory)
        compose.onNodeWithTag("lan_status").assertExists()
        compose.onNodeWithTag("lan_last_success").assertExists()
        compose.onNodeWithText("暂停同步").performScrollTo().performClick()
        compose.onNodeWithText("继续同步").performScrollTo().performClick()
        compose.onNodeWithText("移除设备").performScrollTo().performClick()
        compose.onNodeWithText("移除自己的设备？").assertIsDisplayed()
        compose.onNodeWithTag("lan_confirm_unlink").performClick()
        assertTrue(unlinked)

        compose.runOnIdle {
            sync.value = sync.value.copy(lastSuccess = null, message = "记录目录已切换")
        }
        compose.onNodeWithText("自己的设备 · 另一台设备").assertExists()
        compose.onNodeWithTag("lan_status").assertTextContains("记录目录已切换")
    }

    @Test
    fun settingsNoLongerExposeModelConfiguration() {
        compose.setContent {
            MaterialTheme { SettingsScreen() }
        }

        compose.onNodeWithText("模型设置").assertDoesNotExist()
        compose.onNodeWithText("API Key").assertDoesNotExist()
        compose.onNodeWithText("新增模型").assertDoesNotExist()
        compose.onNodeWithTag("about_section").assertExists()
    }
}