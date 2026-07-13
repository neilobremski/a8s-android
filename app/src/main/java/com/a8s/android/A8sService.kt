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
    internal val publishDedup: PublishDedup by lazy {
        PublishDedup(store = FileDedupStore(File(filesDir, "inbound_publish_dedup.json")))
    }
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
    internal var projectionResultCode: Int = 0
        private set
    internal var projectionData: Intent? = null
        private set

    private val outboundSmsQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    private var smsSenderThread: Thread? = null

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
        startSmsSenderThread()
    }

    private fun startSmsSenderThread() {
        smsSenderThread = Thread {
            while (!Thread.interrupted()) {
                val pair = outboundSmsQueue.poll()
                if (pair != null) {
                    val (to, body) = pair
                    executeSendSms(to, body)
                    val config = A8sAndroid.config
                    val throttleMs = config?.smsThrottleMs ?: 10000L
                    try {
                        Thread.sleep(throttleMs)
                    } catch (e: InterruptedException) {
                        break
                    }
                } else {
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
        smsSenderThread?.start()
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
                val installed = CmdUpdate.installedVersionName(this@A8sService)
                val latest = Updater.fetchLatestRelease()
                if (Updater.compareVersions(installed, latest.versionName) >= 0) {
                    A8sAndroid.log("Update check: up to date (v$installed)")
                } else {
                    A8sAndroid.log("Update check: v$installed → ${latest.tagName} available, downloading...")
                    val dest = File(File(cacheDir, "updates"), latest.apkName)
                    Updater.downloadTo(latest.apkUrl, dest)
                    A8sAndroid.log("Update check: downloaded ${Updater.humanSize(dest.length())}, triggering install")
                    CmdUpdate.triggerInstallPrompt(this@A8sService, dest)
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
        smsSenderThread?.interrupt()
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
        val json = try {
            JSONObject(payload)
        } catch (e: org.json.JSONException) {
            A8sAndroid.log("MQTT Handle Error: ${e.message}")
            TransactionTrace.record(
                TransactionTrace.Event(
                    flow = "MQTT_IN",
                    status = TransactionTrace.Status.FAIL,
                    summary = "invalid JSON: ${e.message}",
                ),
            )
            return
        }
        MqttInboundHandler.handle(this, json, config)
    }

    internal fun preview(s: String, max: Int = 200): String {
        val flat = s.replace("\n", " ").trim()
        return if (flat.length <= max) flat else "${flat.take(max)}…"
    }

    internal fun executeCommand(cmd: MqttRoute.Command) {
        val config = A8sAndroid.config ?: return
        // Anything that does camera, network, or potentially-slow IO runs
        // on a fresh worker thread so paho's network thread isn't blocked;
        // the reply lands whenever the handler finishes.
        val async = AsyncCommands.handlers[cmd.name]
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
            "trace" -> TransactionTrace.render(Commands.parseTraceArgs(cmd.args))
            "flushdedup" -> {
                publishDedup.clear()
                "Deduplication cache flushed."
            }
            else -> Commands.renderUnknown(cmd.name)
        }
        replyToSender(config, cmd, reply)
    }

    /** Publish a raw a8s envelope (e.g. `/tell` sub-identity). */
    fun publishEnvelope(
        from: String,
        to: String,
        content: String,
        files: org.json.JSONArray = org.json.JSONArray(),
    ): Pair<Int, Int> {
        val config = A8sAndroid.config ?: return 0 to 0
        val payload = JSONObject().apply {
            put("id", Ulid.new())
            put("date", EnvelopeTime.isoNowUtc())
            put("from", from)
            put("to", to)
            put("content", content)
            put("files", files)
        }.toString()
        return publishToAllRemotes(config, payload)
    }

    internal fun buildFilesArrayForSms(
        config: A8sAndroid.Config,
        files: List<File>,
    ): org.json.JSONArray = buildFilesArray(config, files)

    fun replyToSender(
        config: A8sAndroid.Config,
        cmd: MqttRoute.Command,
        body: String,
        files: List<File> = emptyList(),
    ) {
        replyToSender(config, cmd.sender, body, files, cmd.smsReplyTo)
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
        smsReplyTo: String? = null,
    ) {
        if (!smsReplyTo.isNullOrBlank()) {
            val smsBody = SmsCommandDelivery.smsBodyWithUploads(this, config, body, files)
            sendSms(smsReplyTo, smsBody)
            A8sAndroid.log(
                "CMD -> SMS $smsReplyTo (${smsBody.length} chars, ${files.size} file(s))",
            )
            return
        }
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

    internal fun publishToSender(
        config: A8sAndroid.Config,
        sender: String,
        body: String,
        files: List<File> = emptyList(),
    ) {
        val filesArr = buildFilesArray(config, files)
        val payload = JSONObject().apply {
            put("id", Ulid.new())
            put("date", EnvelopeTime.isoNowUtc())
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
    internal fun buildFilesArray(
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

    internal fun publishToAllRemotes(config: A8sAndroid.Config, payload: String): Pair<Int, Int> {
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
        outboundSmsQueue.add(Pair(to, body))
        A8sAndroid.log("SMS queued for $to: ${preview(body)}")
    }

    private fun executeSendSms(to: String, body: String) {
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
            recordOutboundSmsParts(to, parts)
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
            A8sAndroid.log("SMS sent to $to: ${preview(body)}")
        } catch (e: Exception) {
            A8sAndroid.log("SMS Send Failed: " + e.message)
        }
    }

    fun publishIncoming(message: IncomingSmsRouter.IncomingMessage) {
        IncomingSmsRouter.publishIncoming(this, message)
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
