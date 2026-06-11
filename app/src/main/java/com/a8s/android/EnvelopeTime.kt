package com.a8s.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** UTC timestamp for a8s wire envelopes (`2026-05-02T01:23:45Z`). */
object EnvelopeTime {

    fun isoNowUtc(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
