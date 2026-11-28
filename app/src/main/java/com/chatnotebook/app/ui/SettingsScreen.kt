package com.chatnotebook.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chatnotebook.app.BuildConfig
import com.chatnotebook.app.R
import com.chatnotebook.app.data.ChatRepository
import com.chatnotebook.app.data.PersistentChatRepository

@Composable
fun SettingsRoute(repository: ChatRepository, onBack: () -> Unit, onPickDirectory: () -> Unit) {
    SettingsScreen(
        syncContent = {
            (repository as? PersistentChatRepository)?.let { LanSyncPanel(it.lanSync, onPickDirectory) }
        },
        updateContent = { UpdatePanel() },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    syncContent: @Composable () -> Unit = {},
    updateContent: @Composable () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val viewing = legalDocument
    BackHandler(enabled = viewing != null) { legalDocument = null }
    if (viewing != null) {
        LegalDocumentScreen(viewing) { legalDocument = null }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            syncContent()
            updateContent()
            AboutSection(
                onOpenPrivacy = { legalDocument = LegalDocument.PrivacyPolicy },
                onOpenAgreement = { legalDocument = LegalDocument.UserAgreement },
            )
        }
    }
}

@Composable
private fun AboutSection(
    onOpenPrivacy: () -> Unit,
    onOpenAgreement: () -> Unit,
) {
    val icpNumber = stringResource(R.string.app_icp_number)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 24.dp)
            .testTag("about_section"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.wechat_qr),
                    contentDescription = "微信二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("about_wechat_qr"),
                )
                Spacer(modifier = Modifier.width(20.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("聊天笔记", style = MaterialTheme.typography.titleLarge)
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    Text("开发者：唐闻天", style = MaterialTheme.typography.bodySmall)
                    Text("邮箱：v-vetang@qq.com", style = MaterialTheme.typography.bodySmall)
                    if (icpNumber.isNotBlank()) {
                        Text("备案号：$icpNumber", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(
                    onClick = onOpenPrivacy,
                    modifier = Modifier.testTag("about_privacy"),
                ) {
                    Text("隐私政策")
                }
                TextButton(
                    onClick = onOpenAgreement,
                    modifier = Modifier.testTag("about_agreement"),
                ) {
                    Text("用户协议")
                }
            }
        }
    }
}