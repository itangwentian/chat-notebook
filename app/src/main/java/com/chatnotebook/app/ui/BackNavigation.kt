package com.chatnotebook.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Root-level back handling: settings and results return to the chat screen.
 * The chat screen registers nothing, so its back gesture exits the app.
 */
@Composable
fun AppBackHandler(
    settingsOpen: Boolean,
    resultOpen: Boolean,
    onCloseSettings: () -> Unit,
    onCloseResults: () -> Unit,
) {
    BackHandler(enabled = settingsOpen, onBack = onCloseSettings)
    BackHandler(enabled = !settingsOpen && resultOpen, onBack = onCloseResults)
}
