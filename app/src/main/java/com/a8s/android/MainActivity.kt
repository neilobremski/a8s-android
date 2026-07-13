package com.a8s.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebSettings
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var configDetail: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deleteSourceCheckbox: CheckBox
    private lateinit var dashboardWebView: WebView
    private lateinit var contentFrame: FrameLayout
    private lateinit var setupPanel: ScrollView
    private lateinit var tabDashboard: Button
    private lateinit var tabLogs: Button
    private lateinit var tabSetup: Button

    private val pickJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (A8sAndroid.loadConfig(this, uri)) {
                val app = application as A8sAndroid
                app.startA8sService()
                A8sService.instance?.reconnectAll()
                if (deleteSourceCheckbox.isChecked) {
                    secureDeleteSource(uri)
                }
                updateUI()
            }
        }
    }

    private fun secureDeleteSource(uri: Uri) {
        try {
            var size = 0L
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    size += n
                }
            }
            if (size > 0) {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    val zeros = ByteArray(8 * 1024)
                    var written = 0L
                    while (written < size) {
                        val n = minOf(zeros.size.toLong(), size - written).toInt()
                        output.write(zeros, 0, n)
                        written += n
                    }
                    output.flush()
                }
            }
            val deleted = contentResolver.delete(uri, null, null)
            A8sAndroid.log("Source config secure-delete: zeroed $size bytes, delete rows=$deleted")
            Toast.makeText(this, "Source config deleted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            A8sAndroid.log("Source config secure-delete failed: ${e.message}")
            Toast.makeText(this, "Secure delete failed: ${e.message}", Toast.LENGTH_LONG).show()
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

        addStatusBlock(root)
        addConfigDetail(root)
        addTabBar(root)
        addContentArea(root)

        setContentView(root)

        A8sAndroid.onLogListener = {
            runOnUiThread {
                logText.text = A8sAndroid.getLogs()
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        Dashboard.onUpdate = {
            runOnUiThread { refreshDashboardWebView() }
        }

        requestMissingPermissions()
        updateUI()
        selectTab(Tab.DASHBOARD)
    }

    private enum class Tab { DASHBOARD, LOGS, SETUP }

    private var activeTab: Tab = Tab.DASHBOARD

    private fun selectTab(tab: Tab) {
        activeTab = tab
        val activeColor = 0xFF2196F3.toInt()
        val inactiveColor = 0xFF424242.toInt()
        val activeTextColor = 0xFFFFFFFF.toInt()
        val inactiveTextColor = 0xFFBBBBBB.toInt()

        tabDashboard.setBackgroundColor(if (tab == Tab.DASHBOARD) activeColor else inactiveColor)
        tabDashboard.setTextColor(if (tab == Tab.DASHBOARD) activeTextColor else inactiveTextColor)
        tabLogs.setBackgroundColor(if (tab == Tab.LOGS) activeColor else inactiveColor)
        tabLogs.setTextColor(if (tab == Tab.LOGS) activeTextColor else inactiveTextColor)
        tabSetup.setBackgroundColor(if (tab == Tab.SETUP) activeColor else inactiveColor)
        tabSetup.setTextColor(if (tab == Tab.SETUP) activeTextColor else inactiveTextColor)

        dashboardWebView.visibility = if (tab == Tab.DASHBOARD) View.VISIBLE else View.GONE
        logScroll.visibility = if (tab == Tab.LOGS) View.VISIBLE else View.GONE
        setupPanel.visibility = if (tab == Tab.SETUP) View.VISIBLE else View.GONE

        if (tab == Tab.DASHBOARD) refreshDashboardWebView()
        if (tab == Tab.LOGS) {
            logText.text = A8sAndroid.getLogs()
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun addStatusBlock(root: LinearLayout) {
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        root.addView(statusText)
    }

    private fun addConfigDetail(root: LinearLayout) {
        configDetail = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(configDetail)
    }

    private fun addTabBar(root: LinearLayout) {
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val tabWeight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        tabDashboard = Button(this).apply {
            text = "Dashboard"
            textSize = 13f
            layoutParams = tabWeight
            setOnClickListener { selectTab(Tab.DASHBOARD) }
        }
        tabLogs = Button(this).apply {
            text = "Logs"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(Tab.LOGS) }
        }
        tabSetup = Button(this).apply {
            text = "Setup"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(Tab.SETUP) }
        }

        tabBar.addView(tabDashboard)
        tabBar.addView(tabLogs)
        tabBar.addView(tabSetup)
        root.addView(tabBar)
    }

    private fun addContentArea(root: LinearLayout) {
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        // Dashboard WebView
        dashboardWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(0xFF1A1A1A.toInt())
            visibility = View.GONE
        }
        contentFrame.addView(dashboardWebView)

        // Logs ScrollView
        logScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF1A1A1A.toInt())
            visibility = View.GONE
        }
        logText = TextView(this).apply {
            textSize = 11f
            setPadding(16, 16, 16, 16)
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFCCCCCC.toInt())
        }
        logScroll.addView(logText)
        contentFrame.addView(logScroll)

        // Setup panel
        setupPanel = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
        val setupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        addConfigBlock(setupContent)
        addPermissionButtons(setupContent)
        addDiagnosticsBlock(setupContent)
        setupPanel.addView(setupContent)
        contentFrame.addView(setupPanel)

        root.addView(contentFrame)
    }

    private fun refreshDashboardWebView() {
        val content = Dashboard.getContent(this)
        val bgPath = Dashboard.getBgPath(this)
        val bgCss = if (bgPath != null) "file://$bgPath" else ""
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            body {
              margin: 0; padding: 16px;
              font-family: -apple-system, sans-serif;
              color: #fff;
              background-color: #1a1a1a;
              background-size: cover;
              background-position: center;
              min-height: 100vh;
            }
            </style>
            </head>
            <body style="background-image: url('$bgCss')">
            $content
            </body>
            </html>
        """.trimIndent()
        dashboardWebView.loadDataWithBaseURL("file:///", html, "text/html", "UTF-8", null)
    }

    private fun addConfigBlock(root: LinearLayout) {
        val loadBtn = Button(this).apply {
            text = "Load Configuration JSON"
            setOnClickListener {
                pickJsonLauncher.launch(arrayOf("application/json"))
            }
        }
        root.addView(loadBtn)
        deleteSourceCheckbox = CheckBox(this).apply {
            text = "Permanently delete source file after loading"
            isChecked = false
        }
        root.addView(deleteSourceCheckbox)
    }

    private fun addPermissionButtons(root: LinearLayout) {
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
    }

    private fun addDiagnosticsBlock(root: LinearLayout) {
        val header = TextView(this).apply {
            text = "Media Diagnostics"
            textSize = 16f
            setPadding(0, 32, 0, 8)
        }
        root.addView(header)

        val uploadBtn = Button(this).apply {
            text = "Test Media Upload"
            setOnClickListener { testMediaUpload() }
        }
        root.addView(uploadBtn)

        val replyBtn = Button(this).apply {
            text = "Reply Action Status"
            setOnClickListener { showReplyStatus() }
        }
        root.addView(replyBtn)

        val flushDedupBtn = Button(this).apply {
            text = "Flush Dedup Cache"
            setOnClickListener {
                A8sService.instance?.publishDedup?.clear()
                Toast.makeText(this@MainActivity, "Dedup cache flushed", Toast.LENGTH_SHORT).show()
                A8sAndroid.log("Diagnostics: Dedup cache flushed")
            }
        }
        root.addView(flushDedupBtn)

        val clearBtn = Button(this).apply {
            text = "Clear Media Cache"
            setOnClickListener { clearMediaCache() }
        }
        root.addView(clearBtn)
    }

    private fun testMediaUpload() {
        val config = A8sAndroid.config
        if (config == null) {
            Toast.makeText(this, "Not configured", Toast.LENGTH_SHORT).show()
            return
        }
        if (config.services.isEmpty()) {
            Toast.makeText(this, "No storage services configured", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(0xFF2196F3.toInt())
                val paint = android.graphics.Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 14f
                    isAntiAlias = true
                }
                canvas.drawText("TEST", 25f, 55f, paint)

                val dest = java.io.File(cacheDir, "test-upload-${System.currentTimeMillis()}.png")
                dest.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                }
                bitmap.recycle()

                A8sAndroid.log("Test upload: generated ${dest.length()} byte test image")

                var uploadedUrl: String? = null
                for (svc in config.services) {
                    try {
                        uploadedUrl = svc.store(dest)
                        A8sAndroid.log("Test upload: success via ${svc.id} -> $uploadedUrl")
                        break
                    } catch (e: StorageException) {
                        A8sAndroid.log("Test upload: ${svc.id} failed: ${e.message}")
                    }
                }

                dest.delete()

                runOnUiThread {
                    if (uploadedUrl != null) {
                        Toast.makeText(this, "Upload OK: $uploadedUrl", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "All storage services failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                A8sAndroid.log("Test upload: error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showReplyStatus() {
        val senders = A8sAndroid.listReplySenders()
        if (senders.isEmpty()) {
            Toast.makeText(this, "No cached reply actions", Toast.LENGTH_SHORT).show()
            A8sAndroid.log("Reply status: no cached actions")
        } else {
            val msg = "Cached reply actions: ${senders.joinToString(", ")}"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            A8sAndroid.log("Reply status: $msg")
        }
    }

    private fun clearMediaCache() {
        var freed = 0L
        val dirs = listOf("media-extract", "mms-outbound", "downloads")
        for (name in dirs) {
            val dir = java.io.File(cacheDir, name)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    freed += file.length()
                    file.delete()
                }
                dir.delete()
            }
        }
        val msg = "Cleared ${freed / 1024} KB from media cache"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        A8sAndroid.log("Media cache: $msg")
    }

    private fun requiredDangerousPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
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

    private fun grantAllPermissions() {
        val perms = requiredDangerousPermissions()
        permissionLauncher.launch(perms.toTypedArray())
        launchProjectionConsent()
        if (!isNotificationAccessGranted()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
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
        updateUI()
    }

    override fun onDestroy() {
        A8sAndroid.onLogListener = null
        Dashboard.onUpdate = null
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
            if (config.remotes.isEmpty()) {
                sb.append("Remote: (none configured)\n")
            } else {
                val (firstName, firstRc) = config.remotes.entries.first().toPair()
                val rest = config.remotes.size - 1
                sb.append("Remote: ").append(firstName).append(" -> ").append(firstRc.broker)
                if (rest > 0) sb.append(" (+").append(rest).append(" more)")
                sb.append("\n")
            }
            if (config.services.isEmpty()) {
                sb.append("Storage: (none)\n")
            } else {
                sb.append("Storage: ").append(config.services.first().id)
                val rest = config.services.size - 1
                if (rest > 0) sb.append(" (+").append(rest).append(" more)")
                sb.append("\n")
            }
            if (config.registry.localAgents.isEmpty()) {
                sb.append("Principals: (empty)\n")
            } else {
                val agents = config.registry.localAgents.sorted()
                val first = agents.first()
                val rest = agents.size - 1
                val phone = config.registry.phoneForAgent(first)
                sb.append("Principals: ").append(first)
                if (phone != null) sb.append(" (").append(phone).append(")")
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
            sb.append(if (isNotificationAccessGranted()) "granted" else "NOT granted")
            sb.append("\n")
            sb.append("Accessibility service (UI automation): ")
            sb.append(if (isAccessibilityServiceEnabled()) "enabled" else "NOT enabled")
            configDetail.text = sb.toString()
        }
        logText.text = A8sAndroid.getLogs()
    }
}
