package com.a8s.android

import android.content.Context

object NicknamesManager {
    private const val PREFS_NAME = "a8s_nicknames"

    fun addNickname(context: Context, realAgent: String, nickname: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(nickname.lowercase(), realAgent)
            .apply()
    }

    fun removeNickname(context: Context, nickname: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(nickname.lowercase())
            .apply()
    }

    fun resolve(context: Context, name: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(name.lowercase(), null) ?: name
    }

    fun getAll(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        return prefs.all as Map<String, String>
    }
}
