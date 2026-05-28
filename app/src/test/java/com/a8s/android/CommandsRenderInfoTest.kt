package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsRenderInfoTest {

    private fun emptySnapshot() = InfoSnapshotter.InfoSnapshot(
        app = null, identity = null, os = null, cellular = null, wifi = null,
        network = null, battery = null, storage = null, memory = null, display = null,
        audio = null, sensors = null, location = null, camera = null, connectivity = null,
        power = null, process = null, security = null, apps = null, notifications = null,
        remotes = emptyList(), services = emptyList(),
        phonebookSize = 0,
        serviceUptimeMs = 0L, a11yRunning = null, projectionConsent = false,
    )

    private fun baseSnapshot() = emptySnapshot().copy(
        app = InfoSnapshotter.App(
            versionName = "1.15.0", versionCode = 20L, packageName = "com.a8s.android",
            firstInstallTime = 1L, lastUpdateTime = 2L,
            signingCertSha256Prefix = null, installSource = null,
        ),
        identity = InfoSnapshotter.Identity(
            manufacturer = "Google", model = "Pixel 7", brand = "google",
            product = null, device = null, hardware = null, board = null,
            socManufacturer = null, socModel = null, fingerprint = null,
            display = null, tags = null, type = null, bootloader = null,
            radioVersion = null, androidId = null,
        ),
        os = InfoSnapshotter.Os(
            release = "14", sdkInt = 34, codename = null, incremental = null,
            securityPatch = "2024-09-01", baseOs = null, previewSdk = null,
            kernelVersion = null, kernelArch = null, javaVm = null,
            locale = "en-US", timezoneId = "UTC", timezoneOffsetMin = 0,
            wallClockMs = 0L, bootTimeMs = 0L, elapsedRealtimeMs = 60_000L,
            uptimeMs = null, autoTime = null, autoTimezone = null,
        ),
        remotes = listOf(
            Commands.RemoteStatus("hivemq", "ssl://broker:8883", "test-topic", connected = true),
        ),
        services = listOf("tempfile"),
        phonebookSize = 1,
        serviceUptimeMs = 5 * 60 * 1000L,
    )

    @Suppress("LongParameterList")
    private fun InfoSnapshotter.InfoSnapshot.copy(
        app: InfoSnapshotter.App? = this.app,
        identity: InfoSnapshotter.Identity? = this.identity,
        os: InfoSnapshotter.Os? = this.os,
        battery: InfoSnapshotter.Battery? = this.battery,
        remotes: List<Commands.RemoteStatus> = this.remotes,
        services: List<String> = this.services,
        phonebookSize: Int = this.phonebookSize,
        serviceUptimeMs: Long = this.serviceUptimeMs,
    ) = InfoSnapshotter.InfoSnapshot(
        app = app, identity = identity, os = os, cellular = this.cellular, wifi = this.wifi,
        network = this.network, battery = battery, storage = this.storage,
        memory = this.memory, display = this.display, audio = this.audio,
        sensors = this.sensors, location = this.location, camera = this.camera,
        connectivity = this.connectivity, power = this.power, process = this.process,
        security = this.security, apps = this.apps, notifications = this.notifications,
        remotes = remotes, services = services,
        phonebookSize = phonebookSize,
        serviceUptimeMs = serviceUptimeMs, a11yRunning = this.a11yRunning,
        projectionConsent = this.projectionConsent,
    )

    @Test
    fun `renderInfo with all-null subsections produces a non-empty short reply`() {
        val out = Commands.renderInfo(emptySnapshot(), verbose = false)
        assertTrue(out.isNotEmpty())
        // Header always present; config line always present.
        assertTrue(out.contains("a8s-android"))
        assertTrue(out.contains("Config:"))
        assertTrue(out.contains("phonebook=0"))
        assertTrue(out.contains("Remotes: (none configured)"))
        assertTrue(out.contains("Storage services: (none)"))
    }

    @Test
    fun `renderInfo default omits verbose-only fields`() {
        val s = baseSnapshot().copy(
            identity = InfoSnapshotter.Identity(
                manufacturer = "Google", model = "Pixel 7", brand = "google",
                product = null, device = null, hardware = null, board = null,
                socManufacturer = null, socModel = null,
                fingerprint = "google/oriole/oriole:14/UQ1A.240205.004/abc:user/release-keys",
                display = null, tags = null, type = null, bootloader = null,
                radioVersion = null, androidId = "abc123def456",
            ),
        )
        val out = Commands.renderInfo(s, verbose = false)
        assertFalse(out.contains("google/oriole"), "fingerprint must not appear in default output")
        assertFalse(out.contains("abc123def456"), "Android ID must not appear in default output")
        assertFalse(out.contains("── verbose ──"))
    }

    @Test
    fun `renderInfo verbose includes identifier fields`() {
        val s = baseSnapshot().copy(
            identity = InfoSnapshotter.Identity(
                manufacturer = "Google", model = "Pixel 7", brand = "google",
                product = "oriole", device = "oriole", hardware = "oriole", board = "oriole",
                socManufacturer = "Google", socModel = "Tensor G2",
                fingerprint = "google/oriole/oriole:14/UQ1A.240205.004/abc:user/release-keys",
                display = "UQ1A", tags = "release-keys", type = "user",
                bootloader = "slider-1.0", radioVersion = "g5300x-x.y.z",
                androidId = "abc123def456",
            ),
        )
        val out = Commands.renderInfo(s, verbose = true)
        assertTrue(out.contains("── verbose ──"))
        assertTrue(out.contains("google/oriole"))
        assertTrue(out.contains("abc123def456"))
        assertTrue(out.contains("oriole"))
    }

    @Test
    fun `renderInfo header shows version and build`() {
        val out = Commands.renderInfo(baseSnapshot(), verbose = false)
        assertTrue(out.contains("a8s-android v1.15.0 (build 20)"))
    }

    @Test
    fun `renderInfo identity line includes patch and api level`() {
        val out = Commands.renderInfo(baseSnapshot(), verbose = false)
        assertTrue(out.contains("Google Pixel 7"))
        assertTrue(out.contains("Android 14"))
        assertTrue(out.contains("API 34"))
        assertTrue(out.contains("patch 2024-09-01"))
    }

    @Test
    fun `renderInfo battery line includes percent status temp`() {
        val s = baseSnapshot().copy(
            battery = InfoSnapshotter.Battery(
                percent = 72, charging = true, plug = "USB", status = "CHARGING",
                health = "GOOD", technology = null, temperatureC = 31.5,
                voltageMv = null, currentNowUa = 1_500_000, currentAvgUa = null,
                chargeCounterUah = null, energyCounterNwh = null,
                powerSaveMode = false, chargeTimeRemainingMs = null, batteryLow = null,
            ),
        )
        val out = Commands.renderInfo(s, verbose = false)
        assertTrue(out.contains("Battery:"))
        assertTrue(out.contains("72%"))
        assertTrue(out.contains("charging"))
        assertTrue(out.contains("31.5°C"))
        assertTrue(out.contains("1500mA"))
    }

    @Test
    fun `renderInfo battery line absent without battery section`() {
        val out = Commands.renderInfo(baseSnapshot().copy(battery = null), verbose = false)
        assertFalse(out.contains("Battery:"))
    }

    @Test
    fun `renderInfo battery line when not charging shows discharging status`() {
        val s = baseSnapshot().copy(
            battery = InfoSnapshotter.Battery(
                percent = 87, charging = false, plug = "NONE", status = "DISCHARGING",
                health = "GOOD", technology = null, temperatureC = null,
                voltageMv = null, currentNowUa = null, currentAvgUa = null,
                chargeCounterUah = null, energyCounterNwh = null,
                powerSaveMode = false, chargeTimeRemainingMs = null, batteryLow = null,
            ),
        )
        val out = Commands.renderInfo(s, verbose = false)
        assertTrue(out.contains("Battery: 87% · discharging"))
    }

    @Test
    fun `renderInfo remotes block`() {
        val s = baseSnapshot().copy(
            remotes = listOf(
                Commands.RemoteStatus("a", "ssl://a:8883", "t", connected = true),
                Commands.RemoteStatus("b", "ssl://b:8883", "t", connected = false),
            ),
        )
        val out = Commands.renderInfo(s, verbose = false)
        assertTrue(out.contains("Remotes: 1/2 connected"))
        assertTrue(out.contains("✓ a"))
        assertTrue(out.contains("✗ b"))
    }

    @Test
    fun `formatBytes covers boundaries`() {
        assertEquals("0 B", Commands.formatBytes(0L))
        assertEquals("100 B", Commands.formatBytes(100L))
        assertEquals("1023 B", Commands.formatBytes(1023L))
        assertEquals("1.0 KB", Commands.formatBytes(1024L))
        assertEquals("20.0 KB", Commands.formatBytes(20L * 1024))
        assertEquals("512 KB", Commands.formatBytes(512L * 1024))
        assertEquals("1.0 MB", Commands.formatBytes(1024L * 1024))
        assertEquals("1.2 GB", Commands.formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `renderInfo config line still rendered`() {
        val out = Commands.renderInfo(baseSnapshot(), verbose = false)
        assertTrue(out.contains("Config: "))
        assertTrue(out.contains("phonebook=1"))
        assertFalse(out.contains("owner="))
        assertFalse(out.contains("forward="))
    }

    @Test
    fun `renderInfo uptime block formatted`() {
        val out = Commands.renderInfo(baseSnapshot(), verbose = false)
        assertTrue(out.contains("Uptime:"))
        assertTrue(out.contains("service 5m 0s"))
        assertTrue(out.contains("boot 1m 0s"))
    }
}
