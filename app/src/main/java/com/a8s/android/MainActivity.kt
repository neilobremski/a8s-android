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

        // Primary one-tap CTA — bundles every dangerous runtime perm,
        // the screen-capture projection consent, and (if needed) the
        // Notification Listener settings page. Battery exemption is
        // already prompted at app start so it's not in this flow.
        val grantAllBtn = Button(this).apply {
            text = "Grant All Permissions"
            setOnClickListener { grantAllPermissions() }
        }
        root.addView(grantAllBtn)

        val notifAccessBtn = Button(this).apply {
            text = "Open Notification Access (for RCS)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(notifAccessBtn)

        val captureBtn = Button(this).apply {
            text = "Enable Screen Capture (for /screenshot)"
            setOnClickListener { launchProjectionConsent() }
        }
        root.addView(captureBtn)

        val a11yBtn = Button(this).apply {
            text = "Enable Accessibility Service (for /tap, /macro)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(a11yBtn)

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
        // POST_NOTIFICATIONS / READ_MEDIA_* are only dangerous from API 33
        // (Tiramisu) onward; older devices get them implicitly via the
        // legacy READ_EXTERNAL_STORAGE shape.
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            // /photo, /video
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            // /location
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_AUDIO
        }
        return perms
    }

    private fun launchProjectionConsent() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    /**
     * Bundle the entire grant flow into one tap:
     * 1. Request every dangerous runtime permission.
     * 2. After the user dismisses that dialog, fire the screen-capture
     *    projection-consent system dialog (because consent is held
     *    in-memory and is lost on process restart — re-grant lives here
     *    so /screenshot starts working again after an in-place upgrade).
     * 3. If notification-listener access (RCS) isn't already granted,
     *    open its settings page (special permission — can't be requested
     *    via the runtime dialog).
     *
     * Battery-optimization exemption is auto-prompted on app start in
     * `A8sAndroid` and is intentionally NOT bundled here.
     */
    private fun grantAllPermissions() {
        // Always request — even already-granted perms are tolerated by
        // the launcher; the system just immediately resolves them as
        // GRANTED and the per-perm flow doesn't reprompt.
        val perms = requiredDangerousPermissions()
        permissionLauncher.launch(perms.toTypedArray())
        // The projection dialog is launched right after — Android queues
        // the runtime perm dialog first; this lands once the user
        // dismisses it.
        launchProjectionConsent()
        if (!isNotificationAccessGranted()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        // Accessibility access is also a special permission — can't be
        // granted via the runtime dialog. If our service isn't enabled
        // yet, jump to the Accessibility Settings page so it shows up
        // in the same one-tap grant flow.
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES is a colon-
        // separated list of ComponentName flat-strings. We check for
        // our service's component name; substring-match is fine since
        // the package qualifier disambiguates against other apps' a11y
        // services that happen to share a class simple name.
        val flatName = "$packageName/.A11yService"
        val expandedFlatName = "$packageName/com.a8s.android.A11yService"
        val raw = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        } catch (_: Exception) {
            null
        } ?: return false
        return raw.split(':').any { entry ->
            entry == flatName || entry == expandedFlatName
        }
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
            sb.append("\n")
            sb.append("Accessibility service (UI automation): ")
            sb.append(if (isAccessibilityServiceEnabled()) "enabled" else "NOT enabled — tap button above")
            configDetail.text = sb.toString()
        }
        logText.text = A8sAndroid.getLogs()
    }
}
