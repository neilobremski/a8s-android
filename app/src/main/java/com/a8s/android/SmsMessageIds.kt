package com.a8s.android

import android.content.Context

/** Persistent, monotonically increasing IDs used in long-SMS part headers. */
object SmsMessageIds {
    @Synchronized
    fun next(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = nextAfter(prefs.getInt(KEY_LAST_ID, 0))
        prefs.edit().putInt(KEY_LAST_ID, value).apply()
        return value
    }

    internal fun nextAfter(current: Int): Int = if (current == Int.MAX_VALUE) 1 else current + 1

    private const val PREFS_NAME = "a8s_sms_sequence"
    private const val KEY_LAST_ID = "last_message_id"
}
