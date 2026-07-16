package com.a8s.android

import java.util.Locale

/** Pure parser for the unambiguous `/nicknames` command grammar. */
object NicknameCommand {

    const val USAGE: String =
        "usage: /nicknames add <nickname> for <agent> | replace <nickname> for <agent> | " +
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

    private fun parseAdd(args: List<String>, replace: Boolean): Action {
        if (args.size != 4 || !args[2].equals("for", ignoreCase = true)) {
            return Action.Invalid("expected '${args[0]} <nickname> for <agent>'")
        }
        val nickname = normalizeNickname(args[1])
            ?: return Action.Invalid("nickname must be one lowercase A8S name token")
        if (nickname in RESERVED) {
            return Action.Invalid("'$nickname' is reserved by /nicknames")
        }
        val agent = normalizeTarget(args[3])
            ?: return Action.Invalid("agent must be one A8S address token")
        return Action.Add(nickname, agent, replace)
    }

    private fun parseRemove(args: List<String>): Action {
        if (args.size != 2) return Action.Invalid("expected '${args[0]} <nickname>'")
        val nickname = normalizeNickname(args[1])
            ?: return Action.Invalid("nickname must be one lowercase A8S name token")
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

    private fun normalizeNickname(raw: String): String? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { NAME.matches(it) }
    }

    private fun normalizeTarget(raw: String): String? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { ADDRESS.matches(it) }
    }

    private val NAME = Regex("[a-z0-9][a-z0-9_-]*")
    private val ADDRESS = Regex("[a-z0-9][a-z0-9_-]*(?::[a-z0-9][a-z0-9_-]*)*")
    private val RESERVED = setOf("add", "replace", "remove", "rm", "list", "enable", "disable", "status", "for")
}
