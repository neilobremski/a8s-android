package com.a8s.android

/** Builds individually sendable carrier units using the platform's own segmentation test. */
object SmsSegmenter {
    fun split(body: String, isSingleSegment: (String) -> Boolean): List<String> {
        if (isSingleSegment(body)) return listOf(body)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < body.length) {
            val end = largestEnd(body, start, PREFIX_RESERVE, isSingleSegment)
            val preferred = preferredBoundary(body, start, end)
            pieces += body.substring(start, preferred).trimEnd()
            start = preferred
            while (start < body.length && body[start].isWhitespace()) start++
        }
        val total = pieces.size
        return pieces.mapIndexed { index, piece -> "[${index + 1}/$total] $piece" }
    }

    private fun largestEnd(
        body: String,
        start: Int,
        prefix: String,
        isSingleSegment: (String) -> Boolean,
    ): Int {
        var end = start
        var best = start
        while (end < body.length) {
            end += Character.charCount(body.codePointAt(end))
            if (!isSingleSegment(prefix + body.substring(start, end))) break
            best = end
        }
        require(best > start) { "platform SMS limit is too small for the part prefix" }
        return best
    }

    private fun preferredBoundary(body: String, start: Int, end: Int): Int {
        if (end == body.length) return end
        val whitespace = (end - 1 downTo start + (end - start) / 2).firstOrNull { body[it].isWhitespace() }
        return whitespace?.plus(1) ?: end
    }

    private const val PREFIX_RESERVE = "[999/999] "
}
