package com.chatnotebook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backClosesSettingsBeforeLeavingApp() {
        var closedSettings = 0
        var closedResults = 0
        compose.setContent {
            Text("设置")
            AppBackHandler(
                settingsOpen = true,
                resultOpen = false,
                onCloseSettings = { closedSettings++ },
                onCloseResults = { closedResults++ },
            )
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, closedSettings)
        assertEquals(0, closedResults)
    }

    @Test
    fun backClosesResultsBeforeLeavingApp() {
        var closedSettings = 0
        var closedResults = 0
        compose.setContent {
            Text("整理结果")
            AppBackHandler(
                settingsOpen = false,
                resultOpen = true,
                onCloseSettings = { closedSettings++ },
                onCloseResults = { closedResults++ },
            )
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(0, closedSettings)
        assertEquals(1, closedResults)
    }

    @Test
    fun backClosesLegalDocumentOnConsentScreenWithoutExiting() {
        compose.setContent {
            MaterialTheme {
                PrivacyConsentScreen(onAgree = {}, onExit = {})
            }
        }

        compose.onNodeWithTag("open_privacy_policy").performScrollTo().performClick()
        compose.onNodeWithTag("legal_content").assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("legal_content").assertDoesNotExist()
        compose.onNodeWithTag("privacy_agree").assertExists()
    }

    @Test
    fun settingsTakePriorityWhenBothFlagsAreSet() {        var closedSettings = 0
        var closedResults = 0
        compose.setContent {
            Text("设置")
            AppBackHandler(
                settingsOpen = true,
                resultOpen = true,
                onCloseSettings = { closedSettings++ },
                onCloseResults = { closedResults++ },
            )
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, closedSettings)
        assertEquals(0, closedResults)
    }
}
