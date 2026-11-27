package com.chatnotebook.app.ui

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.chatnotebook.app.data.sync.LanDevice
import com.chatnotebook.app.data.sync.LanSyncManager
import com.chatnotebook.app.data.sync.LanSyncStatus
import com.chatnotebook.app.data.sync.PairingQr
import com.chatnotebook.app.data.sync.PairingScanException
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LanSyncPanel(manager: LanSyncManager, onPickDirectory: () -> Unit) {
    val status by manager.status.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    DisposableEffect(manager) {
        onDispose {
            pendingPhoto?.delete()
            manager.cancelPairing()
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val photo = pendingPhoto
        pendingPhoto = null
        if (captured && photo != null) {
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { PairingQr.scan(loadBitmap(context.contentResolver, Uri.fromFile(photo))) }
                    }
                    result.onSuccess(manager::join).onFailure { error ->
                        manager.message(scanMessage(error, fromCamera = true))
                    }
                } finally {
                    photo.delete()
                }
            }
        } else {
            photo?.delete()
        }
    }
    val openCamera: () -> Unit = {
        runCatching {
            val directory = File(context.cacheDir, "pairing").apply { mkdirs() }
            val photo = File.createTempFile("pair-", ".jpg", directory)
            pendingPhoto = photo
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.message-share", photo)
            camera.launch(uri)
        }.onFailure {
            pendingPhoto?.delete()
            pendingPhoto = null
            manager.message("相机无法打开，可从图片识别配对二维码")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else manager.message("相机权限未授权，可从图片识别配对二维码")
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { image ->
        if (image != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { PairingQr.scan(loadBitmap(context.contentResolver, image)) }
                }.onSuccess(manager::join).onFailure { error ->
                    manager.message(scanMessage(error, fromCamera = false))
                }
            }
        }
    }
    LanSyncSection(
        status,
        onShow = { manager.showPairing() },
        onScan = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else permission.launch(Manifest.permission.CAMERA)
        },
        onPick = { pickImage.launch("image/*") },
        onCancel = manager::cancelPairing,
        onPause = manager::pause,
        onUnlink = { manager.unlink(it) },
        onPickDirectory = onPickDirectory,
    )
}

@Composable
internal fun LanSyncSection(
    status: LanSyncStatus,
    onShow: () -> Unit,
    onScan: () -> Unit,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onPause: (Boolean) -> Unit,
    onUnlink: (String) -> Unit,
    onPickDirectory: () -> Unit,
) {
    var pendingRemove by remember { mutableStateOf<LanDevice?>(null) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("局域网同步", style = MaterialTheme.typography.titleMedium)
            if (!status.directoryReady) {
                Text("请先选择记录目录")
            } else {
                if (status.peers.isNotEmpty()) {
                    status.peers.forEach { device ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("自己的设备 · ${device.name}", Modifier.weight(1f))
                            TextButton(onClick = { pendingRemove = device }) { Text("移除设备") }
                        }
                    }
                    status.lastSuccess?.let {
                        val localTime = remember(it) { DateFormat.getDateTimeInstance().format(Date(it)) }
                        Text("最近成功同步 $localTime", Modifier.testTag("lan_last_success"))
                    }
                    TextButton(onClick = { onPause(!status.paused) }) {
                        Text(if (status.paused) "继续同步" else "暂停同步")
                    }
                }
                if (status.joining || status.qr != null) {
                    TextButton(onClick = onCancel) { Text("取消配对") }
                } else {
                    Text(
                        if (status.peers.isEmpty()) "两台设备都打开应用并连接同一局域网后，笔记会自动同步。"
                        else "添加设备：在新设备上扫码，或让新设备扫描本机二维码。",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onShow) { Text("显示二维码") }
                        TextButton(onClick = onScan) { Text("扫码配对") }
                        TextButton(onClick = onPick) { Text("从图片识别") }
                    }
                }
                if (status.message.isNotBlank()) Text(status.message, Modifier.testTag("lan_status"))
                if (status.peers.isNotEmpty()) Text("更换目录后，所选目录会自动与已配对设备同步。")
            }
            TextButton(onClick = onPickDirectory) {
                Text(if (status.directoryReady) "更换记录目录" else "选择记录目录")
            }
        }
    }
    val qr = status.qr
    if (qr != null) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("配对二维码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("只用自己的设备扫描。扫码成功后，两台设备会长期信任彼此并自动同步笔记。二维码两分钟内有效。")
                    Image(
                        remember(qr) { PairingQr.display(qr).asImageBitmap() },
                        contentDescription = "局域网配对二维码",
                        modifier = Modifier.size(220.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("取消配对") } },
        )
    }
    if (pendingRemove != null) {
        val device = pendingRemove!!
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除自己的设备？") },
            text = { Text("将解除与「${device.name}」的配对，此设备上已保存的笔记会保留。") },
            confirmButton = {
                TextButton(
                    onClick = { pendingRemove = null; onUnlink(device.fingerprint) },
                    modifier = Modifier.testTag("lan_confirm_unlink"),
                ) { Text("移除设备") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } },
        )
    }
}

private fun loadBitmap(resolver: ContentResolver, uri: Uri, maxDimension: Int = 1600): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("无法读取图片")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("无法读取图片")
}

private fun scanMessage(error: Throwable, fromCamera: Boolean): String = when (error) {
    is PairingScanException.NotFound -> if (fromCamera) {
        error.message ?: "未识别到二维码，请重试或从图片识别"
    } else {
        "图片中未找到有效配对二维码"
    }
    is PairingScanException.InvalidContent -> error.message ?: "二维码内容无效，请使用本应用的配对二维码"
    is PairingScanException.VersionMismatch -> error.message ?: "二维码配对版本不兼容，请更新两台设备"
    else -> if (fromCamera) "识别失败，请重试或从图片识别" else "无法读取图片，请重新选择"
}
