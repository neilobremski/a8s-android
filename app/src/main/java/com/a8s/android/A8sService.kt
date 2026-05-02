package com.a8s.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

class A8sService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "a8s_android_channel"
        private const val NOTIF_ID = 1001
        const val SMS_SENT_ACTION = "com.a8s.android.SMS_SENT"

        var instance: A8sService? = null
            private set
    }

    private val smsRequestSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var sentResultReceiver: BroadcastReceiver? = null

    private var mqttClient: MqttAsyncClient? = null
    private val isConnected = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val publishDedup = PublishDedup()
    private var serviceStartMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceStartMs = System.currentTimeMillis()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a8s:mqtt")
        wakeLock.acquire(10 * 60 * 1000L)

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(wifiMode, "a8s:wifi")
        wifiLock.acquire()

        registerNetworkCallback()
        registerSentResultReceiver()
        connect()
    }

    private fun registerSentResultReceiver() {
        if (sentResultReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val rc = resultCode
                val recipient = intent?.getStringExtra("recipient") ?: "?"
                val part = intent?.getIntExtra("part", -1) ?: -1
                val of = intent?.getIntExtra("of", -1) ?: -1
                val verdict = when (rc) {
                    android.app.Activity.RESULT_OK -> "OK"
                    android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
                    android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
                    android.telephony.SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
                    android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
                    else -> "rc=$rc"
                }
                A8sAndroid.log("SMS send result $verdict to $recipient (part ${part + 1}/$of)")
            }
        }
        val filter = IntentFilter(SMS_SENT_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
        sentResultReceiver = r
    }

    override fun onDestroy() {
        A8sAndroid.log("Service stopping")
        instance = null
        sentResultReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        sentResultReceiver = null
        mqttClient?.disconnect()
        super.onDestroy()
    }

    private fun connect() {
        val config = A8sAndroid.config ?: return
        if (isConnected.get()) return

        try {
            val serverUri = config.remote.url
            mqttClient = MqttAsyncClient(serverUri, "a8s-android-" + config.device, MemoryPersistence())

            val opts = MqttConnectOptions().apply {
                userName = config.remote.username
                password = config.remote.password.toCharArray()
                isCleanSession = true
                if (serverUri.startsWith("ssl://") || serverUri.startsWith("mqtts://")) {
                    socketFactory = SSLSocketFactory.getDefault()
                }
            }

            mqttClient!!.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    isConnected.set(false)
                    updateNotification("Disconnected")
                    A8sAndroid.log("MQTT Connection Lost: " + cause?.message)
                    handler.postDelayed({ connect() }, 5000)
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMqttMessage(String(message.payload))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            A8sAndroid.log("MQTT Connecting to $serverUri")
            mqttClient!!.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    isConnected.set(true)
                    updateNotification("Connected")
                    A8sAndroid.log("MQTT Connected")
                    subscribe()
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    A8sAndroid.log("MQTT Connect Failed: " + exception?.message)
                    handler.postDelayed({ connect() }, 5000)
                }
            })
        } catch (e: Exception) {
            A8sAndroid.log("MQTT setup error: " + e.message)
            handler.postDelayed({ connect() }, 5000)
        }
    }

    private fun subscribe() {
        val config = A8sAndroid.config ?: return
        mqttClient?.subscribe(config.remote.topic, 1)
        A8sAndroid.log("MQTT Subscribed to " + config.remote.topic)
    }

    private fun handleMqttMessage(payload: String) {
        val config = A8sAndroid.config ?: return
        when (val route = decideRoute(payload, config)) {
            is MqttRoute.Forward -> {
                A8sAndroid.log("MQTT -> SMS forward to ${route.number}: ${preview(route.smsBody)}")
                sendSms(route.number, route.smsBody)
            }
            is MqttRoute.Phonebook -> {
                A8sAndroid.log("MQTT -> SMS to ${route.name} (${route.number}): ${preview(route.smsBody)}")
                sendSms(route.number, route.smsBody)
            }
            is MqttRoute.Command -> {
                A8sAndroid.log("/${route.name} from owner=${route.owner}")
                executeCommand(route)
            }
            is MqttRoute.Drop -> {
                A8sAndroid.log("MQTT -> drop (${route.reason})")
            }
            is MqttRoute.ParseError -> {
                A8sAndroid.log("MQTT Handle Error: ${route.reason}")
            }
        }
    }

    private fun preview(s: String, max: Int = 200): String {
        val flat = s.replace("\n", " ").trim()
        return if (flat.length <= max) flat else "${flat.take(max)}…"
    }

    private fun executeCommand(cmd: MqttRoute.Command) {
        val config = A8sAndroid.config ?: return
        // /update is async — it does HTTP + filesystem work. Run it on a
        // worker thread so we don't block paho's network thread; reply
        // arrives whenever it's ready.
        if (cmd.name == "update") {
            Thread { runUpdateCommand(config, cmd) }.start()
            return
        }
        val reply = when (cmd.name) {
            "info" -> Commands.renderInfo(snapshotInfo(config))
            "logs" -> Commands.renderLogs(A8sAndroid.getLogs(), Commands.parseLogsArgs(cmd.args))
            else -> Commands.renderUnknown(cmd.name)
        }
        publishToOwner(config, cmd.owner, reply)
    }

    private fun runUpdateCommand(config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val checkOnly = cmd.args.any { it == "--check" || it == "-c" }
        val explicitUrl = cmd.args.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        try {
            val installedVersion = installedVersionName()
            if (checkOnly) {
                val latest = Updater.fetchLatestRelease()
                publishToOwner(config, cmd.owner, Updater.renderCheck(installedVersion, latest))
                return
            }
            val (downloadUrl, fileName) = if (explicitUrl != null) {
                Pair(explicitUrl, "explicit-${System.currentTimeMillis()}.apk")
            } else {
                val latest = Updater.fetchLatestRelease()
                if (Updater.compareVersions(installedVersion, latest.versionName) >= 0) {
                    publishToOwner(
                        config, cmd.owner,
                        "Already up to date (installed v$installedVersion, latest ${latest.tagName}). " +
                            "Use /update <url> to force a specific build.",
                    )
                    return
                }
                publishToOwner(
                    config, cmd.owner,
                    "Update available: v$installedVersion → ${latest.tagName} " +
                        "(${Updater.humanSize(latest.sizeBytes)}). Downloading…",
                )
                Pair(latest.apkUrl, latest.apkName)
            }
            val dest = File(File(cacheDir, "updates"), fileName)
            Updater.downloadTo(downloadUrl, dest)
            A8sAndroid.log("Update downloaded to ${dest.absolutePath} (${dest.length()} bytes)")
            triggerInstallPrompt(dest)
            publishToOwner(
                config, cmd.owner,
                "Downloaded ${Updater.humanSize(dest.length())}. Install dialog launched on phone — " +
                    "tap Install on the device to apply.",
            )
        } catch (e: Exception) {
            A8sAndroid.log("Update failed: ${e.message}")
            publishToOwner(config, cmd.owner, "Update failed: ${e.message}")
        }
    }

    private fun installedVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun triggerInstallPrompt(apk: File) {
        val authority = "$packageName.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun snapshotInfo(config: A8sAndroid.Config): Commands.InfoSnapshot {
        val pInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }
        val appVersion = if (pInfo != null) {
            "v${pInfo.versionName} (build ${pInfo.longVersionCode})"
        } else "v?"
        val networkType = describeActiveNetwork()
        val (battPct, charging) = readBattery()
        return Commands.InfoSnapshot(
            appVersion = appVersion,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            mqttConnected = isConnected.get(),
            mqttBroker = config.remote.url,
            mqttTopic = config.remote.topic,
            networkType = networkType,
            batteryPercent = battPct,
            batteryCharging = charging,
            uptimeMs = if (serviceStartMs == 0L) 0L else System.currentTimeMillis() - serviceStartMs,
            phonebookSize = config.phonebook.size,
            ownerSet = !config.owner.isNullOrBlank(),
            forwardSet = !config.forward.isNullOrBlank(),
        )
    }

    private fun describeActiveNetwork(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val active = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(active) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    private fun readBattery(): Pair<Int?, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Pair(null, false)
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        return Pair(pct, charging)
    }

    private fun publishToOwner(config: A8sAndroid.Config, owner: String, body: String) {
        val payload = JSONObject().apply {
            put("id", Ulid.new())
            put("date", isoNowUtc())
            put("from", config.device)
            put("to", owner)
            put("content", body)
            put("files", org.json.JSONArray())
        }.toString()
        try {
            mqttClient?.publish(config.remote.topic, MqttMessage(payload.toByteArray()))
            A8sAndroid.log("CMD -> MQTT ${config.device} -> $owner (${body.length} chars)")
        } catch (e: Exception) {
            A8sAndroid.log("MQTT Publish Failed: ${e.message}")
        }
    }

    private fun sendSms(to: String, body: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            A8sAndroid.log("SMS Send blocked: SEND_SMS not granted — open the app and grant permissions")
            return
        }
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            // sentIntent fires once per part (the system splits long
            // messages); we use a single-part PendingIntent and
            // sendMultipartTextMessage so the result reaches us for
            // every chunk.
            val parts = smsManager.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(SMS_SENT_ACTION).apply {
                    setPackage(packageName)
                    putExtra("recipient", to)
                    putExtra("part", i)
                    putExtra("of", parts.size)
                }
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this, smsRequestSeq.incrementAndGet(), intent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
            smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, null)
            A8sAndroid.log("SMS sent (queued) to $to: ${preview(body)}")
        } catch (e: Exception) {
            A8sAndroid.log("SMS Send Failed: " + e.message)
        }
    }

    fun publishIncoming(fromIdentity: String, body: String) {
        val config = A8sAndroid.config ?: return

        // SMS gives us the raw phone number directly. RCS notifications give
        // us the contact's display name (e.g. "Neil C. Obremski"). Try the
        // phonebook with the input as-is first; if no match, look up the
        // contact in ContactsContract to resolve display name → phone, then
        // re-try the phonebook lookup.
        val direct = fromIdentity.replace("[^0-9+]".toRegex(), "")
        val phonebookNames = if (direct.isNotEmpty()) {
            config.phonebook.filterValues { it.replace("[^0-9+]".toRegex(), "") == direct }.keys
        } else {
            emptySet()
        }
        val matchedNames = if (phonebookNames.isNotEmpty()) {
            phonebookNames
        } else {
            val resolved = phoneNumberForDisplayName(fromIdentity)
                ?.replace("[^0-9+]".toRegex(), "")
            if (resolved.isNullOrEmpty()) {
                A8sAndroid.log("Ignored incoming from $fromIdentity (no phone number resolved)")
                return
            }
            val byContact = config.phonebook.filterValues {
                it.replace("[^0-9+]".toRegex(), "") == resolved
            }.keys
            if (byContact.isEmpty()) {
                A8sAndroid.log("Ignored incoming from $fromIdentity (resolved to $resolved, not in phonebook)")
                return
            }
            byContact
        }

        // Reply destined for the cluster participant whose number we
        // matched. The phone is acting as the device participant
        // (config.device); the cluster sees the message as coming from
        // it. One envelope per matched name (rare, but a phonebook can
        // have aliases).
        matchedNames.forEach { name ->
            // Dedup: Google Messages re-posts notifications on thread
            // updates and the same SMS can fire both SmsReceiver and the
            // notification listener. Same recipient + same body within
            // the dedup window is treated as one message.
            if (!publishDedup.shouldPublish("$name|$body")) {
                A8sAndroid.log("Skipping duplicate to $name (already sent recently)")
                return@forEach
            }
            val payload = JSONObject().apply {
                put("id", Ulid.new())
                put("date", isoNowUtc())
                put("from", config.device)
                put("to", name)
                put("content", body)
                put("files", org.json.JSONArray())
            }.toString()

            try {
                mqttClient?.publish(config.remote.topic, MqttMessage(payload.toByteArray()))
                A8sAndroid.log("SMS -> MQTT ${config.device} -> $name: ${preview(body)}")
            } catch (e: Exception) {
                A8sAndroid.log("MQTT Publish Failed: " + e.message)
            }
        }
    }

    private fun isoNowUtc(): String {
        // 2026-05-02T01:23:45Z — same shape as Python a8s envelopes.
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    private fun phoneNumberForDisplayName(name: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            A8sAndroid.log("Cannot resolve $name: READ_CONTACTS not granted")
            return null
        }
        return try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            A8sAndroid.log("Contacts lookup failed for $name: ${e.message}")
            null
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                A8sAndroid.log("Network Available")
                handler.post { connect() }
            }
        }
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "a8s Status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("a8s Android")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
