package com.a8s.android

/**
 * Parser for `/macro <step1> | <step2> | …` input. Pure Kotlin so the
 * grammar is unit-tested without an Android Context — the dispatch
 * logic in `CmdMacro` consumes a `List<MacroStep>` produced here.
 *
 * Limitations (documented):
 * - Steps are split on `|`. Pipe inside an `input <text>` segment will
 *   be treated as a step boundary; there is no escape syntax.
 * - All numeric args (x, y, ms) are integers. Negatives are clamped to
 *   zero so a parse error surfaces only on non-integers.
 */
sealed class MacroStep {
    data class Tap(val x: Int, val y: Int) : MacroStep()
    data class LongTap(val x: Int, val y: Int, val durationMs: Long) : MacroStep()
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
    ) : MacroStep()
    data class Key(val name: String) : MacroStep()
    data class Input(val text: String) : MacroStep()
    data class Find(val label: String) : MacroStep()
    data class Delay(val ms: Long) : MacroStep()
    data class ParseError(val raw: String, val reason: String) : MacroStep()
}

object MacroParser {

    fun parse(text: String): List<MacroStep> = text
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { parseSegment(it) }

    @Suppress("ReturnCount", "ComplexMethod")
    private fun parseSegment(segment: String): MacroStep {
        val tokens = segment.split(Regex("\\s+"))
        val verb = tokens[0].lowercase()
        val rest = tokens.drop(1)
        return when (verb) {
            "tap" -> parseTap(segment, rest)
            "longtap" -> parseLongTap(segment, rest)
            "swipe" -> parseSwipe(segment, rest)
            "key" -> parseKey(segment, rest)
            "input" -> parseInput(segment)
            "find" -> parseFind(segment)
            "delay" -> parseDelay(segment, rest)
            else -> MacroStep.ParseError(segment, "unknown verb '$verb'")
        }
    }

    private fun parseTap(segment: String, rest: List<String>): MacroStep {
        if (rest.size < 2) return MacroStep.ParseError(segment, "tap needs x y")
        val x = rest[0].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer x for tap")
        val y = rest[1].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer y for tap")
        return MacroStep.Tap(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    private fun parseLongTap(segment: String, rest: List<String>): MacroStep {
        if (rest.size < 2) return MacroStep.ParseError(segment, "longtap needs x y [ms]")
        val x = rest[0].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer x for longtap")
        val y = rest[1].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer y for longtap")
        val ms = if (rest.size >= 3) {
            rest[2].toLongOrNull() ?: return MacroStep.ParseError(segment, "expected integer ms for longtap")
        } else {
            A11yService.LONG_TAP_MS
        }
        return MacroStep.LongTap(x.coerceAtLeast(0), y.coerceAtLeast(0), ms.coerceAtLeast(0))
    }

    private fun parseSwipe(segment: String, rest: List<String>): MacroStep {
        if (rest.size < 4) return MacroStep.ParseError(segment, "swipe needs x1 y1 x2 y2 [ms]")
        val x1 = rest[0].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer x1 for swipe")
        val y1 = rest[1].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer y1 for swipe")
        val x2 = rest[2].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer x2 for swipe")
        val y2 = rest[3].toIntOrNull() ?: return MacroStep.ParseError(segment, "expected integer y2 for swipe")
        val ms = if (rest.size >= 5) {
            rest[4].toLongOrNull() ?: return MacroStep.ParseError(segment, "expected integer ms for swipe")
        } else {
            A11yService.SWIPE_MS
        }
        return MacroStep.Swipe(
            x1.coerceAtLeast(0), y1.coerceAtLeast(0),
            x2.coerceAtLeast(0), y2.coerceAtLeast(0),
            ms.coerceAtLeast(0),
        )
    }

    private fun parseKey(segment: String, rest: List<String>): MacroStep {
        if (rest.isEmpty()) return MacroStep.ParseError(segment, "key needs <NAME>")
        return MacroStep.Key(rest[0].uppercase())
    }

    private fun parseInput(segment: String): MacroStep {
        // The whole rest of the segment after the verb is the literal text.
        // Strip the leading "input" + at least one whitespace character.
        val match = Regex("^input\\s+(.*)$", RegexOption.IGNORE_CASE).find(segment.trim())
        val text = match?.groupValues?.get(1)?.trim().orEmpty()
        if (text.isEmpty()) return MacroStep.ParseError(segment, "input needs <text>")
        return MacroStep.Input(text)
    }

    private fun parseFind(segment: String): MacroStep {
        val match = Regex("^find\\s+(.*)$", RegexOption.IGNORE_CASE).find(segment.trim())
        val label = match?.groupValues?.get(1)?.trim().orEmpty()
        if (label.isEmpty()) return MacroStep.ParseError(segment, "find needs <label>")
        return MacroStep.Find(label)
    }

    private fun parseDelay(segment: String, rest: List<String>): MacroStep {
        if (rest.isEmpty()) return MacroStep.ParseError(segment, "delay needs <ms>")
        val ms = rest[0].toLongOrNull() ?: return MacroStep.ParseError(segment, "expected integer ms for delay")
        return MacroStep.Delay(ms.coerceAtLeast(0))
    }
}
