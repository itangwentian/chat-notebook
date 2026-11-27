package com.chatnotebook.app.compliance

import android.content.Context

/** 记录用户已同意的隐私政策版本，版本升级后需要重新征得同意。 */
object PrivacyConsent {
    private const val PREFERENCES = "privacy_consent"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    const val CURRENT_VERSION = 2

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= CURRENT_VERSION

    fun accept(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
            .apply()
    }
}
