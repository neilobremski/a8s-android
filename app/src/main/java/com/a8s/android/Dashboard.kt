package com.a8s.android

import android.content.Context
import java.io.File

object Dashboard {
    private const val CONTENT_FILE = "dashboard.html"
    private const val PREFS_NAME = "dashboard"
    private const val KEY_BG_PATH = "bg_path"

    fun getContent(context: Context): String {
        val file = File(context.filesDir, CONTENT_FILE)
        return if (file.exists()) file.readText() else ""
    }

    fun setContent(context: Context, html: String) {
        File(context.filesDir, CONTENT_FILE).writeText(html)
        notifyUpdate()
    }

    fun getBgPath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_BG_PATH, null)
        return if (path != null && File(path).exists()) path else null
    }

    fun setBgPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BG_PATH, path).apply()
        notifyUpdate()
    }

    fun clear(context: Context) {
        File(context.filesDir, CONTENT_FILE).delete()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bgPath = prefs.getString(KEY_BG_PATH, null)
        if (bgPath != null) File(bgPath).delete()
        prefs.edit().remove(KEY_BG_PATH).apply()
        notifyUpdate()
    }

    var onUpdate: (() -> Unit)? = null
    private fun notifyUpdate() { onUpdate?.invoke() }
}
