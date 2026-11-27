package com.chatnotebook.app.security

import android.content.Context
import java.security.KeyStore

/** 清除历史版本留下的服务配置、凭据与删除墓碑；可重复执行。 */
object LegacyLlmCleanup {
    fun run(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREF_CREDENTIALS, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(PREF_LLM_STATE, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(PREF_ENVIRONMENT, Context.MODE_PRIVATE).edit()
            .remove(KEY_MOCK_MODE)
            .remove(KEY_DEFAULT_CONFIG)
            .apply()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private const val PREF_CREDENTIALS = "credentials"
    private const val PREF_LLM_STATE = "llm_config_state"
    private const val PREF_ENVIRONMENT = "record_environment"
    private const val KEY_MOCK_MODE = "mock_mode"
    private const val KEY_DEFAULT_CONFIG = "default_config"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "chat_notebook_credentials"
}