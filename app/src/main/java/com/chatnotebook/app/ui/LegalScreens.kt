package com.chatnotebook.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class LegalDocument(val title: String, val assetPath: String) {
    PrivacyPolicy("隐私政策", "legal/privacy_policy.txt"),
    UserAgreement("用户协议", "legal/user_agreement.txt"),
}

/** 展示应用内嵌的隐私政策与用户协议全文。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(document: LegalDocument, onBack: () -> Unit) {
    val context = LocalContext.current
    val content = remember(document) {
        runCatching {
            context.assets.open(document.assetPath).bufferedReader().use { it.readText() }
        }.getOrElse { "文档加载失败，请稍后重试。" }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("legal_content"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 首次启动时的隐私政策同意页，未同意前不进入应用。 */
@Composable
fun PrivacyConsentScreen(onAgree: () -> Unit, onExit: () -> Unit) {
    var viewing by remember { mutableStateOf<LegalDocument?>(null) }
    val document = viewing
    BackHandler(enabled = document != null) { viewing = null }
    if (document != null) {
        LegalDocumentScreen(document) { viewing = null }
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "欢迎使用聊天笔记",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "我们非常重视你的个人信息保护。请在使用前阅读并同意《隐私政策》和《用户协议》。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "聊天笔记是一款本地记事工具。局域网同步默认关闭，扫码授权后仅与自己的设备直接交换笔记。" +
                    "麦克风用于主动录音，相机用于主动扫描配对二维码。应用无需账号，不集成广告或统计 SDK。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { viewing = LegalDocument.PrivacyPolicy },
                    modifier = Modifier.testTag("open_privacy_policy"),
                ) {
                    Text("查看《隐私政策》")
                }
                TextButton(
                    onClick = { viewing = LegalDocument.UserAgreement },
                    modifier = Modifier.testTag("open_user_agreement"),
                ) {
                    Text("查看《用户协议》")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth().testTag("privacy_agree"),
            ) {
                Text("同意并继续")
            }
            TextButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().testTag("privacy_exit"),
            ) {
                Text("不同意并退出")
            }
        }
    }
}
