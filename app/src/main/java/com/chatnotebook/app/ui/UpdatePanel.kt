package com.chatnotebook.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.chatnotebook.app.BuildConfig
import com.chatnotebook.app.R
import com.chatnotebook.app.update.AppUpdate
import com.chatnotebook.app.update.AppUpdater
import java.io.File
import kotlinx.coroutines.launch

/**
 * 设置页的更新入口：进入设置时静默检查 GitHub 最新版本，
 * 只有地址可访问且存在新版本时才显示“点击更新”。
 */
@Composable
internal fun UpdatePanel(
    checker: suspend () -> AppUpdate? = { AppUpdater().latest(BuildConfig.VERSION_NAME) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { update = checker() }

    val available = update ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
            .testTag("update_section"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.update_available, available.version),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.update_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = {
                    scope.launch {
                        downloading = true
                        progress = 0
                        val result = runCatching {
                            AppUpdater().download(available, File(context.cacheDir, "updates")) {
                                progress = it
                            }
                        }
                        downloading = false
                        result
                            .onSuccess { installApk(context, it) }
                            .onFailure {
                                Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT)
                                    .show()
                            }
                    }
                },
                enabled = !downloading,
                modifier = Modifier.testTag("update_install"),
            ) {
                Text(
                    if (downloading) {
                        stringResource(R.string.update_downloading, progress)
                    } else {
                        stringResource(R.string.update_action)
                    },
                )
            }
        }
    }
}

private fun installApk(context: Context, apk: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, R.string.update_install_permission, Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.message-share", apk)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_SHORT).show() }
}