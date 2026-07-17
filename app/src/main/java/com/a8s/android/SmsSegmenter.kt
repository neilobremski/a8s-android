package com.a8s.android

/** Splits long text into readable logical SMS messages without breaking tokens. */
object SmsSegmenter {
    fun split(body: String, maxChars: Int, messageId: Int): List<String> {
        require(maxChars >= MIN_CHUNK_CHARS) { "SMS chunk limit must be at least $MIN_CHUNK_CHARS" }
        if (body.length <= maxChars) return listOf(body)

        var totalHint = 2
        repeat(MAX_LAYOUT_PASSES) {
            val prefixLength = prefix(messageId, totalHint, totalHint).length
            val pieces = splitContent(body, maxChars - prefixLength)
            if (pieces.size == 1) return listOf(body)
            if (pieces.size == totalHint) {
                return pieces.mapIndexed { index, piece ->
                    prefix(messageId, index + 1, pieces.size) + piece
                }
            }
            totalHint = pieces.size
        }
        error("SMS chunk layout did not converge")
    }

    private fun splitContent(body: String, budget: Int): List<String> {
        val pieces = mutableListOf<String>()
        var start = skipWhitespace(body, 0)
        while (start < body.length) {
            var end = (start + budget).coerceAtMost(body.length)
            if (end < body.length && body[end - 1].isHighSurrogate() && body[end].isLowSurrogate()) end--
            if (end < body.length) end = tokenBoundary(body, start, end)
            pieces += body.substring(start, end).trimEnd()
            start = skipWhitespace(body, end)
        }
        return pieces
    }

    /**
     * Prefer the last whitespace within the budget. If the current token is
     * longer than the whole budget, extend past the limit rather than split a
     * word or URL.
     */
    private fun tokenBoundary(body: String, start: Int, limit: Int): Int {
        for (i in limit downTo start + 1) {
            if (body[i - 1].isWhitespace()) return i - 1
        }
        for (i in limit until body.length) {
            if (body[i].isWhitespace()) return i
        }
        return body.length
    }

    private fun skipWhitespace(body: String, from: Int): Int {
        var index = from
        while (index < body.length && body[index].isWhitespace()) index++
        return index
    }

    private fun prefix(messageId: Int, part: Int, total: Int): String =
        "Message $messageId part $part of $total: "

    const val DEFAULT_CHUNK_CHARS = 1000
    const val MIN_CHUNK_CHARS = 100
    private const val MAX_LAYOUT_PASSES = 10
}
