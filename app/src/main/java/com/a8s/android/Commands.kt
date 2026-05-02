package com.a8s.android

/**
 * Slash-command outputs that don't need an Android Context. Each helper
 * is pure: takes a snapshot dataclass, returns a formatted string the
 * service layer publishes back to the owner.
 *
 * The Android-flavored bits (battery, network type, build info) are
 * gathered in `A8sService.snapshotInfo()` and passed in here as
 * `InfoSnapshot`. That keeps this file unit-testable.
 */
object Commands {

    data class InfoSnapshot(
        val appVersion: String,
        val deviceModel: String,
        val androidRelease: String,
        val sdkInt: Int,
        val mqttConnected: Boolean,
        val mqttBroker: String,
        val mqttTopic: String,
        val networkType: String,
        val batteryPercent: Int?,
        val batteryCharging: Boolean,
        val uptimeMs: Long,
        val phonebookSize: Int,
        val ownerSet: Boolean,
        val forwardSet: Boolean,
    )

    fun renderInfo(s: InfoSnapshot): String = buildString {
        appendLine("a8s-android ${s.appVersion}")
        appendLine("Device: ${s.deviceModel} (Android ${s.androidRelease}, API ${s.sdkInt})")
        appendLine("MQTT: ${if (s.mqttConnected) "connected" else "DISCONNECTED"} → ${s.mqttBroker} / ${s.mqttTopic}")
        appendLine("Network: ${s.networkType}")
        val batt = s.batteryPercent?.let { "$it%" } ?: "?"
        val chg = if (s.batteryCharging) " (charging)" else ""
        appendLine("Battery: $batt$chg")
        appendLine("Uptime: ${formatDuration(s.uptimeMs)}")
        append("Config: ")
        append("phonebook=${s.phonebookSize}, ")
        append("owner=${if (s.ownerSet) "set" else "(none)"}, ")
        append("forward=${if (s.forwardSet) "set" else "(none)"}")
    }

    /** Tail of `logs`, taking the last `n` lines. `n` clamped to [1, 500]. */
    fun renderLogs(logs: String, n: Int): String {
        val clamped = n.coerceIn(1, 500)
        val lines = logs.split("\n")
        val tail = if (lines.size > clamped) lines.takeLast(clamped) else lines
        val header = "logs: last ${tail.size} of ${lines.size} line(s)"
        return (listOf(header) + tail).joinToString("\n")
    }

    fun parseLogsArgs(args: List<String>, default: Int = DEFAULT_LOGS_LINES): Int {
        if (args.isEmpty()) return default
        return args[0].toIntOrNull() ?: default
    }

    fun renderUnknown(name: String): String =
        "unknown command: /$name\nknown: /info, /logs [N], /update [--check|<url>]"

    private fun formatDuration(ms: Long): String {
        if (ms < 0) return "unknown"
        var s = ms / 1000
        val days = s / 86_400; s %= 86_400
        val hrs = s / 3600; s %= 3600
        val mins = s / 60
        val secs = s % 60
        return when {
            days > 0 -> "${days}d ${hrs}h ${mins}m"
            hrs > 0 -> "${hrs}h ${mins}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

    const val DEFAULT_LOGS_LINES: Int = 50
}
