package com.a8s.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

class A8sService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "a8s_android_channel"
        private const val NOTIF_ID = 1001

        var instance: A8sService? = null
            private set
    }

    private var mqttClient: MqttAsyncClient? = null
    private val isConnected = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        connect()
    }

    override fun onDestroy() {
        A8sAndroid.log("Service stopping")
        instance = null
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
                A8sAndroid.log("MQTT -> SMS forward to ${route.number}")
                sendSms(route.number, route.smsBody)
            }
            is MqttRoute.Phonebook -> {
                A8sAndroid.log("MQTT -> SMS to ${route.name} (${route.number})")
                sendSms(route.number, route.smsBody)
            }
            is MqttRoute.Drop -> {
                A8sAndroid.log("MQTT -> drop (${route.reason})")
            }
            is MqttRoute.ParseError -> {
                A8sAndroid.log("MQTT Handle Error: ${route.reason}")
            }
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
            smsManager.sendTextMessage(to, null, body, null, null)
        } catch (e: Exception) {
            A8sAndroid.log("SMS Send Failed: " + e.message)
        }
    }

    fun publishIncoming(fromPhone: String, body: String) {
        val config = A8sAndroid.config ?: return
        val normalizedFrom = fromPhone.replace("[^0-9+]".toRegex(), "")
        
        val names = config.phonebook.filterValues { 
            it.replace("[^0-9+]".toRegex(), "") == normalizedFrom 
        }.keys

        if (names.isEmpty()) {
            A8sAndroid.log("Ignored incoming from $fromPhone (not in phonebook)")
            return
        }

        names.forEach { name ->
            val payload = JSONObject().apply {
                put("from", name)
                put("to", "all")
                put("body", body)
            }.toString()
            
            try {
                mqttClient?.publish(config.remote.topic, MqttMessage(payload.toByteArray()))
                A8sAndroid.log("SMS -> MQTT from $name")
            } catch (e: Exception) {
                A8sAndroid.log("MQTT Publish Failed: " + e.message)
            }
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
