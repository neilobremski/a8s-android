package com.a8s.android

import android.content.Context
import java.util.Locale

object NicknamesManager {
    private const val PREFS_NAME = "a8s_nicknames"
    private const val STATE_PREFS_NAME = "a8s_nicknames_state"
    private const val ENABLED_KEY = "enabled"

    data class Resolution(
        val input: String,
        val normalized: String,
        val resolved: String,
        val matched: Boolean,
        val enabled: Boolean,
    )

    fun putNickname(context: Context, nickname: String, realAgent: String, replace: Boolean): Boolean {
        val normalizedNickname = nickname.lowercase(Locale.ROOT)
        val normalizedAgent = realAgent.lowercase(Locale.ROOT)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!replace && prefs.contains(normalizedNickname)) return false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(normalizedNickname, normalizedAgent)
            .apply()
        return true
    }

    fun removeNickname(context: Context, nickname: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(nickname.lowercase(Locale.ROOT))
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED_KEY, enabled)
            .apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, true)

    fun resolveDetailed(context: Context, name: String): Resolution {
        val normalized = name.trim().lowercase(Locale.ROOT)
        val enabled = isEnabled(context)
        if (!enabled) return Resolution(name, normalized, normalized, matched = false, enabled = false)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapped = prefs.getString(normalized, null)
        return Resolution(name, normalized, mapped ?: normalized, mapped != null, enabled = true)
    }

    fun getAll(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
    }
}
