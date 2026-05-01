package com.a8s.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File

class A8sAndroid : Application() {

    companion object {
        private const val TAG = "A8sAndroid"
        private const val CONFIG_FILENAME = "a8s.json"
        
        var config: Config? = null
            private set

        fun loadConfig(context: android.content.Context): Boolean {
            try {
                // Try Documents folder first
                val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                var configFile = File(docDir, CONFIG_FILENAME)
                
                if (!configFile.exists()) {
                    // Try app private files
                    configFile = File(context.getExternalFilesDir(null), CONFIG_FILENAME)
                }

                if (!configFile.exists()) {
                    Log.w(TAG, "Config file not found")
                    return false
                }

                val json = JSONObject(configFile.readText())
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
                Log.i(TAG, "Config loaded")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config: %s", e.message)
                return false
            }
        }
    }

    data class Config(
        val device: String,
        val phonebook: Map<String, String>, // Name -> Phone
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
                        data = Uri.parse("package:%s" + packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not request battery optimization exclusion")
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