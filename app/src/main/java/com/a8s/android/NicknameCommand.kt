package com.a8s.android

import java.util.Locale

/** Pure parser for the unambiguous `/nicknames` command grammar. */
object NicknameCommand {

    const val USAGE: String =
        "usage: /nicknames add <nickname words> for <agent> | replace <nickname words> for <agent> | " +
            "remove <nickname> | list [for <agent>] | enable | disable | status"

    sealed class Action {
        data class Add(val nickname: String, val agent: String, val replace: Boolean) : Action()
        data class Remove(val nickname: String) : Action()
        data class ListFor(val agent: String?) : Action()
        data class SetEnabled(val enabled: Boolean) : Action()
        object Status : Action()
        data class Invalid(val reason: String) : Action()
    }

    fun parse(args: List<String>): Action {
        if (args.isEmpty()) return Action.ListFor(null)
        return when (args[0].lowercase(Locale.ROOT)) {
            "add" -> parseAdd(args, replace = false)
            "replace" -> parseAdd(args, replace = true)
            "remove", "rm" -> parseRemove(args)
            "list" -> parseList(args)
            "enable" -> exactArity(args, 1) { Action.SetEnabled(true) }
            "disable" -> exactArity(args, 1) { Action.SetEnabled(false) }
            "status" -> exactArity(args, 1) { Action.Status }
            else -> Action.Invalid("unknown action '${args[0]}'")
        }
    }

    fun conflictsWithCanonicalName(nickname: String, device: String, agents: Set<String>): Boolean {
        val normalized = nickname.trim().lowercase(Locale.ROOT)
        if (device.trim().lowercase(Locale.ROOT) == normalized) return true
        return agents.any { it.trim().lowercase(Locale.ROOT) == normalized }
    }

    private fun parseAdd(args: List<String>, replace: Boolean): Action {
        val delimiter = args.indexOfLast { it.equals("for", ignoreCase = true) }
        if (delimiter < 2 || delimiter != args.lastIndex - 1) {
            return Action.Invalid("expected '${args[0]} <nickname> for <agent>'")
        }
        val nickname = normalizeNickname(args.subList(1, delimiter).joinToString(" "))
            ?: return Action.Invalid("nickname must contain letters, numbers, spaces, '_' or '-'")
        if (nickname in RESERVED) {
            return Action.Invalid("'$nickname' is reserved by /nicknames")
        }
        val agent = normalizeTarget(args[delimiter + 1])
            ?: return Action.Invalid("agent must be one A8S address token")
        return Action.Add(nickname, agent, replace)
    }

    private fun parseRemove(args: List<String>): Action {
        if (args.size < 2) return Action.Invalid("expected '${args[0]} <nickname>'")
        val nickname = normalizeNickname(args.drop(1).joinToString(" "))
            ?: return Action.Invalid("nickname must contain letters, numbers, spaces, '_' or '-'")
        return Action.Remove(nickname)
    }

    private fun parseList(args: List<String>): Action {
        if (args.size == 1) return Action.ListFor(null)
        if (args.size != 3 || !args[1].equals("for", ignoreCase = true)) {
            return Action.Invalid("expected 'list' or 'list for <agent>'")
        }
        val agent = normalizeTarget(args[2])
            ?: return Action.Invalid("agent must be one A8S address token")
        return Action.ListFor(agent)
    }

    private fun exactArity(args: List<String>, size: Int, action: () -> Action): Action =
        if (args.size == size) action() else Action.Invalid("'${args[0]}' takes no arguments")

    internal fun normalizeNickname(raw: String): String? {
        val normalized = raw.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { word -> word.trim { it in PUNCTUATION } }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return normalized.takeIf { it.isNotEmpty() && it.split(" ").all(NAME::matches) }
    }

    private fun normalizeTarget(raw: String): String? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { ADDRESS.matches(it) }
    }

    private val NAME = Regex("[a-z0-9][a-z0-9_-]*")
    private val ADDRESS = Regex("[a-z0-9][a-z0-9_-]*(?::[a-z0-9][a-z0-9_-]*)*")
    private val RESERVED = setOf("add", "replace", "remove", "rm", "list", "enable", "disable", "status", "for")
    private val PUNCTUATION = setOf('.', ',', '!', '?', ':', ';', '\'', '"', '(', ')', '[', ']')
}
