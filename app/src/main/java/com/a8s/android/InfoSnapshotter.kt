package com.a8s.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Display as AndroidDisplay
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds an `InfoSnapshot` that mirrors `INFO_FIELD_RESEARCH.md`. Every
 * field is nullable; "API not present", "permission denied at runtime",
 * "OEM weirdness" all return null rather than throwing. Each section
 * capture is wrapped so a single failing read doesn't tank the whole
 * reply.
 *
 * `verbose=false` populates only the default-tier fields (cheap +
 * non-leaky); verbose populates everything else. Keeps PII out of
 * non-verbose replies and avoids unnecessary work on the default path.
 *
 * The class is intentionally large + each section's `try`-density is
 * intentionally high — every leaf field gets its own
 * try/catch/null-fallback for OEM resilience, which inflates cyclomatic
 * complexity even though the control flow per branch is trivial.
 */
@Suppress("LargeClass", "TooManyFunctions", "CyclomaticComplexMethod", "LongMethod")
object InfoSnapshotter {

    private inline fun <T> attempt(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    data class InfoSnapshot(
        val app: App?,
        val identity: Identity?,
        val os: Os?,
        val cellular: Cellular?,
        val wifi: Wifi?,
        val network: Network?,
        val battery: Battery?,
        val storage: Storage?,
        val memory: Memory?,
        val display: Display?,
        val audio: Audio?,
        val sensors: Sensors?,
        val location: LocationInfo?,
        val camera: Camera?,
        val connectivity: Connectivity?,
        val power: Power?,
        val process: ProcessInfo?,
        val security: Security?,
        val apps: Apps?,
        val notifications: Notifications?,
        val remotes: List<Commands.RemoteStatus>,
        val services: List<String>,
        val phonebookSize: Int,
        val serviceUptimeMs: Long,
        val a11yRunning: Boolean?,
        val projectionConsent: Boolean,
    )

    data class App(
        val versionName: String?,
        val versionCode: Long?,
        val packageName: String?,
        val firstInstallTime: Long?,
        val lastUpdateTime: Long?,
        val signingCertSha256Prefix: String?,
        val installSource: String?,
    )

    data class Identity(
        val manufacturer: String?,
        val model: String?,
        val brand: String?,
        val product: String?,
        val device: String?,
        val hardware: String?,
        val board: String?,
        val socManufacturer: String?,
        val socModel: String?,
        val fingerprint: String?,
        val display: String?,
        val tags: String?,
        val type: String?,
        val bootloader: String?,
        val radioVersion: String?,
        val androidId: String?,
    )

    data class Os(
        val release: String?,
        val sdkInt: Int?,
        val codename: String?,
        val incremental: String?,
        val securityPatch: String?,
        val baseOs: String?,
        val previewSdk: Int?,
        val kernelVersion: String?,
        val kernelArch: String?,
        val javaVm: String?,
        val locale: String?,
        val timezoneId: String?,
        val timezoneOffsetMin: Int?,
        val wallClockMs: Long?,
        val bootTimeMs: Long?,
        val elapsedRealtimeMs: Long?,
        val uptimeMs: Long?,
        val autoTime: Boolean?,
        val autoTimezone: Boolean?,
    )

    data class Cellular(
        val present: Boolean,
        val carrierName: String?,
        val networkOperator: String?,
        val simOperatorName: String?,
        val simOperator: String?,
        val simCountryIso: String?,
        val networkCountryIso: String?,
        val simState: String?,
        val phoneCount: Int?,
        val activeSubscriptionCount: Int?,
        val networkType: String?,
        val voiceNetworkType: String?,
        val dataState: String?,
        val dataActivity: String?,
        val roaming: Boolean?,
        val signalDbm: Int?,
        val signalLevel: Int?,
        val dataEnabled: Boolean?,
        val dataRoamingEnabled: Boolean?,
        val callState: String?,
        val cellTowers: Int?,
    )

    data class Wifi(
        val enabled: Boolean?,
        val band5GhzSupported: Boolean?,
        val band6GhzSupported: Boolean?,
        val standard11axSupported: Boolean?,
        val ssid: String?,
        val bssid: String?,
        val linkSpeedMbps: Int?,
        val txLinkSpeedMbps: Int?,
        val rxLinkSpeedMbps: Int?,
        val frequencyMhz: Int?,
        val rssiDbm: Int?,
        val rssiBars: Int?,
        val standard: String?,
        val ipAddress: String?,
    )

    data class Network(
        val activeTransport: String?,
        val hasInternet: Boolean?,
        val validated: Boolean?,
        val captivePortal: Boolean?,
        val metered: Boolean?,
        val restrictBackground: String?,
        val downstreamKbps: Int?,
        val upstreamKbps: Int?,
        val ipv4Addresses: List<String>?,
        val ipv6Addresses: List<String>?,
        val gateway: String?,
        val dnsServers: List<String>?,
        val privateDns: String?,
        val mtu: Int?,
        val interfaceName: String?,
        val httpProxy: String?,
        val vpnActive: Boolean?,
        val ourAppRxBytes: Long?,
        val ourAppTxBytes: Long?,
        val totalRxBytes: Long?,
        val totalTxBytes: Long?,
    )

    data class Battery(
        val percent: Int?,
        val charging: Boolean?,
        val plug: String?,
        val status: String?,
        val health: String?,
        val technology: String?,
        val temperatureC: Double?,
        val voltageMv: Int?,
        val currentNowUa: Int?,
        val currentAvgUa: Int?,
        val chargeCounterUah: Int?,
        val energyCounterNwh: Long?,
        val powerSaveMode: Boolean?,
        val chargeTimeRemainingMs: Long?,
        val batteryLow: Boolean?,
    )

    data class Storage(
        val internalTotalBytes: Long?,
        val internalFreeBytes: Long?,
        val ourCacheBytes: Long?,
        val ourDataBytes: Long?,
        val ourAppBytes: Long?,
        val externalVolumes: List<ExternalVolume>?,
    )

    data class ExternalVolume(
        val description: String?,
        val state: String?,
        val isRemovable: Boolean?,
        val isPrimary: Boolean?,
        val isEmulated: Boolean?,
    )

    data class Memory(
        val ramTotalBytes: Long?,
        val ramAvailBytes: Long?,
        val lowMemory: Boolean?,
        val lowMemThresholdBytes: Long?,
        val rssBytes: Long?,
        val vssBytes: Long?,
        val nativeHeapAllocatedBytes: Long?,
        val nativeHeapSizeBytes: Long?,
        val nativeHeapFreeBytes: Long?,
        val javaHeapUsedBytes: Long?,
        val javaHeapMaxBytes: Long?,
        val pssBytes: Long?,
        val threadCount: Int?,
        val openFdCount: Int?,
        val gcCount: String?,
        val gcBytesAllocated: String?,
        val swapTotalBytes: Long?,
        val swapFreeBytes: Long?,
    )

    data class Display(
        val widthPx: Int?,
        val heightPx: Int?,
        val densityDpi: Int?,
        val densityScale: Float?,
        val refreshRateHz: Float?,
        val hdrTypes: List<Int>?,
        val wideColorGamut: Boolean?,
        val rotation: Int?,
        val state: String?,
        val brightnessMode: Int?,
        val brightnessLevel: Int?,
        val screenTimeoutMs: Int?,
        val interactive: Boolean?,
        val keyguardLocked: Boolean?,
        val keyguardSecure: Boolean?,
        val deviceLocked: Boolean?,
        val fontScale: Float?,
        val darkMode: Boolean?,
    )

    data class Audio(
        val ringerMode: String?,
        val musicVolumePercent: Int?,
        val ringVolumePercent: Int?,
        val notificationVolumePercent: Int?,
        val voiceCallVolumePercent: Int?,
        val alarmVolumePercent: Int?,
        val musicActive: Boolean?,
        val mode: String?,
        val speakerOn: Boolean?,
        val microphoneMute: Boolean?,
        val wiredHeadset: Boolean?,
        val usbAudio: Boolean?,
        val btAudio: Boolean?,
        val hdmiOut: Boolean?,
        val dndFilter: String?,
        val notificationsEnabled: Boolean?,
    )

    data class Sensors(
        val present: List<String>?,
        val pressureHpa: Float?,
        val lightLux: Float?,
        val proximityCm: Float?,
    )

    data class LocationInfo(
        val locationEnabled: Boolean?,
        val gpsProviderEnabled: Boolean?,
        val networkProviderEnabled: Boolean?,
        val lastFixProvider: String?,
        val lastFixLatitude: Double?,
        val lastFixLongitude: Double?,
        val lastFixAccuracyM: Float?,
        val lastFixAgeMs: Long?,
        val lastFixFromMock: Boolean?,
    )

    data class Camera(
        val count: Int?,
        val cameras: List<CameraSpec>?,
    )

    data class CameraSpec(
        val id: String,
        val facing: String?,
        val megapixels: Double?,
        val maxStillResolution: String?,
        val flashAvailable: Boolean?,
        val hardwareLevel: String?,
    )

    data class Connectivity(
        val bluetoothSupported: Boolean?,
        val bluetoothLeSupported: Boolean?,
        val bluetoothEnabled: Boolean?,
        val bluetoothState: String?,
        val nfcSupported: Boolean?,
        val nfcEnabled: Boolean?,
        val hceSupported: Boolean?,
        val telephonySupported: Boolean?,
        val usbHostSupported: Boolean?,
        val usbConnected: Boolean?,
        val airplaneMode: Boolean?,
    )

    data class Power(
        val batterySaver: Boolean?,
        val deviceIdle: Boolean?,
        val standbyBucket: String?,
        val ignoringBatteryOptimizations: Boolean?,
        val interactive: Boolean?,
        val thermalStatus: String?,
        val sustainedPerfSupported: Boolean?,
    )

    data class ProcessInfo(
        val pid: Int?,
        val uid: Int?,
        val processName: String?,
        val activeThreadsJvm: Int?,
        val processStartTimeMs: Long?,
        val cpuTimeMs: Long?,
    )

    data class Security(
        val deviceEncrypted: Boolean?,
        val userUnlocked: Boolean?,
        val deviceSecure: Boolean?,
        val biometricStatus: String?,
        val biometricEnrolled: Boolean?,
        val deviceAdminActive: Boolean?,
        val profileOwner: Boolean?,
        val deviceOwner: Boolean?,
        val workProfilePresent: Boolean?,
        val adbEnabled: Boolean?,
        val developerOptions: Boolean?,
        val canRequestInstall: Boolean?,
    )

    data class Apps(
        val installedCount: Int?,
        val defaultBrowser: String?,
        val defaultDialer: String?,
        val defaultSms: String?,
        val defaultHome: String?,
        val installSource: String?,
        val accessibilityEnabledMaster: Boolean?,
        val a11yEnabledServices: List<String>?,
        val notificationListeners: List<String>?,
        val grantedPermissions: List<PermStatus>?,
    )

    data class PermStatus(val name: String, val granted: Boolean)

    data class Notifications(
        val foregroundChannelImportance: Int?,
        val postNotificationsGranted: Boolean?,
    )

    fun capture(service: A8sService, config: A8sAndroid.Config, verbose: Boolean): InfoSnapshot {
        val pm = service.packageManager
        val cr = service.contentResolver
        return InfoSnapshot(
            app = captureApp(service, verbose),
            identity = captureIdentity(cr, verbose),
            os = captureOs(cr, verbose),
            cellular = captureCellular(service, verbose),
            wifi = captureWifi(service, verbose),
            network = captureNetwork(service, verbose),
            battery = captureBattery(service, verbose),
            storage = captureStorage(service, verbose),
            memory = captureMemory(service, verbose),
            display = captureDisplay(service, verbose),
            audio = captureAudio(service, verbose),
            sensors = captureSensors(service),
            location = captureLocation(service, verbose),
            camera = captureCamera(service, verbose),
            connectivity = captureConnectivity(service, pm, verbose),
            power = capturePower(service, verbose),
            process = captureProcess(verbose),
            security = captureSecurity(service, verbose),
            apps = captureApps(service, verbose),
            notifications = captureNotifications(service),
            remotes = service.remoteStatuses(config),
            services = config.services.map { it.id },
            phonebookSize = config.phonebook.size,
            serviceUptimeMs = service.serviceUptimeMs(),
            a11yRunning = runCatching { A11yService.instance != null }.getOrNull(),
            projectionConsent = service.hasProjectionConsent(),
        )
    }

    private fun captureApp(service: A8sService, verbose: Boolean): App? = try {
        val pm = service.packageManager
        val info = pm.getPackageInfo(service.packageName, 0)
        val signing: String? = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            captureSigningSha(service)
        } else null
        val installSource: String? = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { pm.getInstallSourceInfo(service.packageName).installingPackageName } catch (_: Exception) { null }
        } else null
        App(
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                else @Suppress("DEPRECATION") info.versionCode.toLong(),
            packageName = service.packageName,
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime,
            signingCertSha256Prefix = signing,
            installSource = installSource,
        )
    } catch (_: Exception) { null }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun captureSigningSha(service: A8sService): String? {
        return try {
            val pm = service.packageManager
            val info = pm.getPackageInfo(service.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            val sigs = if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            val first = sigs?.firstOrNull() ?: return null
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hex = md.digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
            hex.take(16)
        } catch (_: Exception) { null }
    }

    private fun captureIdentity(cr: android.content.ContentResolver, verbose: Boolean): Identity? = try {
        val androidId = if (verbose) try {
            Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null } else null
        Identity(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            brand = Build.BRAND,
            product = if (verbose) Build.PRODUCT else null,
            device = if (verbose) Build.DEVICE else null,
            hardware = if (verbose) Build.HARDWARE else null,
            board = if (verbose) Build.BOARD else null,
            socManufacturer = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
            socModel = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            fingerprint = if (verbose) Build.FINGERPRINT else null,
            display = if (verbose) Build.DISPLAY else null,
            tags = if (verbose) Build.TAGS else null,
            type = if (verbose) Build.TYPE else null,
            bootloader = if (verbose) Build.BOOTLOADER else null,
            radioVersion = if (verbose) try { Build.getRadioVersion() } catch (_: Exception) { null } else null,
            androidId = androidId,
        )
    } catch (_: Exception) { null }

    private fun captureOs(cr: android.content.ContentResolver, verbose: Boolean): Os? = try {
        val tz = TimeZone.getDefault()
        val autoTime = if (verbose) try {
            Settings.Global.getInt(cr, Settings.Global.AUTO_TIME) == 1
        } catch (_: Exception) { null } else null
        val autoTz = if (verbose) try {
            Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE) == 1
        } catch (_: Exception) { null } else null
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        Os(
            release = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            codename = if (verbose) Build.VERSION.CODENAME else null,
            incremental = if (verbose) Build.VERSION.INCREMENTAL else null,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            baseOs = if (verbose) Build.VERSION.BASE_OS else null,
            previewSdk = if (verbose) Build.VERSION.PREVIEW_SDK_INT else null,
            kernelVersion = System.getProperty("os.version"),
            kernelArch = if (verbose) (Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch")) else null,
            javaVm = if (verbose) System.getProperty("java.vm.version") else null,
            locale = Locale.getDefault().toLanguageTag(),
            timezoneId = tz.id,
            timezoneOffsetMin = (tz.getOffset(now) / 60_000),
            wallClockMs = now,
            bootTimeMs = now - elapsed,
            elapsedRealtimeMs = elapsed,
            uptimeMs = if (verbose) SystemClock.uptimeMillis() else null,
            autoTime = autoTime,
            autoTimezone = autoTz,
        )
    } catch (_: Exception) { null }

    private fun captureCellular(service: A8sService, verbose: Boolean): Cellular? { return try {
        val pm = service.packageManager
        val present = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        if (!present) return Cellular(
            present = false,
            carrierName = null, networkOperator = null, simOperatorName = null,
            simOperator = null, simCountryIso = null, networkCountryIso = null,
            simState = null, phoneCount = null, activeSubscriptionCount = null,
            networkType = null, voiceNetworkType = null, dataState = null,
            dataActivity = null, roaming = null, signalDbm = null, signalLevel = null,
            dataEnabled = null, dataRoamingEnabled = null, callState = null,
            cellTowers = null,
        )
        val tm = service.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        val hasPhoneState = ContextCompat.checkSelfPermission(service, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val hasFineLoc = ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        Cellular(
            present = true,
            carrierName = tm.networkOperatorName?.ifBlank { null },
            networkOperator = tm.networkOperator?.ifBlank { null },
            simOperatorName = tm.simOperatorName?.ifBlank { null },
            simOperator = tm.simOperator?.ifBlank { null },
            simCountryIso = tm.simCountryIso?.ifBlank { null },
            networkCountryIso = tm.networkCountryIso?.ifBlank { null },
            simState = simStateName(tm.simState),
            phoneCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount
                else @Suppress("DEPRECATION") tm.phoneCount,
            activeSubscriptionCount = captureActiveSubscriptionCount(service, hasPhoneState),
            networkType = if (hasPhoneState) attempt { networkTypeName(tm.dataNetworkType) } else null,
            voiceNetworkType = if (verbose && hasPhoneState) attempt { networkTypeName(tm.voiceNetworkType) } else null,
            dataState = dataStateName(tm.dataState),
            dataActivity = if (verbose) dataActivityName(tm.dataActivity) else null,
            roaming = try { tm.isNetworkRoaming } catch (_: Exception) { null },
            signalDbm = captureSignalDbm(tm, hasPhoneState),
            signalLevel = captureSignalLevel(tm, hasPhoneState),
            dataEnabled = if (hasPhoneState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { tm.isDataEnabled } catch (_: Exception) { null }
            } else null,
            dataRoamingEnabled = if (verbose && hasPhoneState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { tm.isDataRoamingEnabled } catch (_: Exception) { null }
            } else null,
            callState = callStateName(tm.callState),
            cellTowers = if (verbose && hasFineLoc && hasPhoneState) {
                try { tm.allCellInfo?.size } catch (_: Exception) { null }
            } else null,
        )
    } catch (_: Exception) { null } }

    @SuppressLint("MissingPermission")
    private fun captureActiveSubscriptionCount(service: A8sService, hasPhoneState: Boolean): Int? {
        if (!hasPhoneState) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return null
        return try {
            val sm = service.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            sm?.activeSubscriptionInfoCount
        } catch (_: Exception) { null }
    }

    private fun captureSignalDbm(tm: TelephonyManager, hasPhoneState: Boolean): Int? {
        if (!hasPhoneState) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
        } catch (_: Exception) { null }
    }

    private fun captureSignalLevel(tm: TelephonyManager, hasPhoneState: Boolean): Int? {
        if (!hasPhoneState) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            tm.signalStrength?.level
        } catch (_: Exception) { null }
    }

    private fun captureWifi(service: A8sService, verbose: Boolean): Wifi? { return try {
        val wm = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo
        val rssi = attempt { info?.rssi }
        val onR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        Wifi(
            enabled = attempt { wm.isWifiEnabled },
            band5GhzSupported = if (verbose) attempt { wm.is5GHzBandSupported } else null,
            band6GhzSupported = if (verbose && onR) attempt { wm.is6GHzBandSupported } else null,
            standard11axSupported = if (verbose && onR) attempt {
                wm.isWifiStandardSupported(android.net.wifi.ScanResult.WIFI_STANDARD_11AX)
            } else null,
            ssid = if (verbose) info?.ssid?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } else null,
            bssid = if (verbose) info?.bssid
                ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" } else null,
            linkSpeedMbps = attempt { info?.linkSpeed },
            txLinkSpeedMbps = if (onQ) attempt { info?.txLinkSpeedMbps } else null,
            rxLinkSpeedMbps = if (onQ) attempt { info?.rxLinkSpeedMbps } else null,
            frequencyMhz = attempt { info?.frequency },
            rssiDbm = rssi,
            rssiBars = if (rssi != null) attempt { WifiManager.calculateSignalLevel(rssi, 5) } else null,
            standard = if (verbose && onR) attempt { wifiStandardName(info?.wifiStandard) } else null,
            ipAddress = if (verbose) ipv4FromInt(info?.ipAddress) else null,
        )
    } catch (_: Exception) { null } }

    private fun ipv4FromInt(addr: Int?): String? {
        if (addr == null || addr == 0) return null
        return "${addr and 0xff}.${(addr shr 8) and 0xff}.${(addr shr 16) and 0xff}.${(addr shr 24) and 0xff}"
    }

    private fun wifiStandardName(s: Int?): String? = when (s) {
        null -> null
        0 -> "UNKNOWN"
        1 -> "LEGACY"
        4 -> "11N"
        5 -> "11AC"
        6 -> "11AX"
        7 -> "11AD"
        8 -> "11BE"
        else -> "STD_$s"
    }

    private fun captureNetwork(service: A8sService, verbose: Boolean): Network? { return try {
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val link = active?.let { cm.getLinkProperties(it) }
        val (v4, v6) = if (verbose) collectIpAddresses() else Pair(null, null)
        Network(
            activeTransport = caps?.let { transportName(it) },
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = attempt { cm.isActiveNetworkMetered },
            restrictBackground = if (verbose)
                restrictBackgroundName(attempt { cm.restrictBackgroundStatus }) else null,
            downstreamKbps = if (verbose) caps?.linkDownstreamBandwidthKbps else null,
            upstreamKbps = if (verbose) caps?.linkUpstreamBandwidthKbps else null,
            ipv4Addresses = v4,
            ipv6Addresses = v6,
            gateway = if (verbose)
                link?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress else null,
            dnsServers = if (verbose) link?.dnsServers?.mapNotNull { it.hostAddress } else null,
            privateDns = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) attempt {
                if (link?.isPrivateDnsActive == true) (link.privateDnsServerName ?: "active") else null
            } else null,
            mtu = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                attempt { link?.mtu } else null,
            interfaceName = if (verbose) link?.interfaceName else null,
            httpProxy = if (verbose) link?.httpProxy?.toString() else null,
            vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            ourAppRxBytes = attempt { android.net.TrafficStats.getUidRxBytes(Process.myUid()) },
            ourAppTxBytes = attempt { android.net.TrafficStats.getUidTxBytes(Process.myUid()) },
            totalRxBytes = if (verbose) attempt { android.net.TrafficStats.getTotalRxBytes() } else null,
            totalTxBytes = if (verbose) attempt { android.net.TrafficStats.getTotalTxBytes() } else null,
        )
    } catch (_: Exception) { null } }

    private fun collectIpAddresses(): Pair<List<String>?, List<String>?> = try {
        val v4 = mutableListOf<String>()
        val v6 = mutableListOf<String>()
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                val cleaned = host.substringBefore('%')
                if (addr is java.net.Inet4Address) v4.add("${iface.name}: $cleaned")
                else if (addr is java.net.Inet6Address) v6.add("${iface.name}: $cleaned")
            }
        }
        Pair(v4.takeIf { it.isNotEmpty() }, v6.takeIf { it.isNotEmpty() })
    } catch (_: Exception) { Pair(null, null) }

    private fun captureBattery(service: A8sService, verbose: Boolean): Battery? = try {
        val intent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val statusInt = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL
        val plugInt = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        Battery(
            percent = pct,
            charging = if (statusInt == -1) null else charging,
            plug = plugName(plugInt),
            status = batteryStatusName(statusInt),
            health = batteryHealthName(intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1),
            technology = if (verbose) intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) else null,
            temperatureC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null,
            voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it >= 0 },
            currentNowUa = bm?.let { attempt { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } },
            currentAvgUa = bm?.let { attempt { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) } },
            chargeCounterUah = if (verbose)
                bm?.let { attempt { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } } else null,
            energyCounterNwh = if (verbose)
                bm?.let { attempt { it.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) } } else null,
            powerSaveMode = (service.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode,
            chargeTimeRemainingMs = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { bm?.computeChargeTimeRemaining()?.takeIf { it >= 0 } } catch (_: Exception) { null }
            } else null,
            batteryLow = if (verbose) intent?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) else null,
        )
    } catch (_: Exception) { null }

    private fun captureStorage(service: A8sService, verbose: Boolean): Storage? = try {
        val data = service.filesDir
        val total = try { data.totalSpace } catch (_: Exception) { null }
        val free = try { data.usableSpace } catch (_: Exception) { null }
        val (cache, dataB, app) = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureOwnAppStorageStats(service)
        } else Triple(null, null, null)
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            captureExternalVolumes(service, verbose)
        } else null
        Storage(
            internalTotalBytes = total,
            internalFreeBytes = free,
            ourCacheBytes = cache,
            ourDataBytes = dataB,
            ourAppBytes = app,
            externalVolumes = volumes,
        )
    } catch (_: Exception) { null }

    private fun captureOwnAppStorageStats(service: A8sService): Triple<Long?, Long?, Long?> { return try {
        val ssm = service.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            ?: return Triple(null, null, null)
        val uuid = android.os.storage.StorageManager.UUID_DEFAULT
        val stats = ssm.queryStatsForUid(uuid, Process.myUid())
        Triple(stats.cacheBytes, stats.dataBytes, stats.appBytes)
    } catch (_: Exception) { Triple(null, null, null) } }

    private fun captureExternalVolumes(service: A8sService, verbose: Boolean): List<ExternalVolume>? { return try {
        val sm = service.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
            ?: return null
        sm.storageVolumes.map { v ->
            ExternalVolume(
                description = try { v.getDescription(service) } catch (_: Exception) { null },
                state = try { v.state } catch (_: Exception) { null },
                isRemovable = try { v.isRemovable } catch (_: Exception) { null },
                isPrimary = if (verbose) try { v.isPrimary } catch (_: Exception) { null } else null,
                isEmulated = if (verbose) try { v.isEmulated } catch (_: Exception) { null } else null,
            )
        }
    } catch (_: Exception) { null } }

    private fun captureMemory(service: A8sService, verbose: Boolean): Memory? = try {
        val am = service.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val procStatus = if (verbose) readProcStatusMap() else readProcStatusMap()
        val rt = Runtime.getRuntime()
        val pss = if (verbose && am != null) try {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss?.toLong()?.times(1024L)
        } catch (_: Exception) { null } else null
        val swap = if (verbose) readMeminfoSwap() else Pair(null, null)
        Memory(
            ramTotalBytes = mi.totalMem.takeIf { it > 0 },
            ramAvailBytes = mi.availMem.takeIf { it > 0 },
            lowMemory = mi.lowMemory,
            lowMemThresholdBytes = if (verbose) mi.threshold else null,
            rssBytes = procStatus["VmRSS"]?.let { kbToBytes(it) },
            vssBytes = if (verbose) procStatus["VmSize"]?.let { kbToBytes(it) } else null,
            nativeHeapAllocatedBytes = if (verbose) try { Debug.getNativeHeapAllocatedSize() } catch (_: Exception) { null } else null,
            nativeHeapSizeBytes = if (verbose) try { Debug.getNativeHeapSize() } catch (_: Exception) { null } else null,
            nativeHeapFreeBytes = if (verbose) try { Debug.getNativeHeapFreeSize() } catch (_: Exception) { null } else null,
            javaHeapUsedBytes = (rt.totalMemory() - rt.freeMemory()),
            javaHeapMaxBytes = if (verbose) rt.maxMemory() else null,
            pssBytes = pss,
            threadCount = procStatus["Threads"]?.toIntOrNull(),
            openFdCount = if (verbose) try { File("/proc/self/fd").list()?.size } catch (_: Exception) { null } else null,
            gcCount = if (verbose) runtimeStat("art.gc.gc-count") else null,
            gcBytesAllocated = if (verbose) runtimeStat("art.gc.bytes-allocated") else null,
            swapTotalBytes = swap.first,
            swapFreeBytes = swap.second,
        )
    } catch (_: Exception) { null }

    private fun runtimeStat(key: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Debug.getRuntimeStat(key) else null
    } catch (_: Exception) { null }

    private fun readProcStatusMap(): Map<String, String> = try {
        val map = mutableMapOf<String, String>()
        File("/proc/self/status").useLines { lines ->
            for (line in lines) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val rest = line.substring(idx + 1).trim()
                val value = rest.split(Regex("\\s+")).firstOrNull() ?: continue
                map[key] = value
            }
        }
        map
    } catch (_: Exception) { emptyMap() }

    private fun kbToBytes(kbStr: String): Long? = kbStr.toLongOrNull()?.times(1024L)

    private fun readMeminfoSwap(): Pair<Long?, Long?> = try {
        var total: Long? = null
        var free: Long? = null
        File("/proc/meminfo").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("SwapTotal:")) {
                    total = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.times(1024L)
                } else if (line.startsWith("SwapFree:")) {
                    free = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.times(1024L)
                }
            }
        }
        Pair(total, free)
    } catch (_: Exception) { Pair(null, null) }

    private fun captureDisplay(service: A8sService, verbose: Boolean): Display? = try {
        @Suppress("DEPRECATION")
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) service.display else @Suppress("DEPRECATION") wm?.defaultDisplay
        val (w, h) = captureDisplaySize(wm)
        val res = service.resources
        val cr = service.contentResolver
        val pm = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val brightnessMode = attempt { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) }
        val brightnessLevel = attempt { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }
        val timeoutMs = if (verbose)
            attempt { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) } else null
        Display(
            widthPx = w,
            heightPx = h,
            densityDpi = res.displayMetrics.densityDpi,
            densityScale = if (verbose) res.displayMetrics.density else null,
            refreshRateHz = display?.refreshRate,
            hdrTypes = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { display?.hdrCapabilities?.supportedHdrTypes?.toList() } catch (_: Exception) { null }
            } else null,
            wideColorGamut = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { display?.isWideColorGamut } catch (_: Exception) { null }
            } else null,
            rotation = display?.rotation,
            state = displayStateName(display?.state),
            brightnessMode = brightnessMode,
            brightnessLevel = brightnessLevel,
            screenTimeoutMs = timeoutMs,
            interactive = pm?.isInteractive,
            keyguardLocked = km?.isKeyguardLocked,
            keyguardSecure = if (verbose) km?.isKeyguardSecure else null,
            deviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) km?.isDeviceLocked else null,
            fontScale = if (verbose) res.configuration.fontScale else null,
            darkMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun captureDisplaySize(wm: android.view.WindowManager?): Pair<Int?, Int?> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm?.maximumWindowMetrics?.bounds
            Pair(bounds?.width(), bounds?.height())
        } else {
            val dm = android.util.DisplayMetrics()
            wm?.defaultDisplay?.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    } catch (_: Exception) { Pair(null, null) }

    private fun captureAudio(service: A8sService, verbose: Boolean): Audio? { return try {
        val am = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        Audio(
            ringerMode = ringerModeName(am.ringerMode),
            musicVolumePercent = streamVolumePct(am, AudioManager.STREAM_MUSIC),
            ringVolumePercent = if (verbose) streamVolumePct(am, AudioManager.STREAM_RING) else null,
            notificationVolumePercent = if (verbose) streamVolumePct(am, AudioManager.STREAM_NOTIFICATION) else null,
            voiceCallVolumePercent = if (verbose) streamVolumePct(am, AudioManager.STREAM_VOICE_CALL) else null,
            alarmVolumePercent = if (verbose) streamVolumePct(am, AudioManager.STREAM_ALARM) else null,
            musicActive = attempt { am.isMusicActive },
            mode = audioModeName(am.mode),
            speakerOn = if (verbose) attempt { am.isSpeakerphoneOn } else null,
            microphoneMute = if (verbose) attempt { am.isMicrophoneMute } else null,
            wiredHeadset = audioDevicePresent(am, intArrayOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)),
            usbAudio = if (verbose) audioDevicePresent(am, intArrayOf(
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY)) else null,
            btAudio = audioDevicePresent(am, intArrayOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)),
            hdmiOut = if (verbose) audioDevicePresent(am, intArrayOf(AudioDeviceInfo.TYPE_HDMI)) else null,
            dndFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                interruptionFilterName(nm?.currentInterruptionFilter) else null,
            notificationsEnabled = nm?.areNotificationsEnabled(),
        )
    } catch (_: Exception) { null } }

    private fun streamVolumePct(am: AudioManager, stream: Int): Int? = try {
        val cur = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) null else (cur * 100 / max)
    } catch (_: Exception) { null }

    private fun audioDevicePresent(am: AudioManager, types: IntArray): Boolean? = try {
        val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devs.any { d -> types.any { it == d.type } }
    } catch (_: Exception) { null }

    private fun captureSensors(service: A8sService): Sensors? { return try {
        val sm = service.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val all = try { sm.getSensorList(Sensor.TYPE_ALL) } catch (_: Exception) { emptyList<Sensor>() }
        val present = all.mapNotNull { sensorTypeName(it.type) }.distinct().sorted()
        Sensors(
            present = present.takeIf { it.isNotEmpty() },
            pressureHpa = null,
            lightLux = null,
            proximityCm = null,
        )
    } catch (_: Exception) { null } }

    private fun captureLocation(service: A8sService, verbose: Boolean): LocationInfo? { return try {
        val lm = service.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val gps = attempt { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
        val net = attempt { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            attempt { lm.isLocationEnabled } else null
        val fix = if (verbose) lastKnownFix(service, lm) else null
        LocationInfo(
            locationEnabled = enabled,
            gpsProviderEnabled = gps,
            networkProviderEnabled = net,
            lastFixProvider = fix?.provider,
            lastFixLatitude = fix?.latitude,
            lastFixLongitude = fix?.longitude,
            lastFixAccuracyM = fix?.takeIf { it.hasAccuracy() }?.accuracy,
            lastFixAgeMs = fix?.let { System.currentTimeMillis() - it.time },
            lastFixFromMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) fix?.isFromMockProvider else null,
        )
    } catch (_: Exception) { null } }

    private fun lastKnownFix(service: A8sService, lm: LocationManager): android.location.Location? {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null
        return try {
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            listOfNotNull(gps, net).maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    private fun captureCamera(service: A8sService, verbose: Boolean): Camera? { return try {
        val cm = service.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val ids = try { cm.cameraIdList.toList() } catch (_: Exception) { emptyList() }
        val specs = if (verbose) ids.map { id -> describeCamera(cm, id) } else null
        Camera(count = ids.size, cameras = specs)
    } catch (_: Exception) { null } }

    private fun describeCamera(cm: CameraManager, id: String): CameraSpec = try {
        val ch = cm.getCameraCharacteristics(id)
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> null
        }
        val pixel = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val mp = pixel?.let { (it.width.toLong() * it.height.toLong()) / 1_000_000.0 }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxStill = try {
            map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?.let { "${it.width}x${it.height}" }
        } catch (_: Exception) { null }
        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        val hwLevel = hardwareLevelName(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
        CameraSpec(
            id = id, facing = facing, megapixels = mp,
            maxStillResolution = maxStill, flashAvailable = flash, hardwareLevel = hwLevel,
        )
    } catch (_: Exception) {
        CameraSpec(
            id = id, facing = null, megapixels = null,
            maxStillResolution = null, flashAvailable = null, hardwareLevel = null,
        )
    }

    private fun captureConnectivity(service: A8sService, pm: PackageManager, verbose: Boolean): Connectivity? = try {
        val nfc = try { NfcAdapter.getDefaultAdapter(service) } catch (_: Exception) { null }
        val bt = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Exception) { null }
        val battIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val airplane = try {
            Settings.Global.getInt(service.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1
        } catch (_: Exception) { null }
        Connectivity(
            bluetoothSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            bluetoothLeSupported = if (verbose) pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) else null,
            bluetoothEnabled = try { bt?.isEnabled } catch (_: Exception) { null },
            bluetoothState = if (verbose) bluetoothStateName(try { bt?.state } catch (_: Exception) { null }) else null,
            nfcSupported = pm.hasSystemFeature(PackageManager.FEATURE_NFC),
            nfcEnabled = try { nfc?.isEnabled } catch (_: Exception) { null },
            hceSupported = if (verbose)
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) else null,
            telephonySupported = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            usbHostSupported = if (verbose) pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST) else null,
            usbConnected = (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0,
            airplaneMode = airplane,
        )
    } catch (_: Exception) { null }

    private fun capturePower(service: A8sService, verbose: Boolean): Power? { return try {
        val pm = service.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val standby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) try {
            val usm = service.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            standbyBucketName(usm?.appStandbyBucket)
        } catch (_: Exception) { null } else null
        val onM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        Power(
            batterySaver = attempt { pm.isPowerSaveMode },
            deviceIdle = if (onM) attempt { pm.isDeviceIdleMode } else null,
            standbyBucket = standby,
            ignoringBatteryOptimizations = if (onM)
                attempt { pm.isIgnoringBatteryOptimizations(service.packageName) } else null,
            interactive = attempt { pm.isInteractive },
            thermalStatus = if (onQ)
                thermalStatusName(attempt { pm.currentThermalStatus }) else null,
            sustainedPerfSupported = if (verbose)
                attempt { pm.isSustainedPerformanceModeSupported } else null,
        )
    } catch (_: Exception) { null } }

    private fun captureProcess(verbose: Boolean): ProcessInfo? = try {
        val procStat = readProcStatFields()
        val cpuJiffies = procStat.getOrNull(13)?.toLongOrNull()?.plus(procStat.getOrNull(14)?.toLongOrNull() ?: 0L)
        val ticks = 100L
        val cpuMs = cpuJiffies?.let { it * 1000L / ticks }
        val startJiffies = procStat.getOrNull(21)?.toLongOrNull()
        val startMs = startJiffies?.let {
            val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            boot + (it * 1000L / ticks)
        }
        ProcessInfo(
            pid = Process.myPid(),
            uid = Process.myUid(),
            processName = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                attempt { android.app.Application.getProcessName() } else null,
            activeThreadsJvm = if (verbose) Thread.activeCount() else null,
            processStartTimeMs = startMs,
            cpuTimeMs = if (verbose) cpuMs else null,
        )
    } catch (_: Exception) { null }

    private fun readProcStatFields(): List<String> {
        return try {
            val raw = File("/proc/self/stat").readText().trim()
            val close = raw.indexOf(')')
            if (close <= 0) return emptyList()
            val before = raw.substring(0, raw.indexOf('(')).trim()
            val after = raw.substring(close + 1).trim()
            val firstTwo = listOf(before, raw.substring(raw.indexOf('(') + 1, close))
            firstTwo + after.split(Regex("\\s+"))
        } catch (_: Exception) { emptyList() }
    }

    private fun captureSecurity(service: A8sService, verbose: Boolean): Security? = try {
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val sm = service.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val um = service.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
        val dpm = service.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val cr = service.contentResolver
        val pm = service.packageManager
        val biometricStatus = captureBiometricStatus(service)
        Security(
            deviceEncrypted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) try {
                sm?.isEncrypted(android.os.Environment.getDataDirectory())
            } catch (_: Exception) { null } else null,
            userUnlocked = if (verbose) attempt { um?.isUserUnlocked } else null,
            deviceSecure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                attempt { km?.isDeviceSecure } else null,
            biometricStatus = biometricStatus,
            biometricEnrolled = biometricStatus?.let { it == "SUCCESS" },
            deviceAdminActive = if (verbose)
                attempt { (dpm?.activeAdmins?.size ?: 0) > 0 } else null,
            profileOwner = if (verbose)
                attempt { dpm?.isProfileOwnerApp(service.packageName) } else null,
            deviceOwner = if (verbose)
                attempt { dpm?.isDeviceOwnerApp(service.packageName) } else null,
            workProfilePresent = if (verbose)
                attempt { (um?.userProfiles?.size ?: 0) > 1 } else null,
            adbEnabled = attempt { Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED) == 1 },
            developerOptions = attempt {
                Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
            },
            canRequestInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                attempt { pm.canRequestPackageInstalls() } else null,
        )
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun captureBiometricStatus(service: A8sService): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val bm = service.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            val code = bm?.canAuthenticate() ?: return null
            when (code) {
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS -> "SUCCESS"
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO_HARDWARE"
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "HW_UNAVAILABLE"
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NONE_ENROLLED"
                else -> "UNKNOWN"
            }
        } catch (_: Exception) { null }
    }

    private fun captureApps(service: A8sService, verbose: Boolean): Apps? = try {
        val pm = service.packageManager
        val cr = service.contentResolver
        val installed = if (verbose) try { pm.getInstalledPackages(0).size } catch (_: Exception) { null } else null
        val defaultBrowser = if (verbose) defaultBrowserPackage(pm) else null
        val defaultDialer = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) try {
            (service.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } catch (_: Exception) { null } else null
        val defaultSms = try {
            android.provider.Telephony.Sms.getDefaultSmsPackage(service)
        } catch (_: Exception) { null }
        val defaultHome = if (verbose) defaultHomePackage(pm) else null
        val installSource = if (verbose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) try {
            pm.getInstallSourceInfo(service.packageName).installingPackageName
        } catch (_: Exception) { null } else null
        val a11yEnabledMaster = try {
            Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        } catch (_: Exception) { null }
        val a11yEnabled = try {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(":")?.filter { it.isNotBlank() }
        } catch (_: Exception) { null }
        val notifListeners = try {
            NotificationManagerCompat.getEnabledListenerPackages(service).toList()
        } catch (_: Exception) { null }
        Apps(
            installedCount = installed,
            defaultBrowser = defaultBrowser,
            defaultDialer = defaultDialer,
            defaultSms = defaultSms,
            defaultHome = defaultHome,
            installSource = installSource,
            accessibilityEnabledMaster = a11yEnabledMaster,
            a11yEnabledServices = a11yEnabled,
            notificationListeners = notifListeners,
            grantedPermissions = capturePermissions(service),
        )
    } catch (_: Exception) { null }

    private fun defaultBrowserPackage(pm: PackageManager): String? = try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (_: Exception) { null }

    private fun defaultHomePackage(pm: PackageManager): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (_: Exception) { null }

    private fun capturePermissions(service: A8sService): List<PermStatus>? = try {
        val pm = service.packageManager
        val info = pm.getPackageInfo(service.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.map { name ->
            PermStatus(
                name = name.removePrefix("android.permission."),
                granted = ContextCompat.checkSelfPermission(service, name) == PackageManager.PERMISSION_GRANTED,
            )
        }
    } catch (_: Exception) { null }

    private fun captureNotifications(service: A8sService): Notifications? = try {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val ch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try {
            nm?.getNotificationChannel("a8s_android_channel")?.importance
        } catch (_: Exception) { null } else null
        val postGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) try {
            ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { null } else true
        Notifications(
            foregroundChannelImportance = ch,
            postNotificationsGranted = postGranted,
        )
    } catch (_: Exception) { null }

    // ── enum/constant translation helpers ──

    private fun simStateName(s: Int): String? = when (s) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
        else -> null
    }

    private fun networkTypeName(t: Int): String? = when (t) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        else -> null
    }

    private fun dataStateName(s: Int): String? = when (s) {
        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
        TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
        else -> null
    }

    private fun dataActivityName(a: Int): String? = when (a) {
        TelephonyManager.DATA_ACTIVITY_NONE -> "NONE"
        TelephonyManager.DATA_ACTIVITY_IN -> "IN"
        TelephonyManager.DATA_ACTIVITY_OUT -> "OUT"
        TelephonyManager.DATA_ACTIVITY_INOUT -> "INOUT"
        TelephonyManager.DATA_ACTIVITY_DORMANT -> "DORMANT"
        else -> null
    }

    private fun callStateName(s: Int): String? = when (s) {
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else -> null
    }

    private fun transportName(c: NetworkCapabilities): String {
        val parts = mutableListOf<String>()
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts.add("WIFI")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts.add("CELLULAR")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts.add("ETHERNET")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts.add("VPN")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) parts.add("BLUETOOTH")
        return if (parts.isEmpty()) "OTHER" else parts.joinToString("+")
    }

    private fun restrictBackgroundName(s: Int?): String? = when (s) {
        null -> null
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "DISABLED"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "WHITELISTED"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ENABLED"
        else -> null
    }

    private fun plugName(p: Int): String? = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        4 -> "DOCK"
        0 -> "NONE"
        else -> null
    }

    private fun batteryStatusName(s: Int): String? = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
        else -> null
    }

    private fun batteryHealthName(h: Int): String? = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPEC_FAILURE"
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "UNKNOWN"
        else -> null
    }

    private fun displayStateName(s: Int?): String? = when (s) {
        null -> null
        AndroidDisplay.STATE_ON -> "ON"
        AndroidDisplay.STATE_OFF -> "OFF"
        AndroidDisplay.STATE_DOZE -> "DOZE"
        AndroidDisplay.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        AndroidDisplay.STATE_VR -> "VR"
        AndroidDisplay.STATE_ON_SUSPEND -> "ON_SUSPEND"
        AndroidDisplay.STATE_UNKNOWN -> "UNKNOWN"
        else -> null
    }

    private fun ringerModeName(m: Int): String? = when (m) {
        AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
        AudioManager.RINGER_MODE_SILENT -> "SILENT"
        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
        else -> null
    }

    private fun audioModeName(m: Int): String? = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> null
    }

    private fun interruptionFilterName(f: Int?): String? = when (f) {
        null -> null
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "UNKNOWN"
        else -> null
    }

    private fun standbyBucketName(b: Int?): String? = when (b) {
        null -> null
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "BUCKET_$b"
    }

    private fun thermalStatusName(s: Int?): String? = when (s) {
        null -> null
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> null
    }

    private fun bluetoothStateName(s: Int?): String? = when (s) {
        null -> null
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> null
    }

    private fun hardwareLevelName(l: Int?): String? = when (l) {
        null -> null
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> null
    }

    private fun sensorTypeName(t: Int): String? = when (t) {
        Sensor.TYPE_ACCELEROMETER -> "ACCELEROMETER"
        Sensor.TYPE_MAGNETIC_FIELD -> "MAGNETIC_FIELD"
        Sensor.TYPE_GYROSCOPE -> "GYROSCOPE"
        Sensor.TYPE_LIGHT -> "LIGHT"
        Sensor.TYPE_PRESSURE -> "PRESSURE"
        Sensor.TYPE_PROXIMITY -> "PROXIMITY"
        Sensor.TYPE_GRAVITY -> "GRAVITY"
        Sensor.TYPE_LINEAR_ACCELERATION -> "LINEAR_ACCELERATION"
        Sensor.TYPE_ROTATION_VECTOR -> "ROTATION_VECTOR"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "AMBIENT_TEMPERATURE"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "HUMIDITY"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "SIGNIFICANT_MOTION"
        Sensor.TYPE_STEP_COUNTER -> "STEP_COUNTER"
        Sensor.TYPE_STEP_DETECTOR -> "STEP_DETECTOR"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "GAME_ROTATION_VECTOR"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "GEOMAGNETIC_ROTATION"
        Sensor.TYPE_HEART_RATE -> "HEART_RATE"
        else -> null
    }
}
