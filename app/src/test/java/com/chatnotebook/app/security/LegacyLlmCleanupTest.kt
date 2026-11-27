package com.chatnotebook.app.security

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LegacyLlmCleanupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun clearsLegacyCredentialsAndModelState() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit()
            .putString("api_key", "secret").apply()
        context.getSharedPreferences("llm_config_state", Context.MODE_PRIVATE).edit()
            .putString("active", "model-1").apply()
        context.getSharedPreferences("record_environment", Context.MODE_PRIVATE).edit()
            .putBoolean("mock_mode", true)
            .putString("default_config", "model-1")
            .putString("tree_uri", "content://tree/notes")
            .apply()

        LegacyLlmCleanup.run(context)

        assertTrue(context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(context.getSharedPreferences("llm_config_state", Context.MODE_PRIVATE).all.isEmpty())
        val environment = context.getSharedPreferences("record_environment", Context.MODE_PRIVATE)
        assertFalse(environment.contains("mock_mode"))
        assertFalse(environment.contains("default_config"))
        assertTrue(environment.contains("tree_uri"))
    }

    @Test fun repeatedCleanupIsSafe() {
        LegacyLlmCleanup.run(context)
        LegacyLlmCleanup.run(context)

        assertTrue(context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.isEmpty())
    }
}