# `/info` field research

Spec for expanding the `/info` slash-command response. Catalogues every
device-fact a foreground service with our current grant set can read.

## Constraints recap

- **minSdk 26 (Android 8.0), targetSdk 34 (Android 14).** Anything API 26+ is
  free; later additions need `Build.VERSION.SDK_INT` gating.
- **Granted runtime perms**: `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`,
  `READ_PHONE_STATE`, `READ_CONTACTS`, `POST_NOTIFICATIONS`, `CAMERA`,
  `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `READ_MEDIA_IMAGES`, `READ_MEDIA_AUDIO`.
- **Granted normal/manifest perms**: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (+ subtypes),
  `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  `SCHEDULE_EXACT_ALARM`, `BIND_NOTIFICATION_LISTENER_SERVICE`,
  `REQUEST_INSTALL_PACKAGES`.
- **Granted special**: AccessibilityService bound, MediaProjection consent.
- **Not granted**: `READ_PHONE_NUMBERS`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`,
  `READ_CALENDAR`, `GET_ACCOUNTS`, `READ_PRIVILEGED_PHONE_STATE` (signature
  only), `PACKAGE_USAGE_STATS` (special), `WRITE_SECURE_SETTINGS` (signature),
  root.

## Verdict legend

- **default** — include in plain `/info`. Cheap, non-leaky, useful triage.
- **verbose** — include behind `/info verbose` (or equivalent flag). Either
  privacy-tinged (SSID, IP, BSSID, phone number, location) or noisy.
- **skip** — don't surface. Either requires perm we don't hold, deprecated,
  privacy-toxic, or returns sentinel garbage on modern Android.

## Safety legend

- **safe** — fine in any log destination.
- **identifier** — unique enough to fingerprint device/user. Verbose only.
- **location-leak** — reveals where the device is. Verbose only.
- **secret-adjacent** — could correlate with credentials/account. Verbose only.

---

## 1. Identity

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Manufacturer | `Build.MANUFACTURER` | none | 1 | default | safe | already shown |
| Model | `Build.MODEL` | none | 1 | default | safe | already shown |
| Brand | `Build.BRAND` | none | 1 | default | safe | sometimes differs from manufacturer (Pixel/Google) |
| Product | `Build.PRODUCT` | none | 1 | verbose | safe | factory codename |
| Device | `Build.DEVICE` | none | 1 | verbose | safe | hardware codename (e.g. `oriole`) |
| Hardware | `Build.HARDWARE` | none | 1 | verbose | safe | SoC family hint |
| Board | `Build.BOARD` | none | 1 | verbose | safe | |
| SoC manufacturer | `Build.SOC_MANUFACTURER` | none | 31 | verbose | safe | "Qualcomm" / "Google" — gate on SDK_INT >= 31 |
| SoC model | `Build.SOC_MODEL` | none | 31 | verbose | safe | "Tensor G2" etc. |
| Build fingerprint | `Build.FINGERPRINT` | none | 1 | verbose | identifier | high entropy (build/tag/incremental); useful for OTA debug |
| Build display | `Build.DISPLAY` | none | 3 | verbose | safe | user-visible build id |
| Build tags | `Build.TAGS` | none | 1 | verbose | safe | `release-keys` vs `test-keys` |
| Build type | `Build.TYPE` | none | 1 | verbose | safe | `user` / `userdebug` / `eng` |
| Bootloader version | `Build.BOOTLOADER` | none | 8 | verbose | safe | |
| Radio version | `Build.getRadioVersion()` | none | 14 | verbose | safe | baseband/modem firmware |
| Serial number | `Build.getSerial()` | `READ_PRIVILEGED_PHONE_STATE` (29+) | 26 (deprecated 26) | **skip** | identifier | API 26-28 needed `READ_PHONE_STATE`; API 29+ is privileged-only and returns `Build.UNKNOWN` for normal apps |
| Android ID | `Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID)` | none | 26+ scoped | verbose | identifier | scoped per app-signing-key + per user since 8.0; stable per-install identifier |
| Install ID (own pkg) | `packageManager.getPackageInfo(packageName, 0).firstInstallTime / lastUpdateTime` | none | 9 | default | safe | useful for "have I been reinstalled today" |
| Package name | `packageName` | none | 1 | default | safe | |
| App signing cert SHA-256 | `packageManager.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES).signingInfo` | none | 28 | verbose | safe | first 8 hex chars enough for "is this the prod build" |

## 2. OS / runtime

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Android release | `Build.VERSION.RELEASE` | none | 1 | default | safe | shown |
| SDK INT | `Build.VERSION.SDK_INT` | none | 4 | default | safe | shown |
| Codename | `Build.VERSION.CODENAME` | none | 4 | verbose | safe | `REL` on stable builds; non-`REL` on previews |
| Incremental | `Build.VERSION.INCREMENTAL` | none | 1 | verbose | safe | |
| Security patch | `Build.VERSION.SECURITY_PATCH` | none | 23 | default | safe | YYYY-MM-DD; useful — flags stale devices |
| Base OS | `Build.VERSION.BASE_OS` | none | 23 | verbose | safe | |
| Preview SDK | `Build.VERSION.PREVIEW_SDK_INT` | none | 23 | verbose | safe | 0 on shipping releases |
| Kernel version | `System.getProperty("os.version")` | none | 1 | default | safe | matches `uname -r`; e.g. `5.10.81-android13-...` |
| Kernel arch | `System.getProperty("os.arch")` or `Build.SUPPORTED_ABIS[0]` | none | 21 | verbose | safe | `arm64-v8a` etc. |
| Java VM | `System.getProperty("java.vm.version")` | none | 1 | verbose | safe | ART version |
| Locale | `Locale.getDefault().toLanguageTag()` | none | 1 | default | safe | `en-US` |
| Timezone id | `TimeZone.getDefault().id` | none | 1 | default | safe | `America/Los_Angeles` |
| Timezone offset | `TimeZone.getDefault().rawOffset` (+ DST offset) | none | 1 | default | safe | minutes from UTC |
| Wall-clock now | `System.currentTimeMillis()` | none | 1 | default | safe | ISO-format it |
| Boot time | `System.currentTimeMillis() - SystemClock.elapsedRealtime()` | none | 1 | default | safe | ISO-format |
| System uptime (since boot, incl. deep sleep) | `SystemClock.elapsedRealtime()` | none | 1 | default | safe | distinct from our service uptime |
| System uptime (excluding deep sleep) | `SystemClock.uptimeMillis()` | none | 1 | verbose | safe | "awake time" |
| Auto-time enabled | `Settings.Global.getInt(cr, Settings.Global.AUTO_TIME)` | none | 17 | verbose | safe | NTP sync flag — 1 if enabled |
| Auto-timezone enabled | `Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE)` | none | 17 | verbose | safe | |

## 3. Cellular / SIM

`READ_PHONE_STATE` is held — this unlocks most non-identifier telephony state.
`getAllCellInfo()` and dBm signal additionally consult location perms; we hold
`ACCESS_FINE_LOCATION` so they're available.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Has telephony | `packageManager.hasSystemFeature(FEATURE_TELEPHONY)` | none | 7 | default | safe | gate everything below |
| Carrier name | `TelephonyManager.getNetworkOperatorName()` | none | 1 | default | safe | "T-Mobile" |
| Carrier MCC/MNC | `TelephonyManager.getNetworkOperator()` | none | 1 | default | safe | 5-6 digit string |
| SIM operator name | `TelephonyManager.getSimOperatorName()` | none | 1 | default | safe | distinct from network when roaming |
| SIM operator MCC/MNC | `TelephonyManager.getSimOperator()` | none | 1 | default | safe | |
| SIM country ISO | `TelephonyManager.getSimCountryIso()` | none | 1 | default | safe | "us" |
| Network country ISO | `TelephonyManager.getNetworkCountryIso()` | none | 1 | default | safe | |
| SIM state | `TelephonyManager.getSimState()` | none | 1 | default | safe | READY/PIN/PUK/etc |
| Active subscription count | `SubscriptionManager.activeSubscriptionInfoCount` | `READ_PHONE_STATE` | 22 | default | safe | |
| Subscription slot info | `SubscriptionManager.getActiveSubscriptionInfoList()` | `READ_PHONE_STATE` | 22 | verbose | identifier | reveals carrier per slot; ICCID is privileged on 29+ so naturally redacted |
| Phone count (slots) | `TelephonyManager.getPhoneCount()` / `activeModemCount` | none | 23 / 30 | default | safe | |
| Network type | `TelephonyManager.getDataNetworkType()` | `READ_PHONE_STATE` | 24 | default | safe | LTE/NR/HSPA/etc — translate constant to string |
| Voice network type | `TelephonyManager.getVoiceNetworkType()` | `READ_PHONE_STATE` | 24 | verbose | safe | |
| Data state | `TelephonyManager.getDataState()` | none | 1 | default | safe | DISCONNECTED/CONNECTING/CONNECTED/SUSPENDED |
| Data activity | `TelephonyManager.getDataActivity()` | none | 1 | verbose | safe | NONE/IN/OUT/INOUT/DORMANT |
| Roaming | `TelephonyManager.isNetworkRoaming()` | none | 1 | default | safe | |
| Signal strength dBm | `TelephonyManager.getSignalStrength().getCellSignalStrengths()[0].dbm` | `READ_PHONE_STATE` | 29 | default | safe | API 28-: register `PhoneStateListener`; on 29+ direct getter |
| Signal level (0-4) | `SignalStrength.level` | `READ_PHONE_STATE` | 29 | default | safe | bars |
| Cell tower info | `TelephonyManager.getAllCellInfo()` | `ACCESS_FINE_LOCATION` (+ `READ_PHONE_STATE`) | 17 | verbose | location-leak | CID/LAC/PCI/TAC; ties to physical tower |
| IMEI / MEID | `TelephonyManager.getImei()` | `READ_PRIVILEGED_PHONE_STATE` | 26 (priv 29) | **skip** | identifier | privileged-only on API 29+ |
| Subscriber ID (IMSI) | `TelephonyManager.getSubscriberId()` | `READ_PRIVILEGED_PHONE_STATE` | 1 (priv 29) | **skip** | identifier | |
| Phone number | `TelephonyManager.getLine1Number()` / `SubscriptionManager.getPhoneNumber()` (33+) | `READ_PHONE_NUMBERS` | 1 / 33 | **skip** | identifier | we don't hold the perm; many carriers return empty anyway |
| Manufacturer code | `TelephonyManager.getManufacturerCode()` | `READ_PHONE_STATE` | 29 | verbose | safe | 8-char TAC |
| VoLTE available | `TelephonyManager.isVolteAvailable()` | `READ_PHONE_STATE` | 26 | verbose | safe | hidden API on some, prefer `isImsRegistered` if needed |
| Wi-Fi calling available | `TelephonyManager.isWifiCallingAvailable()` | `READ_PHONE_STATE` | 26 | verbose | safe | |
| Data enabled | `TelephonyManager.isDataEnabled()` | `READ_PHONE_STATE` | 26 | default | safe | |
| Data roaming enabled | `TelephonyManager.isDataRoamingEnabled()` | `READ_PHONE_STATE` | 29 | verbose | safe | |
| Call state | `TelephonyManager.getCallState()` | none | 1 | default | safe | IDLE/RINGING/OFFHOOK |

## 4. Wi-Fi

We hold `ACCESS_WIFI_STATE` and `ACCESS_FINE_LOCATION`. SSID/BSSID return real
values; MAC is randomized.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Wi-Fi enabled | `WifiManager.isWifiEnabled()` | `ACCESS_WIFI_STATE` | 1 | default | safe | |
| 5 GHz supported | `WifiManager.is5GHzBandSupported()` | `ACCESS_WIFI_STATE` | 21 | verbose | safe | |
| 6 GHz supported | `WifiManager.is6GHzBandSupported()` | `ACCESS_WIFI_STATE` | 30 | verbose | safe | |
| Wi-Fi 6 (HE) supported | `WifiManager.isWifiStandardSupported(WIFI_STANDARD_11AX)` | `ACCESS_WIFI_STATE` | 30 | verbose | safe | |
| SSID | `WifiManager.connectionInfo.ssid` | `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE` | 1 | verbose | identifier | requires location perm since API 27/29; we have it. Strip surrounding quotes. |
| BSSID | `WifiInfo.getBSSID()` | `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE` | 1 | verbose | location-leak | AP MAC — can be looked up to physical address via wigle.net etc. |
| Link speed Mbps | `WifiInfo.getLinkSpeed()` | `ACCESS_WIFI_STATE` | 1 | default | safe | |
| TX link speed | `WifiInfo.getTxLinkSpeedMbps()` | `ACCESS_WIFI_STATE` | 29 | default | safe | |
| RX link speed | `WifiInfo.getRxLinkSpeedMbps()` | `ACCESS_WIFI_STATE` | 29 | default | safe | |
| Frequency MHz | `WifiInfo.getFrequency()` | `ACCESS_WIFI_STATE` | 21 | default | safe | derive band (2.4/5/6) |
| RSSI dBm | `WifiInfo.getRssi()` | `ACCESS_WIFI_STATE` | 1 | default | safe | |
| RSSI bars | `WifiManager.calculateSignalLevel(rssi, 5)` | none | 1/30 | default | safe | |
| Wi-Fi standard | `WifiInfo.getWifiStandard()` | `ACCESS_WIFI_STATE` | 30 | verbose | safe | LEGACY/N/AC/AX/BE |
| IP (Wi-Fi) | `WifiInfo.getIpAddress()` (deprecated; use `LinkProperties`) | `ACCESS_WIFI_STATE` | 1 | verbose | location-leak | private IP — leaks LAN structure |
| MAC | `WifiInfo.getMacAddress()` | n/a | 1 | **skip** | identifier | returns `02:00:00:00:00:00` since API 23 for non-system apps |
| Configured networks count | `WifiManager.getConfiguredNetworks().size` | `ACCESS_FINE_LOCATION` | 1 | verbose | safe | API 29+ only returns networks the caller created — usually empty |
| Hotspot active | `WifiManager.isWifiApEnabled()` (reflection / hidden) | n/a | hidden | **skip** | safe | not stable; use `getTetheredIfaces` via reflection — fragile, drop |

## 5. Network (system-wide)

`ACCESS_NETWORK_STATE` is a normal perm we hold.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Active transport | `ConnectivityManager.getNetworkCapabilities(activeNetwork)` → `hasTransport(...)` | `ACCESS_NETWORK_STATE` | 23 | default | safe | WIFI/CELLULAR/ETHERNET/VPN/BLUETOOTH; supersedes shown `networkType` |
| Has internet capability | `NetworkCapabilities.hasCapability(NET_CAPABILITY_INTERNET)` | `ACCESS_NETWORK_STATE` | 23 | default | safe | |
| Validated (captive portal pass) | `NetworkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)` | `ACCESS_NETWORK_STATE` | 23 | default | safe | distinguishes "connected" from "connected+routable" |
| Captive portal | `NetworkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)` | `ACCESS_NETWORK_STATE` | 23 | default | safe | |
| Metered | `ConnectivityManager.isActiveNetworkMetered()` | `ACCESS_NETWORK_STATE` | 16 | default | safe | |
| Restricted background | `ConnectivityManager.getRestrictBackgroundStatus()` | `ACCESS_NETWORK_STATE` | 24 | verbose | safe | DataSaver state |
| Downstream Kbps | `NetworkCapabilities.getLinkDownstreamBandwidthKbps()` | `ACCESS_NETWORK_STATE` | 21 | verbose | safe | OS estimate, not measured |
| Upstream Kbps | `NetworkCapabilities.getLinkUpstreamBandwidthKbps()` | `ACCESS_NETWORK_STATE` | 21 | verbose | safe | |
| IPv4 addresses (per iface) | `NetworkInterface.getNetworkInterfaces()` → enumerate | none | 1 | verbose | location-leak | private LAN IPs |
| IPv6 addresses | same | none | 1 | verbose | location-leak | global v6 prefix can geo-locate |
| Gateway | `LinkProperties.getRoutes()` first default route | `ACCESS_NETWORK_STATE` | 21 | verbose | location-leak | |
| DNS servers | `LinkProperties.getDnsServers()` | `ACCESS_NETWORK_STATE` | 21 | verbose | safe-ish | 1.1.1.1 vs 8.8.8.8 vs ISP-provided |
| Private DNS mode | `LinkProperties.getPrivateDnsServerName()` / `isPrivateDnsActive()` | `ACCESS_NETWORK_STATE` | 28 | verbose | safe | |
| MTU | `LinkProperties.getMtu()` | `ACCESS_NETWORK_STATE` | 21 | verbose | safe | |
| Interface name | `LinkProperties.getInterfaceName()` | `ACCESS_NETWORK_STATE` | 21 | verbose | safe | wlan0 / rmnet_data0 |
| HTTP proxy | `LinkProperties.getHttpProxy()` | `ACCESS_NETWORK_STATE` | 21 | verbose | safe | |
| VPN active | `NetworkCapabilities.hasTransport(TRANSPORT_VPN)` | `ACCESS_NETWORK_STATE` | 21 | default | safe | |
| Total RX bytes | `TrafficStats.getTotalRxBytes()` | none | 8 | verbose | safe | since boot |
| Total TX bytes | `TrafficStats.getTotalTxBytes()` | none | 8 | verbose | safe | |
| Mobile RX bytes | `TrafficStats.getMobileRxBytes()` | none | 8 | verbose | safe | |
| Mobile TX bytes | `TrafficStats.getMobileTxBytes()` | none | 8 | verbose | safe | |
| Our app RX | `TrafficStats.getUidRxBytes(android.os.Process.myUid())` | none | 8 | default | safe | useful — quantifies our own MQTT traffic |
| Our app TX | `TrafficStats.getUidTxBytes(android.os.Process.myUid())` | none | 8 | default | safe | |

## 6. Battery

Sticky `Intent.ACTION_BATTERY_CHANGED` for static metadata; `BatteryManager`
properties for live numbers.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Percent | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` or sticky `level/scale` | none | 21 | default | safe | shown |
| Charging | sticky `EXTRA_PLUGGED` != 0 OR `BatteryManager.isCharging()` | none | 23 | default | safe | shown |
| Charge plug | sticky `EXTRA_PLUGGED` (AC/USB/WIRELESS/DOCK) | none | 5 | default | safe | translate constant |
| Status | sticky `EXTRA_STATUS` (CHARGING/DISCHARGING/FULL/NOT_CHARGING) | none | 5 | default | safe | |
| Health | sticky `EXTRA_HEALTH` (GOOD/OVERHEAT/DEAD/COLD/...) | none | 5 | default | safe | |
| Technology | sticky `EXTRA_TECHNOLOGY` | none | 5 | verbose | safe | "Li-ion" |
| Temperature C | sticky `EXTRA_TEMPERATURE` / 10.0 | none | 5 | default | safe | tenths of °C |
| Voltage mV | sticky `EXTRA_VOLTAGE` | none | 5 | default | safe | |
| Current now (µA) | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` | none | 21 | default | safe | sign convention varies (positive=charging on most OEMs, negative on others) — note in caveat |
| Current avg (µA) | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_AVERAGE)` | none | 21 | default | safe | |
| Charge counter (µAh) | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CHARGE_COUNTER)` | none | 21 | verbose | safe | accumulated since reset |
| Energy counter (nWh) | `BatteryManager.getLongProperty(BATTERY_PROPERTY_ENERGY_COUNTER)` | none | 21 | verbose | safe | not implemented on every device |
| Capacity remaining mAh | `currentNow * (capacity_pct/100)` — derive from spec | none | n/a | skip | safe | no clean Android API for design capacity |
| Battery saver | `PowerManager.isPowerSaveMode()` | none | 21 | default | safe | |
| Charging time remaining | `BatteryManager.computeChargeTimeRemaining()` | none | 28 | verbose | safe | -1 if device can't predict |
| Charge state (BMS) | `EXTRA_BATTERY_LOW` (sticky) | none | 5 | verbose | safe | low-power notification fired |
| Charging cycles | (no public API) | n/a | n/a | skip | safe | available on some OEM-extended SystemAPIs only |

## 7. Storage

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Internal data total | `StorageManager.getAllocatableBytes(...)` or `File("/data").totalSpace` | none | 26 | default | safe | use `StorageStatsManager` for accurate per-volume |
| Internal data free | `StatFs(filesDir.path).availableBytes` | none | 18 | default | safe | |
| Internal data used | derive (total - free) | — | — | default | safe | |
| Total volume | `StorageStatsManager.getTotalBytes(uuid)` | none | 26 | default | safe | per-volume |
| Free volume | `StorageStatsManager.getFreeBytes(uuid)` | none | 26 | default | safe | |
| Cache size (own app) | `StorageStatsManager.queryStatsForUid(uuid, myUid).cacheBytes` | none | 26 | verbose | safe | other-app stats need `PACKAGE_USAGE_STATS` |
| Data size (own app) | `...dataBytes` | none | 26 | verbose | safe | |
| App APK size | `...appBytes` | none | 26 | verbose | safe | |
| External (SD) volumes | `StorageManager.getStorageVolumes()` enumerate; `isRemovable`, `state` | none | 24 | default | safe | flag SD presence + free bytes |
| Adoptable storage | `StorageVolume.isPrimary()` / `isEmulated()` | none | 24 | verbose | safe | |
| Cache quota | `StorageManager.getCacheQuotaBytes()` | none | 26 | verbose | safe | |
| Low storage | sticky `Intent.ACTION_DEVICE_STORAGE_LOW` | none | 1 | verbose | safe | also `StorageManager.isAllocationSupported(...)` |

## 8. Memory

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| RAM total | `ActivityManager.getMemoryInfo(mi)` then `mi.totalMem` | none | 16 | default | safe | |
| RAM available | `mi.availMem` | none | 1 | default | safe | |
| Low memory | `mi.lowMemory` | none | 1 | default | safe | |
| Low memory threshold | `mi.threshold` | none | 1 | verbose | safe | |
| Our process RSS | parse `/proc/self/status` `VmRSS` | none | — | default | safe | fully readable |
| Our process VSS | parse `/proc/self/status` `VmSize` | none | — | verbose | safe | |
| Our native heap allocated | `Debug.getNativeHeapAllocatedSize()` | none | 1 | verbose | safe | |
| Our native heap size | `Debug.getNativeHeapSize()` | none | 1 | verbose | safe | |
| Our native heap free | `Debug.getNativeHeapFreeSize()` | none | 1 | verbose | safe | |
| Java heap used | `Runtime.getRuntime().totalMemory() - freeMemory()` | none | 1 | default | safe | |
| Java heap max | `Runtime.getRuntime().maxMemory()` | none | 1 | verbose | safe | dalvik.vm.heapsize |
| PSS (own app) | `ActivityManager.getProcessMemoryInfo(int[]{myPid})[0].totalPss` | none | 23 | verbose | safe | rate-limited on 29+, our own pid is exempt |
| Thread count | parse `/proc/self/status` `Threads` | none | — | verbose | safe | |
| Open FD count | `File("/proc/self/fd").list().size` | none | — | verbose | safe | useful for FD-leak diagnosis |
| GC count | `Debug.getRuntimeStat("art.gc.gc-count")` | none | 23 | verbose | safe | |
| Last GC blocking time | `Debug.getRuntimeStat("art.gc.blocking-gc-time")` | none | 23 | verbose | safe | |
| GC bytes allocated | `Debug.getRuntimeStat("art.gc.bytes-allocated")` | none | 23 | verbose | safe | |
| Swap total | parse `/proc/meminfo` `SwapTotal` | none | — | verbose | safe | usually 0 / zRAM only |
| Swap free | `/proc/meminfo` `SwapFree` | none | — | verbose | safe | |

## 9. Display

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Resolution px | `WindowManager.maximumWindowMetrics.bounds` (30+) or `Display.getRealMetrics()` | none | 17 / 30 | default | safe | |
| Density DPI | `Resources.getSystem().displayMetrics.densityDpi` | none | 1 | default | safe | |
| Density scale | `displayMetrics.density` | none | 1 | verbose | safe | |
| Refresh rate | `Display.getRefreshRate()` | none | 3 | default | safe | float, e.g. 120.0 |
| Display modes | `Display.getSupportedModes()` | none | 23 | verbose | safe | active mode + alternates |
| HDR capabilities | `Display.getHdrCapabilities().supportedHdrTypes` | none | 24 | verbose | safe | DOLBY_VISION/HDR10/HLG/HDR10+ |
| Wide color gamut | `Display.isWideColorGamut()` | none | 26 | verbose | safe | |
| Rotation | `Display.getRotation()` | none | 8 | default | safe | ROTATION_0/90/180/270 |
| State | `Display.getState()` | none | 20 | default | safe | ON/OFF/DOZE/DOZE_SUSPEND — implies screen on |
| Brightness mode | `Settings.System.getInt(cr, SCREEN_BRIGHTNESS_MODE)` | none | 8 | default | safe | 0=manual, 1=auto |
| Brightness level | `Settings.System.getInt(cr, SCREEN_BRIGHTNESS)` | none | 1 | default | safe | 0-255 |
| Adaptive brightness on | derive from brightness mode | none | — | default | safe | |
| Screen timeout ms | `Settings.System.getInt(cr, SCREEN_OFF_TIMEOUT)` | none | 1 | verbose | safe | |
| Screen on/off (interactive) | `PowerManager.isInteractive()` | none | 20 | default | safe | replaces deprecated `isScreenOn` |
| Keyguard locked | `KeyguardManager.isKeyguardLocked()` | none | 16 | default | safe | |
| Keyguard secure | `KeyguardManager.isKeyguardSecure()` | none | 16 | verbose | safe | |
| Device locked | `KeyguardManager.isDeviceLocked()` | none | 22 | default | safe | distinct from keyguard-locked when biometric is auth'd |
| Font scale | `Resources.getSystem().configuration.fontScale` | none | 1 | verbose | safe | accessibility hint |
| Dark mode active | `Configuration.uiMode & UI_MODE_NIGHT_YES` | none | 8 | default | safe | |

## 10. Audio

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Ringer mode | `AudioManager.getRingerMode()` | none | 1 | default | safe | NORMAL/SILENT/VIBRATE |
| Music stream volume | `AudioManager.getStreamVolume(STREAM_MUSIC)` / `getStreamMaxVolume` | none | 1 | default | safe | report as % |
| Ring stream volume | same with `STREAM_RING` | none | 1 | verbose | safe | |
| Notification volume | `STREAM_NOTIFICATION` | none | 1 | verbose | safe | |
| Voice call volume | `STREAM_VOICE_CALL` | none | 1 | verbose | safe | |
| Alarm volume | `STREAM_ALARM` | none | 1 | verbose | safe | |
| Music active | `AudioManager.isMusicActive()` | none | 1 | default | safe | something playing |
| Mode (NORMAL/IN_CALL/RINGTONE) | `AudioManager.getMode()` | none | 1 | default | safe | |
| Speaker on | `AudioManager.isSpeakerphoneOn()` | none | 1 | verbose | safe | |
| Microphone mute | `AudioManager.isMicrophoneMute()` | none | 1 | verbose | safe | |
| Wired headset | iterate `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` for `TYPE_WIRED_HEADSET`/`TYPE_WIRED_HEADPHONES` | none | 23 | default | safe | |
| USB audio | same, `TYPE_USB_*` | none | 23 | verbose | safe | |
| BT audio device | same, `TYPE_BLUETOOTH_A2DP` / `TYPE_BLUETOOTH_SCO` | none | 23 | default | safe | type only, no name → no `BLUETOOTH_CONNECT` needed |
| HDMI out | same, `TYPE_HDMI` | none | 23 | verbose | safe | |
| Active output device | `AudioManager.getCommunicationDevice()` (31+) or routing query | none | 31 | verbose | safe | |
| Do Not Disturb | `NotificationManager.getCurrentInterruptionFilter()` | none | 23 | default | safe | NONE/PRIORITY/ALARMS/ALL |
| Notifications enabled | `NotificationManager.areNotificationsEnabled()` | none | 19/24 | default | safe | for our own app |

## 11. Sensors

`SensorManager.getDefaultSensor(type)` returns null if absent — enumerate
once and report a presence map. Reading values takes a callback and is
async; for `/info` just enumerate **what's present** rather than current
readings. If we want a snapshot per type, register a one-shot listener with
`SENSOR_DELAY_FASTEST` and unregister after first event (cap at ~200ms). All
no-permission unless noted.

| Sensor | TYPE | API | Reading | Verdict | Safety |
|---|---|---|---|---|---|
| Accelerometer | `TYPE_ACCELEROMETER` | 3 | xyz m/s² | verbose | safe |
| Gyroscope | `TYPE_GYROSCOPE` | 9 | xyz rad/s | verbose | safe |
| Magnetometer | `TYPE_MAGNETIC_FIELD` | 3 | xyz µT | verbose | safe |
| Pressure (barometer) | `TYPE_PRESSURE` | 3 | hPa | default | safe — useful for altitude/weather context |
| Ambient light | `TYPE_LIGHT` | 3 | lux | default | safe |
| Proximity | `TYPE_PROXIMITY` | 3 | cm or binary | default | safe |
| Ambient temperature | `TYPE_AMBIENT_TEMPERATURE` | 14 | °C | verbose | safe — rare on phones |
| Humidity | `TYPE_RELATIVE_HUMIDITY` | 14 | % | verbose | safe — rare |
| Step counter | `TYPE_STEP_COUNTER` | 19 | steps since boot | verbose | safe |
| Heart rate | `TYPE_HEART_RATE` | 20 | BPM | n/a | requires `BODY_SENSORS` — we don't hold; presence-only |
| Significant motion | `TYPE_SIGNIFICANT_MOTION` | 18 | binary | verbose | safe |
| Rotation vector | `TYPE_ROTATION_VECTOR` | 9 | quat | verbose | safe |
| Game rotation vector | `TYPE_GAME_ROTATION_VECTOR` | 18 | quat | verbose | safe |

For `/info`, recommend: a single line listing **present** sensor types
(without readings), plus barometer pressure + light lux + proximity since
those are the cheap useful ones for environmental triage.

## 12. Location / position

We hold `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`. Don't request a
fresh fix from `/info` — it's slow and battery-costly. Read the most recent
known fix instead.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Last known fix (GPS) | `LocationManager.getLastKnownLocation(GPS_PROVIDER)` | `ACCESS_FINE_LOCATION` | 1 | verbose | location-leak | obvious leak risk |
| Last known fix (network) | same with `NETWORK_PROVIDER` | `ACCESS_COARSE_LOCATION` | 1 | verbose | location-leak | |
| Fused fix | `FusedLocationProviderClient.getLastLocation()` (Play Services) | location perm | n/a | verbose | location-leak | requires GMS dependency; skip if not added |
| Time since fix | `now - location.time` | — | — | default | safe | "fix is 4m old" doesn't leak position; useful staleness indicator |
| Location enabled | `LocationManager.isLocationEnabled()` | none | 28 | default | safe | system master toggle |
| GPS provider enabled | `LocationManager.isProviderEnabled(GPS_PROVIDER)` | none | 1 | default | safe | |
| Network provider enabled | same | none | 1 | default | safe | |
| Mock location set | `Location.isFromMockProvider()` (last fix only) | location perm | 18 | verbose | safe | |
| Cell-tower-derived approx | (covered under Cellular: `getAllCellInfo`) | `FINE_LOCATION` | 17 | verbose | location-leak | |

## 13. Camera

`CameraManager.getCameraIdList()` enumerates without opening anything; per-id
characteristics are also read-only. We hold `CAMERA` so live preview and
recording work, but `/info` shouldn't open the camera. Static info only.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Camera count | `cameraManager.cameraIdList.size` | none | 21 | default | safe | |
| Per-camera facing | `getCameraCharacteristics(id).get(LENS_FACING)` | none | 21 | default | safe | FRONT/BACK/EXTERNAL |
| Sensor pixel size | `SENSOR_INFO_PIXEL_ARRAY_SIZE` | none | 21 | default | safe | "12.0 MP" |
| Max still resolution | iterate `SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(JPEG)` → max | none | 21 | default | safe | |
| Max video resolution | `getOutputSizes(MediaRecorder.class)` → max | none | 21 | default | safe | also `CamcorderProfile.hasProfile(QUALITY_2160P/...)` |
| Supported features | `REQUEST_AVAILABLE_CAPABILITIES` | none | 21 | verbose | safe | MANUAL_SENSOR/RAW/etc |
| Flash available | `FLASH_INFO_AVAILABLE` | none | 21 | default | safe | |
| OIS supported | `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` non-empty | none | 21 | verbose | safe | |
| Hardware level | `INFO_SUPPORTED_HARDWARE_LEVEL` | none | 21 | verbose | safe | LEGACY/LIMITED/FULL/3 |
| Torch on | `CameraManager.TorchCallback` (need to register and remember last state) | none | 23 | verbose | safe | |
| Available focal lengths | `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` | none | 21 | verbose | safe | distinguish ultrawide/main/tele |

## 14. Connectivity hardware (other)

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Bluetooth supported | `packageManager.hasSystemFeature(FEATURE_BLUETOOTH)` | none | 8 | default | safe | |
| Bluetooth LE supported | `FEATURE_BLUETOOTH_LE` | none | 18 | verbose | safe | |
| Bluetooth on | `BluetoothAdapter.getDefaultAdapter().isEnabled` | none | 18 | default | safe | reading enabled state is permission-free; only `getName()`/`getAddress()`/scan/connect need `BLUETOOTH_CONNECT`/`SCAN` (31+) |
| Bluetooth state | `BluetoothAdapter.getState()` | none | 18 | verbose | safe | OFF/TURNING_ON/ON/TURNING_OFF |
| BT bonded device count | `BluetoothAdapter.getBondedDevices()` | `BLUETOOTH_CONNECT` (31+) | 18 | **skip** | identifier | we don't hold the perm |
| NFC supported | `FEATURE_NFC` | none | 9 | default | safe | |
| NFC enabled | `NfcAdapter.getDefaultAdapter().isEnabled` | none | 10 | default | safe | |
| HCE supported | `FEATURE_NFC_HOST_CARD_EMULATION` | none | 19 | verbose | safe | |
| Telephony supported | `FEATURE_TELEPHONY` | none | 7 | default | safe | covered in Cellular |
| USB host supported | `FEATURE_USB_HOST` | none | 12 | verbose | safe | |
| USB connected | sticky `Intent.ACTION_BATTERY_CHANGED` `EXTRA_PLUGGED == BATTERY_PLUGGED_USB` | none | 5 | default | safe | |
| Hotspot active | `WifiManager.isWifiApEnabled()` (hidden API; reflection) | n/a | hidden | **skip** | safe | unstable since 26 |
| VPN active | `NetworkCapabilities.hasTransport(TRANSPORT_VPN)` | `ACCESS_NETWORK_STATE` | 21 | default | safe | duplicates Network section; mention once |
| Tethering active | `ConnectivityManager.getTetheredIfaces` (hidden) | n/a | hidden | **skip** | safe | non-public |
| Airplane mode | `Settings.Global.getInt(cr, AIRPLANE_MODE_ON)` | none | 17 | default | safe | |

## 15. Power state

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Battery saver | `PowerManager.isPowerSaveMode()` | none | 21 | default | safe | |
| Doze (idle) | `PowerManager.isDeviceIdleMode()` | none | 23 | default | safe | |
| App standby bucket | `UsageStatsManager.getAppStandbyBucket()` | own app only — none | 28 | default | safe | ACTIVE/WORKING/FREQUENT/RARE/RESTRICTED |
| Battery optimization ignored (us) | `PowerManager.isIgnoringBatteryOptimizations(packageName)` | none | 23 | default | safe | matters for our background MQTT |
| Interactive (screen on) | `PowerManager.isInteractive()` | none | 20 | default | safe | |
| Power-save state | `PowerManager.getCurrentThermalStatus()` | none | 29 | default | safe | NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN |
| Sustained perf supported | `PowerManager.isSustainedPerformanceModeSupported()` | none | 24 | verbose | safe | |
| Wake lock count (own) | manual counter we maintain | n/a | — | verbose | safe | not exposed by API |
| Adaptive battery enabled | `Settings.Global.getInt(cr, "adaptive_battery_management_enabled")` | none | 28 | verbose | safe | hidden setting key, may be empty on some OEMs |
| Battery saver schedule | `PowerManager.getPowerSaveModeTrigger()` (33+) | none | 33 | verbose | safe | |
| Background data restricted | `ConnectivityManager.getRestrictBackgroundStatus()` | `ACCESS_NETWORK_STATE` | 24 | default | safe | DataSaver — already in Network |

## 16. Process (own app)

Useful battery-drain & leak diagnostics. All `/proc/self/*` reads are
permitted for our own pid.

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Pid | `Process.myPid()` | none | 1 | default | safe | |
| Uid | `Process.myUid()` | none | 1 | default | safe | |
| Process name | `Application.getProcessName()` (28+) or read `/proc/self/cmdline` | none | 28 | verbose | safe | |
| Threads | `/proc/self/status` `Threads` | none | — | default | safe | thread count |
| Active threads (JVM) | `Thread.activeCount()` (current group) | none | 1 | verbose | safe | underestimates |
| Open FDs | count `/proc/self/fd` | none | — | verbose | safe | |
| User CPU jiffies | `/proc/self/stat` field 14 | none | — | verbose | safe | |
| Sys CPU jiffies | field 15 | none | — | verbose | safe | |
| Voluntary ctx switches | `/proc/self/status` `voluntary_ctxt_switches` | none | — | verbose | safe | |
| Involuntary ctx switches | same `nonvoluntary_ctxt_switches` | none | — | verbose | safe | |
| Process start time | `/proc/self/stat` field 22 / boot time | none | — | default | safe | distinct from service-start (matters across `/update`) |
| Service uptime | already shown | none | — | default | safe | |
| Last GC blocking time | `Debug.getRuntimeStat("art.gc.blocking-gc-time")` | none | 23 | verbose | safe | |
| Last GC count | `art.gc.gc-count` | none | 23 | verbose | safe | |
| ART JIT total time | `art.jit.compile-time` (might be unsupported) | none | 23 | verbose | safe | |
| Class init count | `Debug.getRuntimeStat("art.class.init.count")` (where avail) | none | 23 | verbose | safe | |
| Boot dex2oat status | `getApplicationInfo().sourceDir` size delta — no public stat | n/a | — | skip | safe | |

## 17. Security

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Encrypted | `StorageManager.isEncrypted()` (28+) or `EnvironmentCompat.MEDIA_UNKNOWN` heuristic | none | 24/28 | default | safe | FBE/FDE on by default since 7 |
| Direct boot aware | `UserManager.isUserUnlocked()` | none | 24 | verbose | safe | post-boot pre-unlock state |
| Lockscreen secured | `KeyguardManager.isDeviceSecure()` | none | 23 | default | safe | true if any of PIN/pattern/password/biometric enrolled |
| Lockscreen type | (no public API) — derive from `isDeviceSecure` + biometric availability | n/a | — | verbose | safe | best we can say is "secured + biometric enrolled" |
| Biometric availability | `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` | `USE_BIOMETRIC` (normal) | 29 | default | safe | SUCCESS / NO_HARDWARE / HW_UNAVAILABLE / NONE_ENROLLED |
| Biometric enrolled (any) | derive from above | none | 29 | default | safe | |
| Device admin active | `DevicePolicyManager.getActiveAdmins()` non-empty | none | 8 | verbose | safe | enumerates other admins on device, leaks MDM presence — verbose |
| Profile owner | `DevicePolicyManager.isProfileOwnerApp()` | none | 21 | verbose | safe | for our own app — usually false |
| Device owner | `DevicePolicyManager.isDeviceOwnerApp()` | none | 18 | verbose | safe | |
| Work profile present | `UserManager.getUserProfiles()` size > 1 | none | 17 | verbose | safe | |
| ADB enabled | `Settings.Global.getInt(cr, ADB_ENABLED)` | none | 17 | default | safe | useful — flags developer-mode device |
| Developer options enabled | `Settings.Global.getInt(cr, DEVELOPMENT_SETTINGS_ENABLED)` | none | 17 | default | safe | |
| Allow non-Play installs | `packageManager.canRequestPackageInstalls()` | `REQUEST_INSTALL_PACKAGES` (held) | 26 | default | safe | matters for `/update` flow |
| SafetyNet / Play Integrity | (Google API; out of scope) | n/a | n/a | skip | secret-adjacent | not stdlib |
| SELinux mode | `/proc/self/attr/current` or `getenforce` (no public API) | none | — | verbose | safe | almost always "enforcing" — low signal |
| Verified boot state | `Build.VERSION.SECURITY_PATCH` is best public hint | none | — | skip | safe | full state is privileged |

## 18. Apps

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Installed app count | `packageManager.getInstalledPackages(0).size` | (Q+) `<queries>` or `QUERY_ALL_PACKAGES` | 1 / 30 | verbose | identifier | API 30+ requires manifest `<queries>` filter; without it the count is restricted to packages we've declared visibility for. Likely returns small numbers — note caveat. |
| Default browser | `packageManager.resolveActivity(http-intent, MATCH_DEFAULT_ONLY)` | needs `<queries>` for browser | 1 | verbose | safe | useful — Chrome/Brave/etc |
| Default dialer | `TelecomManager.getDefaultDialerPackage()` | none | 23 | verbose | safe | |
| Default SMS | `Telephony.Sms.getDefaultSmsPackage(ctx)` | none | 19 | default | safe | matters for our SMS path |
| Default home (launcher) | `packageManager.resolveActivity(home-intent, ...)` | none | 1 | verbose | safe | |
| Default assistant | `Settings.Secure.getString(cr, "assistant")` | none | — | verbose | safe | hidden key; OEM-variable |
| Our own granted perms | iterate `packageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions` and `checkSelfPermission` per item | none | 1 | default | safe | self-report grant matrix — very useful for triage |
| Our own version code | already shown | — | 28 | default | safe | |
| Our own package install source | `packageManager.getInstallSourceInfo(packageName).installingPackageName` | none | 30 | verbose | safe | "com.android.vending" / "adb" / our updater |
| Accessibility services enabled | `Settings.Secure.getString(cr, ENABLED_ACCESSIBILITY_SERVICES)` | none | 14 | default | safe | confirm our a11y service is in the list |
| Accessibility master switch | `Settings.Secure.getInt(cr, ACCESSIBILITY_ENABLED)` | none | 4 | default | safe | |
| Notification listeners enabled | `NotificationManagerCompat.getEnabledListenerPackages(ctx)` | none | 19 | default | safe | confirm our listener is bound |
| Bound apps that can install | `packageManager.canRequestPackageInstalls()` | none | 26 | default | safe | |

## 19. Time / clock

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Wall-clock | `System.currentTimeMillis()` | none | 1 | default | safe | covered above |
| Boot time | derive | none | — | default | safe | covered above |
| Auto-time enabled | covered above | none | 17 | verbose | safe | |
| Last NTP sync time | (no public API) | n/a | — | skip | safe | only `auto_time` boolean is exposed |
| 24-hour format | `DateFormat.is24HourFormat(ctx)` | none | 3 | verbose | safe | |
| Screen-on time (battery stats) | `BatteryStatsManager.getCellularBatteryStats()` etc. (system app only) | privileged | 30 | skip | safe | |
| CPU on (own app) | `Process.getElapsedCpuTime()` (own pid) | none | 1 | verbose | safe | |
| Service start time | already shown | — | — | default | safe | |
| Service uptime | already shown | — | — | default | safe | |
| Process start time | `/proc/self/stat` field 22 | none | — | verbose | safe | |
| Last reboot reason | `PowerManager.getLastShutdownReason()` (privileged) | priv | — | skip | safe | not public |

## 20. Notifications / accessibility (cross-cutting useful state)

| Field | API call | Perm | API | Verdict | Safety | Notes |
|---|---|---|---|---|---|---|
| Our notification channel state | `NotificationManager.getNotificationChannel(id).importance` | none | 26 | default | safe | flags user muting our foreground svc |
| Notif post permission | `checkSelfPermission(POST_NOTIFICATIONS)` (held) | — | 33 | default | safe | |
| Accessibility service running (us) | manual flag we set in `A11yService.onServiceConnected` | none | — | default | safe | our own state |
| MediaProjection consent held | `A8sService.hasProjectionConsent()` | n/a | — | default | safe | already partly known internally |
| `tell`/`replyToOwner` last success | manual instrumentation | n/a | — | default | safe | useful — "last successful publish 4s ago" |
| MQTT subscriber connected | `mqttClients[name]?.isConnected` | n/a | — | default | safe | already shown per-remote |
| MQTT inbox queue depth | (we don't queue — broker does QoS 1) | — | — | skip | safe | |

---

## Suggested layout for default `/info`

Order from most-to-least likely to be the answer the operator needs:

1. App version + build (line 1) — already there.
2. Device identity: `manufacturer model · Android <release> (API <int>) · patch <YYYY-MM-DD>` — adds patch level + a `(soc)` suffix on 31+.
3. Network: transport · validated · metered · roaming flag · signal dBm · network type.
4. Remotes: count + per-remote ✓/✗.
5. Storage services configured.
6. Battery: `<pct>% <state> · <temp>°C · <currentNow>mA` (sign-aware label).
7. Memory: `RAM <avail>/<total> · RSS <our> · Java heap <used>/<max>`.
8. Storage: `data <free>/<total> · cache <our>`.
9. Display: `<W>×<H>@<refresh>Hz · brightness <auto|manual> <0-255> · screen <on|off|locked|unlocked>`.
10. Power: `interactive=<bool> · doze=<bool> · battery-saver=<bool> · standby=<bucket> · thermal=<status> · ignoring-batt-opt=<bool>`.
11. Permissions self-report: one line per *expected* runtime perm with ✓/✗.
12. Critical services: `a11y=<bool> · notif-listener=<bool> · projection=<bool>`.
13. Uptime block: `service <fmt> · process <fmt> · boot <fmt>`.
14. Config: phonebook size, owner set, forward set — already there.

## Suggested additional fields under `/info verbose`

- All of: SSID + BSSID + Wi-Fi link-speed/freq, IP addresses, gateway, DNS,
  cell tower CID/TAC, carrier MCC/MNC, last-known-location coords + age,
  Build fingerprint, Android ID, kernel version full string, sensor
  presence map + light/pressure/proximity readings, camera per-id
  resolutions, traffic counters (TX/RX), GC stats, FD count, thread count,
  installed-app count + default browser/dialer/SMS/home, encryption state,
  developer/ADB flags.
- Anything tagged **identifier** or **location-leak** in the tables above.

## Things to consciously skip

- **IMEI/IMSI/serial/phone number** — privileged or we don't hold the perm.
- **MAC address** — sentinel `02:00:00:00:00:00` since API 23.
- **Hotspot active / tethering** — only via reflection on hidden API; brittle.
- **SafetyNet / Play Integrity** — not stdlib.
- **Other-app install metadata beyond count** — needs `<queries>` declarations
  or `QUERY_ALL_PACKAGES`; the latter is policy-restricted on Play.
- **Body sensors / heart rate readings** — needs `BODY_SENSORS`.
- **BT device names / paired list** — needs `BLUETOOTH_CONNECT`.
- **Calendar/Account info** — needs `READ_CALENDAR` / `GET_ACCOUNTS`.
- **SELinux mode / verified-boot** — privileged or non-public.
- **Reboot reason / last-shutdown** — privileged.

## Rough field count

- **default**: ~55 fields actually surfaced (some are aggregates).
- **verbose**: ~95 additional fields.
- **skip**: ~20 noted above.

Together this exceeds the 60-80 target while staying within the held grant
set. Implementation note: build `InfoSnapshot` as a flat data class with
nullable fields for everything that can fail (sensor missing, no telephony,
provider returns null), and let `renderInfo` decide what to print based on a
`verbose: Boolean` flag derived from `cmd.args.contains("verbose")`.

## Source-citation pointers

- `Build.*` — `android.os.Build` SDK reference.
- `BatteryManager.BATTERY_PROPERTY_*` — `android.os.BatteryManager` SDK ref.
- Sensor type API levels — `android.hardware.Sensor` constants page.
- TelephonyManager perm matrix — `developer.android.com/reference/android/telephony/TelephonyManager` (each method's "Required permissions" block).
- WifiInfo perm changes — Android 8.0/10/11 release notes on Wi-Fi privacy
  hardening; `WifiInfo.getSsid()` Javadoc explicitly enumerates redaction
  conditions.
- `getAllCellInfo` location-permission gating — `TelephonyManager`
  Javadoc, Android 10 (Q) behavior changes page.
- MAC randomization — `WifiInfo.getMacAddress()` Javadoc + Android 6.0
  behavior changes ("Access to Hardware Identifier").
- `Build.getSerial()` privileged restriction — Android 10 privacy changes.
- `StorageStatsManager` — `android.app.usage.StorageStatsManager`.
- `Debug.getRuntimeStat` keys — `android.os.Debug` Javadoc.
- `/proc/self/status`, `/proc/self/stat` field layout — Linux man-page
  `proc(5)`; readable by the owning process on Android with no permission.
