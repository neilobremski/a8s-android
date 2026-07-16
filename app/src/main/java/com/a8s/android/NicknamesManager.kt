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

    data class TellResolution(
        val rawTarget: String,
        val normalizedTarget: String,
        val resolved: String,
        val message: String,
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

    fun resolveDetailed(context: Context, name: String, canonicalNames: Set<String> = emptySet()): Resolution =
        resolveFromMappings(name, isEnabled(context), getAll(context), canonicalNames)

    internal fun resolveFromMappings(
        name: String,
        enabled: Boolean,
        mappings: Map<String, String>,
        canonicalNames: Set<String>,
    ): Resolution {
        val normalized = name.trim().lowercase(Locale.ROOT)
        if (!enabled) return Resolution(name, normalized, normalized, matched = false, enabled = false)
        val canonical = canonicalNames.any { it.trim().lowercase(Locale.ROOT) == normalized }
        if (canonical) return Resolution(name, normalized, normalized, matched = false, enabled = true)
        val mapped = mappings[normalized]
        return Resolution(name, normalized, mapped ?: normalized, mapped != null, enabled = true)
    }

    fun resolveTell(
        context: Context,
        args: List<String>,
        canonicalNames: Set<String>,
    ): TellResolution? = resolveTellFromMappings(args, isEnabled(context), getAll(context), canonicalNames)

    internal fun resolveTellFromMappings(
        args: List<String>,
        enabled: Boolean,
        mappings: Map<String, String>,
        canonicalNames: Set<String>,
    ): TellResolution? {
        if (args.size < 2) return null
        val first = args.first().trim().lowercase(Locale.ROOT)
        val canonical = canonicalNames.associateBy { it.trim().lowercase(Locale.ROOT) }
        if (first in canonical) {
            return TellResolution(args.first(), first, first, args.drop(1).joinToString(" "), false, enabled)
        }
        if (enabled) {
            val normalizedArgs = args.map { NicknameCommand.normalizeNickname(it).orEmpty() }
            val match = mappings.keys
                .mapNotNull { alias -> NicknameCommand.normalizeNickname(alias)?.let { it to alias } }
                .sortedByDescending { (normalized) -> normalized.split(" ").size }
                .firstOrNull { (normalized) ->
                    val words = normalized.split(" ")
                    args.size > words.size && normalizedArgs.take(words.size) == words
                }
            if (match != null) {
                val wordCount = match.first.split(" ").size
                return TellResolution(
                    rawTarget = args.take(wordCount).joinToString(" "),
                    normalizedTarget = match.first,
                    resolved = mappings.getValue(match.second).lowercase(Locale.ROOT),
                    message = args.drop(wordCount).joinToString(" "),
                    matched = true,
                    enabled = true,
                )
            }
        }
        return TellResolution(args.first(), first, first, args.drop(1).joinToString(" "), false, enabled)
    }

    fun getAll(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
    }
}
