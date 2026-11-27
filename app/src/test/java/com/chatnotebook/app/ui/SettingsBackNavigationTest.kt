package com.chatnotebook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
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
class SettingsBackNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun innerLevelWinsOverRootSettingsHandler() {
        var closedSettings = 0
        compose.setContent {
            MaterialTheme {
                AppBackHandler(
                    settingsOpen = true,
                    resultOpen = false,
                    onCloseSettings = { closedSettings++ },
                    onCloseResults = {},
                )
                SettingsScreen(onBack = { closedSettings++ })
            }
        }

        compose.onNodeWithTag("about_privacy").performScrollTo().performClick()
        compose.onNodeWithTag("legal_content").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("legal_content").assertDoesNotExist()
        compose.onNodeWithTag("about_privacy").assertExists()
        assertEquals(0, closedSettings)
    }

    @Test
    fun backClosesLegalDocumentWithoutLeavingSettings() {
        var closedSettings = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(onBack = { closedSettings++ })
            }
        }

        compose.onNodeWithTag("about_privacy").performScrollTo().performClick()
        compose.onNodeWithTag("legal_content").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("legal_content").assertDoesNotExist()
        compose.onNodeWithTag("about_privacy").assertExists()
        assertEquals(0, closedSettings)
    }
}