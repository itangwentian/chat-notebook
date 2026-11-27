package com.chatnotebook.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first

@Composable
internal fun rememberEditInputFocus(
    entryKey: Any?,
    focusBeforeWindow: Boolean = false,
): FocusRequester {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current
    LaunchedEffect(entryKey, window, keyboard, focusBeforeWindow) {
        if (entryKey == null) return@LaunchedEffect
        if (focusBeforeWindow) requester.requestFocus()
        snapshotFlow { window.isWindowFocused }.first { it }
        if (!focusBeforeWindow) requester.requestFocus()
        withFrameNanos { }
        keyboard?.show()
    }
    return requester
}
