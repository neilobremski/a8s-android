package com.a8s.android

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
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

        var config: Config? = null
            private set
            
        fun updateConfig(newConfig: Config) {
            config = newConfig
        }

        private val logs = LinkedList<String>()
        var onLogListener: (() -> Unit)? = null

        fun log(msg: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$timestamp] $msg"
            Log.i(TAG, line)
            synchronized(logs) {
                logs.add(line)
                if (logs.size > 50) logs.removeFirst()
            }
            onLogListener?.invoke()
        }

        fun getLogs(): String = synchronized(logs) { logs.joinToString("\n") }

        private const val REPLY_ACTION_TTL_MS = 30 * 60 * 1000L
        private const val REPLY_ACTION_MAX_SIZE = 20

        data class CachedReply(
            val actionIntent: PendingIntent,
            val remoteInputKey: String,
            val timestamp: Long,
        )

        private val replyActions = mutableMapOf<String, CachedReply>()

        fun cacheReplyAction(sender: String, action: Notification.Action) {
            val remoteInput = action.remoteInputs?.firstOrNull() ?: return
            if (replyActions.size >= REPLY_ACTION_MAX_SIZE) {
                val oldest = replyActions.minByOrNull { it.value.timestamp }?.key
                oldest?.let { replyActions.remove(it) }
            }
            replyActions[sender] = CachedReply(
                actionIntent = action.actionIntent,
                remoteInputKey = remoteInput.resultKey,
                timestamp = System.currentTimeMillis(),
            )
        }

        fun getReplyAction(sender: String): CachedReply? {
            val cached = replyActions[sender] ?: return null
            if (System.currentTimeMillis() - cached.timestamp > REPLY_ACTION_TTL_MS) {
                replyActions.remove(sender)
                return null
            }
            return cached
        }

        fun getReplyActionByDigits(digits: String): CachedReply? {
            val normalized = digits.replace(Regex("[^0-9]"), "")
            for ((key, value) in replyActions) {
                val keyDigits = key.replace(Regex("[^0-9]"), "")
                if (keyDigits == normalized || keyDigits.endsWith(normalized) || normalized.endsWith(keyDigits)) {
                    if (System.currentTimeMillis() - value.timestamp > REPLY_ACTION_TTL_MS) {
                        replyActions.remove(key)
                        return null
                    }
                    return value
                }
            }
            return null
        }

        fun listReplySenders(): Set<String> = replyActions.keys.toSet()

        fun loadConfig(context: Context, uri: Uri? = null): Boolean {
            if (uri != null) {
                return loadConfigFromSaf(context, uri)
            }
            val cached = SecureConfigStore(context).loadConfigJson()
            if (cached != null) {
                return loadConfigFromCache(context, cached)
            }
            return false
        }

        private fun loadConfigFromSaf(context: Context, targetUri: Uri): Boolean {
            val resolver = context.contentResolver
            try {
                resolver.openInputStream(targetUri)?.use { stream ->
                    val rawText = stream.bufferedReader().use { it.readText() }
                    val parsed = parseConfigJson(rawText) ?: return false
                    SecureConfigStore(context).saveConfigJson(rawText)
                    config = parsed
                    logConfigLoaded(parsed, "SAF")
                    return true
                }
            } catch (e: Exception) {
                log("Config error: " + (e.message ?: "unknown"))
            }
            return false
        }

        private fun loadConfigFromCache(context: Context, cachedJson: String): Boolean {
            try {
                val parsed = parseConfigJson(cachedJson) ?: run {
                    SecureConfigStore(context).clear()
                    return false
                }
                config = parsed
                logConfigLoaded(parsed, "encrypted store")
                return true
            } catch (e: Exception) {
                log("Config error (encrypted store): " + (e.message ?: "unknown"))
                SecureConfigStore(context).clear()
            }
            return false
        }

        private fun parseConfigJson(rawText: String): Config? {
            return try {
                ConfigParser.parse(JSONObject(rawText))
            } catch (e: Exception) {
                log("Config error: ${e.message}")
                null
            }
        }

        private fun logConfigLoaded(parsed: Config, source: String) {
            log(
                "Config loaded ($source): device=${parsed.device}, " +
                    "principals=${parsed.registry.localAgents.size}, " +
                    "remotes=${parsed.remotes.size}, " +
                    "services=${parsed.services.size}",
            )
        }
    }

    data class Config(
        val device: String,
        val registry: PrincipalRegistry,
        val remotes: Map<String, RemoteConfig>,
        val services: List<StorageService>,
        val smsThrottleMs: Long,
        val smsChunkLimit: Int,
        val smsRawStorageRefs: Boolean = false,
        val smsStickyTtlMs: Long = 1800L * 1000L,
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
