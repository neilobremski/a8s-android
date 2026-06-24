package com.a8s.android

/**
 * Pure-Kotlin renderers for slash-command outputs that don't need an
 * Android Context. The Android-flavored gathering lives in
 * `InfoSnapshotter.capture(...)`; this file consumes the snapshot
 * shape and formats it.
 */
object Commands {

    data class RemoteStatus(
        val name: String,
        val broker: String,
        val topic: String,
        val connected: Boolean,
    )

    fun renderInfo(s: InfoSnapshotter.InfoSnapshot, verbose: Boolean): String = buildString {
        renderHeader(this, s)
        renderIdentityLine(this, s)
        renderNetworkLine(this, s)
        renderRemotes(this, s)
        renderStorageServices(this, s)
        renderBatteryLine(this, s)
        renderMemoryLine(this, s)
        renderStorageLine(this, s)
        renderDisplayLine(this, s)
        renderPowerLine(this, s)
        renderPermissionsLine(this, s)
        renderCriticalServicesLine(this, s)
        renderUptimeLine(this, s)
        renderConfigLine(this, s)
        if (verbose) renderVerbose(this, s)
    }.trimEnd('\n')

    private fun renderHeader(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val app = s.app
        val ver = app?.versionName?.let { "v$it" } ?: "v?"
        val build = app?.versionCode?.let { " (build $it)" } ?: ""
        b.appendLine("a8s-android $ver$build")
    }

    private fun renderIdentityLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val id = s.identity ?: return
        val os = s.os
        val maker = id.manufacturer ?: "?"
        val model = id.model ?: "?"
        val release = os?.release ?: "?"
        val sdk = os?.sdkInt?.toString() ?: "?"
        val patch = os?.securityPatch?.let { " · patch $it" } ?: ""
        val soc = id.socModel?.let { " · $it" } ?: ""
        b.appendLine("Device: $maker $model · Android $release (API $sdk)$patch$soc")
    }

    private fun renderNetworkLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val n = s.network ?: return
        val parts = mutableListOf<String>()
        n.activeTransport?.let { parts.add(it) }
        if (n.validated == true) parts.add("validated") else if (n.validated == false) parts.add("unvalidated")
        if (n.metered == true) parts.add("metered")
        if (s.cellular?.roaming == true) parts.add("roaming")
        s.cellular?.signalDbm?.let { parts.add("${it}dBm") }
        s.cellular?.networkType?.let { parts.add(it) }
        if (parts.isEmpty()) return
        b.appendLine("Network: ${parts.joinToString(" · ")}")
    }

    private fun renderRemotes(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        if (s.remotes.isEmpty()) {
            b.appendLine("Remotes: (none configured)")
            return
        }
        val connected = s.remotes.count { it.connected }
        b.appendLine("Remotes: $connected/${s.remotes.size} connected")
        for (r in s.remotes) {
            val state = if (r.connected) "✓" else "✗"
            b.appendLine("  $state ${r.name} → ${r.broker} / ${r.topic}")
        }
    }

    private fun renderStorageServices(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        b.appendLine("Storage services: ${if (s.services.isEmpty()) "(none)" else s.services.joinToString(", ")}")
    }

    private fun renderBatteryLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val ba = s.battery ?: return
        val parts = mutableListOf<String>()
        ba.percent?.let { parts.add("$it%") }
        ba.status?.let { parts.add(it.lowercase()) }
        ba.temperatureC?.let { parts.add("%.1f°C".format(it)) }
        ba.currentNowUa?.let { parts.add("${it / 1000}mA") }
        if (parts.isEmpty()) return
        b.appendLine("Battery: ${parts.joinToString(" · ")}")
    }

    private fun renderMemoryLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val m = s.memory ?: return
        val parts = mutableListOf<String>()
        if (m.ramAvailBytes != null && m.ramTotalBytes != null) {
            parts.add("RAM ${formatBytes(m.ramAvailBytes)}/${formatBytes(m.ramTotalBytes)}")
        }
        m.rssBytes?.let { parts.add("RSS ${formatBytes(it)}") }
        if (m.javaHeapUsedBytes != null) {
            val maxStr = m.javaHeapMaxBytes?.let { "/${formatBytes(it)}" } ?: ""
            parts.add("Java heap ${formatBytes(m.javaHeapUsedBytes)}$maxStr")
        }
        if (parts.isEmpty()) return
        b.appendLine("Memory: ${parts.joinToString(" · ")}")
    }

    private fun renderStorageLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val st = s.storage ?: return
        val parts = mutableListOf<String>()
        if (st.internalFreeBytes != null && st.internalTotalBytes != null) {
            parts.add("data ${formatBytes(st.internalFreeBytes)}/${formatBytes(st.internalTotalBytes)}")
        }
        st.ourCacheBytes?.let { parts.add("cache ${formatBytes(it)}") }
        if (parts.isEmpty()) return
        b.appendLine("Storage: ${parts.joinToString(" · ")}")
    }

    private fun renderDisplayLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val d = s.display ?: return
        val parts = mutableListOf<String>()
        if (d.widthPx != null && d.heightPx != null) {
            val rate = d.refreshRateHz?.let { "@%.0fHz".format(it) } ?: ""
            parts.add("${d.widthPx}×${d.heightPx}$rate")
        }
        if (d.brightnessLevel != null) {
            val mode = when (d.brightnessMode) { 1 -> "auto"; 0 -> "manual"; else -> "?" }
            parts.add("brightness $mode ${d.brightnessLevel}")
        }
        val screenStates = mutableListOf<String>()
        d.interactive?.let { screenStates.add(if (it) "on" else "off") }
        d.deviceLocked?.let { screenStates.add(if (it) "locked" else "unlocked") }
        if (screenStates.isNotEmpty()) parts.add("screen ${screenStates.joinToString(" ")}")
        if (parts.isEmpty()) return
        b.appendLine("Display: ${parts.joinToString(" · ")}")
    }

    private fun renderPowerLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val p = s.power ?: return
        val parts = mutableListOf<String>()
        p.interactive?.let { parts.add("interactive=$it") }
        p.deviceIdle?.let { parts.add("doze=$it") }
        p.batterySaver?.let { parts.add("battery-saver=$it") }
        p.standbyBucket?.let { parts.add("standby=$it") }
        p.thermalStatus?.let { parts.add("thermal=$it") }
        p.ignoringBatteryOptimizations?.let { parts.add("ignoring-batt-opt=$it") }
        if (parts.isEmpty()) return
        b.appendLine("Power: ${parts.joinToString(" · ")}")
    }

    private fun renderPermissionsLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val perms = s.apps?.grantedPermissions ?: return
        if (perms.isEmpty()) return
        val expected = setOf(
            "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "READ_PHONE_STATE", "READ_CONTACTS",
            "POST_NOTIFICATIONS", "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION", "READ_MEDIA_IMAGES", "READ_MEDIA_AUDIO",
        )
        val matched = perms.filter { it.name in expected }
        if (matched.isEmpty()) return
        b.appendLine("Permissions:")
        for (p in matched) {
            val mark = if (p.granted) "✓" else "✗"
            b.appendLine("  $mark ${p.name}")
        }
    }

    private fun renderCriticalServicesLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val parts = mutableListOf<String>()
        s.a11yRunning?.let { parts.add("a11y=$it") }
        val notifGranted = s.notifications?.postNotificationsGranted
        notifGranted?.let { parts.add("notif=$it") }
        s.apps?.notificationListeners?.let { listeners ->
            val ours = listeners.any { it.contains("a8s") }
            parts.add("notif-listener=$ours")
        }
        parts.add("projection=${s.projectionConsent}")
        b.appendLine("Services: ${parts.joinToString(" · ")}")
    }

    private fun renderUptimeLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val parts = mutableListOf<String>()
        parts.add("service ${formatDuration(s.serviceUptimeMs)}")
        s.os?.elapsedRealtimeMs?.let { parts.add("boot ${formatDuration(it)}") }
        b.appendLine("Uptime: ${parts.joinToString(" · ")}")
    }

    private fun renderConfigLine(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        b.append("Config: ")
        b.append("principals=${s.principalCount}")
        b.append("\n")
    }

    private fun renderVerbose(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        b.appendLine()
        b.appendLine("── verbose ──")
        renderVerboseIdentity(b, s)
        renderVerboseOs(b, s)
        renderVerboseCellular(b, s)
        renderVerboseWifi(b, s)
        renderVerboseNetwork(b, s)
        renderVerboseSensors(b, s)
        renderVerboseLocation(b, s)
        renderVerboseCamera(b, s)
        renderVerboseConnectivity(b, s)
        renderVerboseSecurity(b, s)
        renderVerboseApps(b, s)
        renderVerboseMemory(b, s)
        renderVerboseProcess(b, s)
    }

    private fun renderVerboseIdentity(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val id = s.identity ?: return
        id.brand?.let { b.appendLine("Brand: $it") }
        id.product?.let { b.appendLine("Product: $it") }
        id.device?.let { b.appendLine("Device codename: $it") }
        id.hardware?.let { b.appendLine("Hardware: $it") }
        id.board?.let { b.appendLine("Board: $it") }
        id.socManufacturer?.let { b.appendLine("SoC manufacturer: $it") }
        id.fingerprint?.let { b.appendLine("Build fingerprint: $it") }
        id.tags?.let { b.appendLine("Build tags: $it") }
        id.type?.let { b.appendLine("Build type: $it") }
        id.bootloader?.let { b.appendLine("Bootloader: $it") }
        id.radioVersion?.let { b.appendLine("Radio: $it") }
        id.androidId?.let { b.appendLine("Android ID: $it") }
        s.app?.signingCertSha256Prefix?.let { b.appendLine("App signing SHA-256 prefix: $it") }
        s.app?.installSource?.let { b.appendLine("App install source: $it") }
    }

    private fun renderVerboseOs(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val os = s.os ?: return
        os.codename?.let { b.appendLine("Codename: $it") }
        os.incremental?.let { b.appendLine("Incremental: $it") }
        os.kernelVersion?.let { b.appendLine("Kernel: $it") }
        os.kernelArch?.let { b.appendLine("Arch: $it") }
        os.javaVm?.let { b.appendLine("Java VM: $it") }
        os.locale?.let { b.appendLine("Locale: $it") }
        os.timezoneId?.let { tz ->
            val off = os.timezoneOffsetMin?.let { " (UTC${if (it >= 0) "+" else ""}$it min)" } ?: ""
            b.appendLine("Timezone: $tz$off")
        }
        os.autoTime?.let { b.appendLine("Auto-time: $it") }
        os.autoTimezone?.let { b.appendLine("Auto-timezone: $it") }
    }

    private fun renderVerboseCellular(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val c = s.cellular ?: return
        if (!c.present) {
            b.appendLine("Cellular: (no telephony)")
            return
        }
        c.carrierName?.let { b.appendLine("Carrier: $it") }
        c.networkOperator?.let { b.appendLine("MCC/MNC: $it") }
        c.simOperatorName?.let { b.appendLine("SIM operator: $it") }
        c.simCountryIso?.let { b.appendLine("SIM country: $it") }
        c.simState?.let { b.appendLine("SIM state: $it") }
        c.activeSubscriptionCount?.let { b.appendLine("Active subscriptions: $it") }
        c.cellTowers?.let { b.appendLine("Cell towers visible: $it") }
        c.voiceNetworkType?.let { b.appendLine("Voice network: $it") }
    }

    private fun renderVerboseWifi(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val w = s.wifi ?: return
        w.ssid?.let { b.appendLine("Wi-Fi SSID: $it") }
        w.bssid?.let { b.appendLine("Wi-Fi BSSID: $it") }
        w.frequencyMhz?.let { b.appendLine("Wi-Fi freq: ${it}MHz") }
        if (w.txLinkSpeedMbps != null || w.rxLinkSpeedMbps != null) {
            val tx = w.txLinkSpeedMbps?.toString() ?: "?"
            val rx = w.rxLinkSpeedMbps?.toString() ?: "?"
            b.appendLine("Wi-Fi link: tx ${tx}Mbps · rx ${rx}Mbps")
        }
        w.ipAddress?.let { b.appendLine("Wi-Fi IP: $it") }
        w.standard?.let { b.appendLine("Wi-Fi standard: $it") }
        w.band5GhzSupported?.let { b.appendLine("Wi-Fi 5GHz: $it") }
        w.band6GhzSupported?.let { b.appendLine("Wi-Fi 6GHz: $it") }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun renderVerboseNetwork(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val n = s.network ?: return
        n.ipv4Addresses?.let { b.appendLine("IPv4: ${it.joinToString(", ")}") }
        n.ipv6Addresses?.let { b.appendLine("IPv6: ${it.joinToString(", ")}") }
        n.gateway?.let { b.appendLine("Gateway: $it") }
        n.dnsServers?.takeIf { it.isNotEmpty() }?.let { b.appendLine("DNS: ${it.joinToString(", ")}") }
        n.privateDns?.let { b.appendLine("Private DNS: $it") }
        n.mtu?.let { b.appendLine("MTU: $it") }
        n.interfaceName?.let { b.appendLine("Iface: $it") }
        n.httpProxy?.let { b.appendLine("HTTP proxy: $it") }
        if (n.totalRxBytes != null || n.totalTxBytes != null) {
            val rx = n.totalRxBytes?.let { formatBytes(it) } ?: "?"
            val tx = n.totalTxBytes?.let { formatBytes(it) } ?: "?"
            b.appendLine("Total traffic: rx $rx · tx $tx")
        }
        if (n.ourAppRxBytes != null || n.ourAppTxBytes != null) {
            val rx = n.ourAppRxBytes?.let { formatBytes(it) } ?: "?"
            val tx = n.ourAppTxBytes?.let { formatBytes(it) } ?: "?"
            b.appendLine("Our traffic: rx $rx · tx $tx")
        }
    }

    private fun renderVerboseSensors(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val sn = s.sensors ?: return
        sn.present?.takeIf { it.isNotEmpty() }?.let {
            b.appendLine("Sensors: ${it.joinToString(", ")}")
        }
    }

    private fun renderVerboseLocation(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val loc = s.location ?: return
        loc.locationEnabled?.let { b.appendLine("Location enabled: $it") }
        if (loc.lastFixLatitude != null && loc.lastFixLongitude != null) {
            val acc = loc.lastFixAccuracyM?.let { " · acc %.0fm".format(it) } ?: ""
            val age = loc.lastFixAgeMs?.let { " · age ${formatDuration(it)}" } ?: ""
            val prov = loc.lastFixProvider?.let { " · $it" } ?: ""
            b.appendLine("Last fix: %.6f, %.6f%s%s%s".format(loc.lastFixLatitude, loc.lastFixLongitude, acc, age, prov))
        }
    }

    private fun renderVerboseCamera(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val c = s.camera ?: return
        c.cameras?.takeIf { it.isNotEmpty() }?.let { specs ->
            b.appendLine("Cameras (${c.count ?: specs.size}):")
            for (sp in specs) {
                val parts = mutableListOf<String>()
                sp.facing?.let { parts.add(it) }
                sp.megapixels?.let { parts.add("%.1fMP".format(it)) }
                sp.maxStillResolution?.let { parts.add(it) }
                sp.hardwareLevel?.let { parts.add(it) }
                if (sp.flashAvailable == true) parts.add("flash")
                b.appendLine("  ${sp.id}: ${parts.joinToString(" · ")}")
            }
        }
    }

    private fun renderVerboseConnectivity(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val c = s.connectivity ?: return
        val parts = mutableListOf<String>()
        c.bluetoothEnabled?.let { parts.add("bt=$it") }
        c.bluetoothState?.let { parts.add("bt-state=$it") }
        c.nfcEnabled?.let { parts.add("nfc=$it") }
        c.airplaneMode?.let { parts.add("airplane=$it") }
        c.usbConnected?.let { parts.add("usb=$it") }
        if (parts.isEmpty()) return
        b.appendLine("Connectivity: ${parts.joinToString(" · ")}")
    }

    private fun renderVerboseSecurity(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val sec = s.security ?: return
        val parts = mutableListOf<String>()
        sec.deviceEncrypted?.let { parts.add("encrypted=$it") }
        sec.deviceSecure?.let { parts.add("locksecure=$it") }
        sec.biometricStatus?.let { parts.add("biometric=$it") }
        sec.adbEnabled?.let { parts.add("adb=$it") }
        sec.developerOptions?.let { parts.add("devopts=$it") }
        sec.canRequestInstall?.let { parts.add("install=$it") }
        if (parts.isEmpty()) return
        b.appendLine("Security: ${parts.joinToString(" · ")}")
    }

    private fun renderVerboseApps(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val a = s.apps ?: return
        a.installedCount?.let { b.appendLine("Installed apps visible: $it") }
        a.defaultBrowser?.let { b.appendLine("Default browser: $it") }
        a.defaultDialer?.let { b.appendLine("Default dialer: $it") }
        a.defaultSms?.let { b.appendLine("Default SMS: $it") }
        a.defaultHome?.let { b.appendLine("Default home: $it") }
    }

    private fun renderVerboseMemory(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val m = s.memory ?: return
        m.vssBytes?.let { b.appendLine("VSS: ${formatBytes(it)}") }
        m.pssBytes?.let { b.appendLine("PSS: ${formatBytes(it)}") }
        m.threadCount?.let { b.appendLine("Threads: $it") }
        m.openFdCount?.let { b.appendLine("Open FDs: $it") }
        m.gcCount?.let { b.appendLine("GC count: $it") }
        m.gcBytesAllocated?.let { b.appendLine("GC bytes allocated: $it") }
        if (m.swapTotalBytes != null && m.swapTotalBytes > 0) {
            val free = m.swapFreeBytes?.let { formatBytes(it) } ?: "?"
            b.appendLine("Swap: $free / ${formatBytes(m.swapTotalBytes)}")
        }
    }

    private fun renderVerboseProcess(b: StringBuilder, s: InfoSnapshotter.InfoSnapshot) {
        val p = s.process ?: return
        if (p.pid != null) b.appendLine("PID: ${p.pid}")
        if (p.uid != null) b.appendLine("UID: ${p.uid}")
        p.processName?.let { b.appendLine("Process name: $it") }
        p.activeThreadsJvm?.let { b.appendLine("JVM threads: $it") }
        p.processStartTimeMs?.let { b.appendLine("Process started: ${formatDuration(System.currentTimeMillis() - it)} ago") }
        p.cpuTimeMs?.let { b.appendLine("CPU time (own): ${formatDuration(it)}") }
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

    /** Same clamp as logs: [1, 500]. */
    fun parseTraceArgs(args: List<String>, default: Int = DEFAULT_LOGS_LINES): Int =
        parseLogsArgs(args, default)

    fun renderUnknown(name: String): String =
        "unknown command: /$name\n" +
            "known: " + CmdHelpers.KNOWN_COMMANDS.joinToString(", ")

    /** "1.2 GB" / "512 MB" / "20 KB" / "100 B". 1024 base. */
    fun formatBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB", "PB")
        var v = n.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

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
