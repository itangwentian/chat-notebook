package com.chatnotebook.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chatnotebook.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardMessagesSheet(state: ChatUiState, actions: ChatActions) {
    val sessions = state.sessions.filter { it.id != state.activeSessionId && !it.isDeleted }
    val messages = remember(state.messages, state.selectedMessages, state.activeSessionId) {
        state.selectedForwardMessages()
    }
    val format = remember(messages) { MessageSharing.format(messages) }
    val context = LocalContext.current
    var apps by remember(context, format) { mutableStateOf<List<MessageShareTarget>?>(null) }
    LaunchedEffect(context, format) {
        apps = MessageSharing.findTargets(context, format)
    }
    val preview = remember(messages) {
        messages.asSequence().map { it.currentContent }.filter { it.isNotBlank() }.joinToString("\n")
    }
    val attachmentCount = messages.sumOf { it.attachments.size }
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = { if (!state.busy) actions.onCancelForwardMessages() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = !state.busy,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                .testTag("forward_sheet"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.forward_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = actions.onCancelForwardMessages, enabled = !state.busy) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            if (preview.isNotBlank() || attachmentCount > 0) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfaceContainerHigh,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (preview.isNotBlank()) {
                                Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (attachmentCount > 0) {
                                Text(
                                    stringResource(R.string.forward_attachment_count, attachmentCount),
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.forward_apps), style = MaterialTheme.typography.titleSmall) }
            item {
                val targets = apps
                when {
                    targets == null -> Text(stringResource(R.string.forward_loading_apps), color = colors.onSurfaceVariant)
                    targets.isEmpty() -> Text(stringResource(R.string.forward_no_apps), color = colors.onSurfaceVariant)
                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(targets, key = { it.component.flattenToString() }) { app ->
                            Surface(
                                onClick = { actions.onShareForwardMessages(app.component) },
                                enabled = !state.busy,
                                shape = RoundedCornerShape(16.dp),
                                color = colors.surface,
                                modifier = Modifier.width(74.dp)
                                    .testTag("forward_app_${app.component.flattenToShortString()}"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Image(
                                        bitmap = remember(app.icon) { app.icon.asImageBitmap() },
                                        contentDescription = null,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(13.dp)),
                                    )
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.forward_chats), style = MaterialTheme.typography.titleSmall) }
            if (sessions.isEmpty()) {
                item { Text(stringResource(R.string.forward_no_chats), color = colors.onSurfaceVariant) }
            }
            items(sessions, key = { it.id }) { session ->
                Surface(
                    onClick = { actions.onConfirmForwardMessages(session.id) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("forward_target_${session.id}"),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceContainerLow,
                ) {
                    Text(session.title, Modifier.padding(14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
