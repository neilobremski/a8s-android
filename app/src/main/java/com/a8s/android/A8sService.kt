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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import javax.net.ssl.SSLSocketFactory

class A8sService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "a8s_android_channel"
        private const val NOTIF_ID = 1001
        const val SMS_SENT_ACTION = "com.a8s.android.SMS_SENT"
        private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val UPDATE_CHECK_INITIAL_DELAY_MS = 60L * 1000

        var instance: A8sService? = null
            private set

        // Verb -> handler map. The handler receives the live service so
        // it can call `replyToSender` and reach the cached MediaProjection
        // consent. Keeping the dispatch as a map (rather than a `when` in
        // executeCommand) keeps the cyclomatic complexity below detekt's
        // ceiling as new verbs land.
        private val asyncCommands: Map<String, (A8sService, A8sAndroid.Config, MqttRoute.Command) -> Unit> = mapOf(
            "update" to { s, c, k -> s.runUpdateCommand(c, k) },
            "screenshot" to { s, c, k -> s.runScreenshotCommand(c, k) },
            "photo" to { s, c, k -> CmdPhoto.run(s, c, k) },
            "video" to { s, c, k -> CmdVideo.run(s, c, k) },
            "audio" to { s, c, k -> CmdAudio.run(s, c, k) },
            "location" to { s, c, k -> CmdLocation.run(s, c, k) },
            "say" to { s, c, k -> CmdSay.run(s, c, k) },
            "notify" to { s, c, k -> CmdNotify.run(s, c, k) },
            "ls" to { s, c, k -> CmdLs.run(s, c, k) },
            "cat" to { s, c, k -> CmdCat.run(s, c, k) },
            "rm" to { s, c, k -> CmdRm.run(s, c, k) },
            "tap" to { s, c, k -> CmdTap.run(s, c, k) },
            "longtap" to { s, c, k -> CmdLongtap.run(s, c, k) },
            "swipe" to { s, c, k -> CmdSwipe.run(s, c, k) },
            "key" to { s, c, k -> CmdKey.run(s, c, k) },
            "input" to { s, c, k -> CmdInput.run(s, c, k) },
            "find" to { s, c, k -> CmdFind.run(s, c, k) },
            "macro" to { s, c, k -> CmdMacro.run(s, c, k) },
            "send" to { s, c, k ->
                val parts = CmdHelpers.parseSendArgs(k.args)
                if (parts == null) {
                    s.replyToSender(c, k.sender, "usage: /send <number> <message>")
                } else {
                    val body = CmdHelpers.buildSendBody(parts.body, k.files)
                    s.sendSms(parts.number, body)
                    s.replyToSender(c, k.sender, "SMS queued to ${parts.number}: ${s.preview(body)}")
                }
            },
            "mms" to { s, c, k -> CmdMms.run(s, c, k) },
            "reply" to { s, c, k -> CmdReply.run(s, c, k) },
            "download" to { s, c, k -> CmdDownload.run(s, c, k) },
            "dashboard" to { s, c, k -> CmdDashboard.run(s, c, k) },
        )
    }

    private val smsRequestSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var sentResultReceiver: BroadcastReceiver? = null

    // One paho client per configured remote, keyed by remote name. The
    // map is mutated only on the main thread (`connectAll`/`onDestroy`),
    // so a plain MutableMap is enough — paho's own callbacks don't add
    // entries.
    private val mqttClients = mutableMapOf<String, MqttAsyncClient>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val publishDedup = PublishDedup()
    private val retryQueue = PublishRetryQueue(
        scheduler = { delayMs, runnable -> handler.postDelayed(runnable, delayMs) },
    )
    private var serviceStartMs: Long = 0L
    private var mmsObserver: MmsObserver? = null
    private val updateCheckRunnable = Runnable { checkForUpdate() }

    // Cached MediaProjection consent. Set by MainActivity after the user
    // grants screen capture; held until the service dies. We store the
    // raw resultCode + Intent rather than the live MediaProjection so
    // each /screenshot can build a fresh projection (Android revokes the
    // projection after a single capture in some configurations).
    private var projectionResultCode: Int = 0
    private var projectionData: Intent? = null

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
        retryQueue.publishFn = { topic, payload ->
            tryPublishToAnyConnected(topic, payload)
        }
        connectAll()
        startMmsObserver()
        scheduleUpdateCheck()
    }

    private fun startMmsObserver() {
        mmsObserver = MmsObserver(this, handler).also { it.register() }
    }

    private fun scheduleUpdateCheck() {
        handler.postDelayed(updateCheckRunnable, UPDATE_CHECK_INITIAL_DELAY_MS)
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val installed = installedVersionName()
                val latest = Updater.fetchLatestRelease()
                if (Updater.compareVersions(installed, latest.versionName) >= 0) {
                    A8sAndroid.log("Update check: up to date (v$installed)")
                } else {
                    A8sAndroid.log("Update check: v$installed → ${latest.tagName} available, downloading...")
                    val dest = File(File(cacheDir, "updates"), latest.apkName)
                    Updater.downloadTo(latest.apkUrl, dest)
                    A8sAndroid.log("Update check: downloaded ${Updater.humanSize(dest.length())}, triggering install")
                    triggerInstallPrompt(dest)
                }
            } catch (e: Exception) {
                A8sAndroid.log("Update check: failed (${e.message})")
            }
            handler.postDelayed(updateCheckRunnable, UPDATE_CHECK_INTERVAL_MS)
        }.start()
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
        handler.removeCallbacks(updateCheckRunnable)
        mmsObserver?.unregister()
        mmsObserver = null
        retryQueue.clear()
        sentResultReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        sentResultReceiver = null
        mqttClients.values.forEach { c ->
            try { c.disconnect() } catch (_: Exception) { }
        }
        mqttClients.clear()
        super.onDestroy()
    }

    private fun connectAll() {
        val config = A8sAndroid.config ?: return
        config.remotes.forEach { (name, rc) -> connectOne(name, rc) }
    }

    /**
     * Tear down every active client and reconnect against the current
     * `A8sAndroid.config`. Called from `MainActivity` after the user
     * reloads `a8s.json`. Without this, a config that renames remotes
     * (e.g. legacy singular shape with the implicit "default" name →
     * new shape with named entries like "hivemq") leaves the OLD
     * subscriber alive (so inbound still works) while publishes look
     * up the NEW name in `mqttClients` and find nothing.
     */
    fun reconnectAll() {
        A8sAndroid.log("Reconnecting all remotes after config reload")
        mqttClients.values.forEach { c ->
            try { c.disconnect() } catch (_: Exception) { }
            try { c.close() } catch (_: Exception) { }
        }
        mqttClients.clear()
        connectAll()
    }

    private fun connectOne(name: String, rc: RemoteConfig) {
        val existing = mqttClients[name]
        if (existing != null && existing.isConnected) return
        // Drop any stale half-open client before opening a new one.
        existing?.let {
            try { it.disconnect() } catch (_: Exception) { }
            try { it.close() } catch (_: Exception) { }
        }

        try {
            val device = A8sAndroid.config?.device ?: "a8s-android"
            val client = MqttAsyncClient(
                rc.broker,
                "a8s-android-$device-$name",
                MemoryPersistence(),
            )
            val opts = MqttConnectOptions().apply {
                userName = rc.username
                password = rc.password?.toCharArray()
                isCleanSession = true
                if (rc.broker.startsWith("ssl://") || rc.broker.startsWith("mqtts://")) {
                    socketFactory = SSLSocketFactory.getDefault()
                }
            }
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    A8sAndroid.log("MQTT[$name] Connection Lost: " + cause?.message)
                    updateNotification(connectionStatusSummary())
                    handler.postDelayed({ connectOne(name, rc) }, 5000)
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMqttMessage(String(message.payload))
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            A8sAndroid.log("MQTT[$name] Connecting to ${rc.broker}")
            client.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    A8sAndroid.log("MQTT[$name] Connected")
                    try {
                        client.subscribe(rc.topic, 1)
                        A8sAndroid.log("MQTT[$name] Subscribed to ${rc.topic}")
                    } catch (e: Exception) {
                        A8sAndroid.log("MQTT[$name] Subscribe failed: ${e.message}")
                    }
                    updateNotification(connectionStatusSummary())
                    retryQueue.flushOnReconnect(name) { topic, payload ->
                        tryPublish(client, topic, payload)
                    }
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    A8sAndroid.log("MQTT[$name] Connect Failed: " + exception?.message)
                    updateNotification(connectionStatusSummary())
                    handler.postDelayed({ connectOne(name, rc) }, 5000)
                }
            })
            mqttClients[name] = client
        } catch (e: Exception) {
            A8sAndroid.log("MQTT[$name] setup error: " + e.message)
            handler.postDelayed({ connectOne(name, rc) }, 5000)
        }
    }

    private fun connectionStatusSummary(): String {
        val total = A8sAndroid.config?.remotes?.size ?: 0
        val connected = mqttClients.values.count { it.isConnected }
        return if (total == 0) "Disconnected" else "Connected $connected/$total"
    }

    private fun handleMqttMessage(payload: String) {
        val config = A8sAndroid.config ?: return
        when (val route = decideRoute(payload, config)) {
            is MqttRoute.NotACommand -> {
                val reply = "error: message must start with a /command\n" +
                    "known: " + CmdHelpers.KNOWN_COMMANDS.joinToString(", ")
                publishToSender(config, route.sender, reply)
            }
            is MqttRoute.Command -> CommandDispatch.handle(route, ::executeCommand)
            is MqttRoute.Drop -> {
                A8sAndroid.log("MQTT -> drop (${route.reason})")
            }
            is MqttRoute.ParseError -> {
                A8sAndroid.log("MQTT Handle Error: ${route.reason}")
            }
        }
    }

    internal fun preview(s: String, max: Int = 200): String {
        val flat = s.replace("\n", " ").trim()
        return if (flat.length <= max) flat else "${flat.take(max)}…"
    }

    private fun executeCommand(cmd: MqttRoute.Command) {
        val config = A8sAndroid.config ?: return
        // Anything that does camera, network, or potentially-slow IO runs
        // on a fresh worker thread so paho's network thread isn't blocked;
        // the reply lands whenever the handler finishes.
        val async = asyncCommands[cmd.name]
        if (async != null) {
            Thread { async(this, config, cmd) }.start()
            return
        }
        val reply = when (cmd.name) {
            "info" -> {
                val verbose = cmd.args.any {
                    it.equals("verbose", ignoreCase = true) || it == "-v" || it == "--verbose"
                }
                Commands.renderInfo(InfoSnapshotter.capture(this, config, verbose), verbose)
            }
            "logs" -> Commands.renderLogs(A8sAndroid.getLogs(), Commands.parseLogsArgs(cmd.args))
            else -> Commands.renderUnknown(cmd.name)
        }
        publishToSender(config, cmd.sender, reply)
    }

    /**
     * Public wrapper around `publishToSender` for the `Cmd*` handlers.
     * They live in their own files but need to send replies through
     * the same wire-format / storage-upload path as everything else.
     */
    fun replyToSender(
        config: A8sAndroid.Config,
        sender: String,
        body: String,
        files: List<File> = emptyList(),
    ) {
        publishToSender(config, sender, body, files)
    }

    /** Called from MainActivity after the user grants screen-capture
     *  consent. We hold the result so subsequent /screenshot commands
     *  can rebuild the MediaProjection without prompting again. */
    fun setProjectionConsent(resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionData = data
    }

    /** True when the user has granted MediaProjection consent in the
     *  current process lifetime. The macro/UI-automation paths use this
     *  to fail-fast before doing any other setup. */
    fun hasProjectionConsent(): Boolean =
        projectionData != null && projectionResultCode != 0

    /** One-shot PNG capture for the macro / accessibility commands.
     *  Builds a fresh MediaProjection from the cached consent, captures
     *  one frame to `dest`, releases the projection. Returns true on
     *  success. Caller is responsible for already verifying consent
     *  exists via `hasProjectionConsent()`. */
    fun captureScreenshotPng(dest: File): Boolean {
        val data = projectionData ?: return false
        if (projectionResultCode == 0) return false
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        var projection: MediaProjection? = null
        return try {
            projection = mgr.getMediaProjection(projectionResultCode, data) ?: return false
            Screenshot(this, projection).capture(dest)
        } catch (e: Exception) {
            A8sAndroid.log("captureScreenshotPng failed: ${e.message}")
            false
        } finally {
            try { projection?.stop() } catch (_: Exception) { }
        }
    }

    /** Build a fresh MediaProjection for callers that need to drive a
     *  longer-lived VirtualDisplay (e.g. CmdMacro's screen recorder).
     *  Caller owns the lifecycle — must call `stop()` when done. */
    fun acquireMediaProjection(): MediaProjection? {
        val data = projectionData ?: return null
        if (projectionResultCode == 0) return null
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            mgr.getMediaProjection(projectionResultCode, data)
        } catch (e: Exception) {
            A8sAndroid.log("acquireMediaProjection failed: ${e.message}")
            null
        }
    }

    private fun runScreenshotCommand(config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val data = projectionData
        if (data == null || projectionResultCode == 0) {
            publishToSender(
                config, cmd.sender,
                "Screen capture not authorized — consent is held in-memory and " +
                    "is lost on app/process restart (e.g. after /update reinstall). " +
                    "Open the app and tap \"Grant All Permissions\" (or " +
                    "\"Enable Screen Capture (for /screenshot)\") to re-grant.",
            )
            return
        }
        if (config.services.isEmpty()) {
            publishToSender(
                config, cmd.sender,
                "Cannot send screenshot: no storage service configured. " +
                    "Add a `services` entry to a8s.json (e.g. tempfile_org).",
            )
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        var projection: MediaProjection? = null
        try {
            projection = mgr.getMediaProjection(projectionResultCode, data)
            if (projection == null) {
                publishToSender(config, cmd.sender, "Screen capture failed: projection unavailable")
                return
            }
            val dest = File(File(cacheDir, "screenshots"), "screenshot-${System.currentTimeMillis()}.png")
            val captured = Screenshot(this, projection).capture(dest)
            if (!captured) {
                publishToSender(config, cmd.sender, "Screen capture failed: timed out waiting for frame")
                return
            }
            A8sAndroid.log("Screenshot captured: ${dest.length()} bytes")
            publishToSender(
                config, cmd.sender,
                "Screenshot (${dest.length()} bytes)",
                files = listOf(dest),
            )
        } catch (e: Exception) {
            A8sAndroid.log("Screenshot failed: ${e.message}")
            publishToSender(config, cmd.sender, "Screenshot failed: ${e.message}")
        } finally {
            try { projection?.stop() } catch (_: Exception) { }
        }
    }

    private fun runUpdateCommand(config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val checkOnly = cmd.args.any { it == "--check" || it == "-c" }
        val explicitUrl = cmd.args.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        try {
            val installedVersion = installedVersionName()
            if (checkOnly) {
                val latest = Updater.fetchLatestRelease()
                publishToSender(config, cmd.sender, Updater.renderCheck(installedVersion, latest))
                return
            }
            val (downloadUrl, fileName) = if (explicitUrl != null) {
                Pair(explicitUrl, "explicit-${System.currentTimeMillis()}.apk")
            } else {
                val latest = Updater.fetchLatestRelease()
                if (Updater.compareVersions(installedVersion, latest.versionName) >= 0) {
                    publishToSender(
                        config, cmd.sender,
                        "Already up to date (installed v$installedVersion, latest ${latest.tagName}). " +
                            "Use /update <url> to force a specific build.",
                    )
                    return
                }
                publishToSender(
                    config, cmd.sender,
                    "Update available: v$installedVersion → ${latest.tagName} " +
                        "(${Updater.humanSize(latest.sizeBytes)}). Downloading…",
                )
                Pair(latest.apkUrl, latest.apkName)
            }
            val dest = File(File(cacheDir, "updates"), fileName)
            Updater.downloadTo(downloadUrl, dest)
            A8sAndroid.log("Update downloaded to ${dest.absolutePath} (${dest.length()} bytes)")
            triggerInstallPrompt(dest)
            publishToSender(
                config, cmd.sender,
                "Downloaded ${Updater.humanSize(dest.length())}. Install dialog launched on phone — " +
                    "tap Install on the device to apply.",
            )
        } catch (e: Exception) {
            A8sAndroid.log("Update failed: ${e.message}")
            publishToSender(config, cmd.sender, "Update failed: ${e.message}")
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

    /** Used by `InfoSnapshotter.capture` to read live MQTT connection
     *  state without exposing the `mqttClients` map. */
    fun remoteStatuses(config: A8sAndroid.Config): List<Commands.RemoteStatus> =
        config.remotes.map { (name, rc) ->
            Commands.RemoteStatus(
                name = name,
                broker = rc.broker,
                topic = rc.topic,
                connected = mqttClients[name]?.isConnected == true,
            )
        }

    /** ms since `onCreate`. Zero if the service hasn't started yet. */
    fun serviceUptimeMs(): Long =
        if (serviceStartMs == 0L) 0L else System.currentTimeMillis() - serviceStartMs

    private fun publishToSender(
        config: A8sAndroid.Config,
        sender: String,
        body: String,
        files: List<File> = emptyList(),
    ) {
        val filesArr = buildFilesArray(config, files)
        val payload = JSONObject().apply {
            put("id", Ulid.new())
            put("date", isoNowUtc())
            put("from", config.device)
            put("to", sender)
            put("content", body)
            put("files", filesArr)
        }.toString()
        val (ok, fail) = publishToAllRemotes(config, payload)
        A8sAndroid.log(
            "CMD -> MQTT ${config.device} -> $sender " +
                "(${body.length} chars, ${files.size} file(s); ${ok}/${ok + fail} remotes)",
        )
    }

    /**
     * Upload each file to every configured storage service, wrap into
     * the wire shape `[{filename, storage: [url, ...]}, ...]`. Empty
     * `storage` array if all services failed (caller's already logged
     * the per-service failure).
     */
    private fun buildFilesArray(
        config: A8sAndroid.Config,
        files: List<File>,
    ): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (file in files) {
            val urls = org.json.JSONArray()
            for (svc in config.services) {
                try {
                    urls.put(svc.store(file))
                    A8sAndroid.log("Storage[${svc.id}] uploaded ${file.name}")
                } catch (e: StorageException) {
                    A8sAndroid.log("Storage[${svc.id}] upload failed: ${e.message}")
                }
            }
            arr.put(JSONObject().apply {
                put("filename", file.name)
                if (urls.length() > 0) put("storage", urls)
            })
        }
        return arr
    }

    private fun tryPublish(client: MqttAsyncClient, topic: String, payload: ByteArray): Boolean {
        return try {
            client.publish(topic, MqttMessage(payload))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryPublishToAnyConnected(topic: String, payload: ByteArray): Boolean {
        val config = A8sAndroid.config ?: return false
        for ((name, rc) in config.remotes) {
            if (rc.topic != topic) continue
            val client = mqttClients[name] ?: continue
            if (!client.isConnected) continue
            if (tryPublish(client, rc.topic, payload)) return true
        }
        return false
    }

    private fun publishToAllRemotes(config: A8sAndroid.Config, payload: String): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        val bytes = payload.toByteArray()
        config.remotes.forEach { (name, rc) ->
            val client = mqttClients[name]
            if (client == null || !client.isConnected) {
                A8sAndroid.log("MQTT[$name] publish queued: not connected")
                retryQueue.enqueue(name, rc.topic, bytes)
                fail++
                return@forEach
            }
            if (tryPublish(client, rc.topic, bytes)) {
                ok++
            } else {
                A8sAndroid.log("MQTT[$name] publish failed, queuing for retry")
                retryQueue.enqueue(name, rc.topic, bytes)
                fail++
            }
        }
        return Pair(ok, fail)
    }

    internal fun sendSms(to: String, body: String) {
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

    fun publishIncoming(
        fromIdentity: String,
        body: String,
        mediaFiles: List<File> = emptyList(),
        replyAction: android.app.Notification.Action? = null,
    ) {
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
        val resolvedNumber: String
        val matchedNames = if (phonebookNames.isNotEmpty()) {
            resolvedNumber = direct
            phonebookNames
        } else {
            val resolved = phoneNumberForDisplayName(fromIdentity)
                ?.replace("[^0-9+]".toRegex(), "")
            if (resolved.isNullOrEmpty()) {
                A8sAndroid.log("Ignored incoming from $fromIdentity (no phone number resolved)")
                return
            }
            resolvedNumber = resolved
            val byContact = config.phonebook.filterValues {
                it.replace("[^0-9+]".toRegex(), "") == resolved
            }.keys
            if (byContact.isEmpty()) {
                A8sAndroid.log("Ignored incoming from $fromIdentity (resolved to $resolved, not in phonebook)")
                return
            }
            byContact
        }

        // Cache reply action keyed by phone number for /reply command
        if (replyAction != null && resolvedNumber.isNotEmpty()) {
            A8sAndroid.cacheReplyAction(resolvedNumber, replyAction)
        }

        // Reply destined for the cluster participant whose number we
        // matched. The phone is acting as the device participant
        // (config.device); the cluster sees the message as coming from
        // it. One envelope per matched name (rare, but a phonebook can
        // have aliases).
        if (mediaFiles.isNotEmpty()) {
            // Upload once, then publish the same URLs to each matched name
            Thread {
                val filesArr = buildFilesArray(config, mediaFiles)
                matchedNames.forEach { name ->
                    if (!publishDedup.shouldPublish("$name|$body")) {
                        A8sAndroid.log("Skipping duplicate to $name (already sent recently)")
                        return@forEach
                    }
                    val payload = buildIncomingPayload(config, name, body, filesArr)
                    val (ok, fail) = publishToAllRemotes(config, payload)
                    A8sAndroid.log(
                        "SMS -> MQTT ${config.device} -> $name: ${preview(body)} " +
                            "[+${mediaFiles.size} file(s)] (${ok}/${ok + fail} remotes)",
                    )
                }
                mediaFiles.forEach { it.delete() }
            }.start()
        } else {
            matchedNames.forEach { name ->
                if (!publishDedup.shouldPublish("$name|$body")) {
                    A8sAndroid.log("Skipping duplicate to $name (already sent recently)")
                    return@forEach
                }
                val payload = buildIncomingPayload(config, name, body, org.json.JSONArray())
                val (ok, fail) = publishToAllRemotes(config, payload)
                A8sAndroid.log(
                    "SMS -> MQTT ${config.device} -> $name: ${preview(body)} " +
                        "(${ok}/${ok + fail} remotes)",
                )
            }
        }
    }

    private fun buildIncomingPayload(
        config: A8sAndroid.Config,
        toName: String,
        body: String,
        filesArr: org.json.JSONArray,
    ): String = JSONObject().apply {
        put("id", Ulid.new())
        put("date", isoNowUtc())
        put("from", config.device)
        put("to", toName)
        put("content", body)
        put("files", filesArr)
    }.toString()

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
                handler.post { connectAll() }
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
