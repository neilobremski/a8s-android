package com.a8s.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var configDetail: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val pickJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (A8sAndroid.loadConfig(this, uri)) {
                val app = application as A8sAndroid
                app.startA8sService()
                // The service may already be running with the previous
                // config's remotes — tear those down and rebuild against
                // the new config's `remotes` map. Otherwise inbound flows
                // through the OLD client (still subscribed) but publishes
                // look up the NEW remote name and find nothing.
                A8sService.instance?.reconnectAll()
                updateUI()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.count { it.value }
        val denied = results.filterValues { !it }.keys
        A8sAndroid.log("Permissions: $granted granted, ${denied.size} denied")
        if (denied.isNotEmpty()) {
            A8sAndroid.log("Denied: ${denied.joinToString(",") { it.substringAfterLast(".") }}")
        }
        updateUI()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            A8sService.instance?.setProjectionConsent(result.resultCode, result.data!!)
            A8sAndroid.log("Screen capture: consent granted")
        } else {
            A8sAndroid.log("Screen capture: consent denied or cancelled")
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        root.addView(statusText)

        val loadBtn = Button(this).apply {
            text = "Load Configuration JSON"
            setOnClickListener {
                pickJsonLauncher.launch(arrayOf("application/json"))
            }
        }
        root.addView(loadBtn)

        val notifAccessBtn = Button(this).apply {
            text = "Open Notification Access (for RCS)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(notifAccessBtn)

        val captureBtn = Button(this).apply {
            text = "Enable Screen Capture (for /screenshot)"
            setOnClickListener {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }
        root.addView(captureBtn)

        configDetail = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 32)
        }
        root.addView(configDetail)
        
        // Log Section
        val logLabel = TextView(this).apply {
            text = "Console Logs:"
            textSize = 14f
            setPadding(0, 32, 0, 8)
        }
        root.addView(logLabel)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            // Hardcoded dark-on-light to stay readable regardless of system
            // DayNight theme — the inherited theme color was white on light
            // gray and unreadable in dark mode.
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        logText = TextView(this).apply {
            textSize = 12f
            setPadding(16, 16, 16, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF111111.toInt())
        }
        logScroll.addView(logText)
        root.addView(logScroll)

        setContentView(root)

        A8sAndroid.onLogListener = {
            runOnUiThread {
                logText.text = A8sAndroid.getLogs()
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        requestMissingPermissions()
        updateUI()
    }

    private fun requiredDangerousPermissions(): List<String> {
        // Permissions Android marks "dangerous" — every one of these requires
        // a runtime grant on API 23+ regardless of manifest declaration.
        // POST_NOTIFICATIONS is only dangerous from API 33 (Tiramisu) onward;
        // older devices get the grant implicitly and don't need it requested.
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }

    private fun missingPermissions(): List<String> = requiredDangerousPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    override fun onResume() {
        super.onResume()
        // Returning from Settings may have changed permission or notification-
        // listener status; refresh so the UI reflects reality.
        updateUI()
    }

    override fun onDestroy() {
        A8sAndroid.onLogListener = null
        super.onDestroy()
    }

    private fun installedVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "v${info.versionName} (build ${info.longVersionCode})"
    } catch (e: Exception) {
        "v?"
    }

    private fun updateUI() {
        val config = A8sAndroid.config
        val missing = missingPermissions()
        if (config == null) {
            statusText.text = "a8s Android ${installedVersion()}\nStatus: Not Configured"
            configDetail.text = "Please load an a8s.json file to start."
        } else {
            statusText.text = "a8s Android ${installedVersion()}\nStatus: Configured as " + config.device
            val sb = StringBuilder()
            sb.append("Owner: ").append(config.owner ?: "(none)").append("\n")
            sb.append("Forward: ").append(config.forward ?: "(none)").append("\n")
            // Remotes — show first by name → broker, "+N more" if more.
            if (config.remotes.isEmpty()) {
                sb.append("Remote: (none configured)\n")
            } else {
                val (firstName, firstRc) = config.remotes.entries.first().toPair()
                val rest = config.remotes.size - 1
                sb.append("Remote: ").append(firstName).append(" → ").append(firstRc.broker)
                if (rest > 0) sb.append(" (+").append(rest).append(" more)")
                sb.append("\n")
            }
            // Storage services — first id, "+N more" if more.
            if (config.services.isEmpty()) {
                sb.append("Storage: (none)\n")
            } else {
                sb.append("Storage: ").append(config.services.first().id)
                val rest = config.services.size - 1
                if (rest > 0) sb.append(" (+").append(rest).append(" more)")
                sb.append("\n")
            }
            // Phonebook — first entry, "+N more" if more.
            if (config.phonebook.isEmpty()) {
                sb.append("Phonebook: (empty)\n")
            } else {
                val (firstName, firstNumber) = config.phonebook.entries.first().toPair()
                val rest = config.phonebook.size - 1
                sb.append("Phonebook: ").append(firstName).append(" → ").append(firstNumber)
                if (rest > 0) sb.append(" (+").append(rest).append(" more)")
                sb.append("\n")
            }
            if (missing.isEmpty()) {
                sb.append("Permissions: all granted\n")
            } else {
                sb.append("Permissions missing: ")
                sb.append(missing.joinToString(", ") { it.substringAfterLast(".") })
                sb.append("\n")
            }
            sb.append("Notification access (RCS): ")
            sb.append(if (isNotificationAccessGranted()) "granted" else "NOT granted — tap button above")
            configDetail.text = sb.toString()
        }
        logText.text = A8sAndroid.getLogs()
    }
}
