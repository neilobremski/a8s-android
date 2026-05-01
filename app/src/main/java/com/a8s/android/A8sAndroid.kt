package com.a8s.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.util.*

class A8sAndroid : Application() {

    companion object {
        private const val TAG = "A8sAndroid"
        private const val CONFIG_PREF = "config_uri"
        
        var config: Config? = null
            private set

        private val logs = LinkedList<String>()
        var onLogListener: (() -> Unit)? = null

        fun log(msg: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[] "
            Log.i(TAG, line)
            synchronized(logs) {
                logs.add(line)
                if (logs.size > 50) logs.removeFirst()
            }
            onLogListener?.invoke()
        }

        fun getLogs(): String = synchronized(logs) { logs.joinToString("\n") }

        fun loadConfig(context: Context, uri: Uri? = null): Boolean {
            val resolver = context.contentResolver
            val targetUri = uri ?: getSavedUri(context) ?: return false
            
            try {
                resolver.openInputStream(targetUri)?.use { stream ->
                    val text = stream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    
                    val device = json.getString("device")
                    val phonebookMap = mutableMapOf<String, String>()
                    val phonebookJson = json.getJSONObject("phonebook")
                    val keys = phonebookJson.keys()
                    while (keys.hasNext()) {
                        val name = keys.next()
                        phonebookMap[name] = phonebookJson.getString(name)
                    }

                    val remoteJson = json.getJSONObject("remote")
                    val remote = RemoteConfig(
                        url = remoteJson.getString("url"),
                        topic = remoteJson.getString("topic"),
                        username = remoteJson.optString("username", ""),
                        password = remoteJson.optString("password", "")
                    )

                    config = Config(device, phonebookMap, remote)
                    saveUri(context, targetUri)
                    log("Config loaded: ")
                    return true
                }
            } catch (e: Exception) {
                log("Config error: " + (e.message ?: "unknown"))
            }
            return false
        }

        private fun saveUri(context: Context, uri: Uri) {
            context.getSharedPreferences("a8s", MODE_PRIVATE)
                .edit().putString(CONFIG_PREF, uri.toString()).apply()
        }

        private fun getSavedUri(context: Context): Uri? {
            val s = context.getSharedPreferences("a8s", MODE_PRIVATE)
                .getString(CONFIG_PREF, null) ?: return null
            return Uri.parse(s)
        }
    }

    data class Config(
        val device: String,
        val phonebook: Map<String, String>,
        val remote: RemoteConfig
    )

    data class RemoteConfig(
        val url: String,
        val topic: String,
        val username: String,
        val password: String
    )

    override fun onCreate() {
        super.onCreate()
        log("App starting")
        requestBatteryOptimizationExclusion()
        if (loadConfig(this)) {
            startA8sService()
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:" + packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    log("Battery permission failed")
                }
            }
        }
    }

    fun startA8sService() {
        val intent = Intent(this, A8sService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
