package com.a8s.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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
                updateUI()
            }
        }
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
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        logText = TextView(this).apply {
            textSize = 12f
            setPadding(16, 16, 16, 16)
            typeface = android.graphics.Typeface.MONOSPACE
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
        
        updateUI()
    }

    override fun onDestroy() {
        A8sAndroid.onLogListener = null
        super.onDestroy()
    }

    private fun updateUI() {
        val config = A8sAndroid.config
        if (config == null) {
            statusText.text = "Status: Not Configured"
            configDetail.text = "Please load an a8s.json file to start."
        } else {
            statusText.text = "Status: Configured as " + config.device
            val sb = StringBuilder()
            sb.append("Remote URL: ").append(config.remote.url).append("\n")
            sb.append("Topic: ").append(config.remote.topic).append("\n")
            sb.append("Phonebook: (").append(config.phonebook.size).append(" entries)")
            configDetail.text = sb.toString()
        }
        logText.text = A8sAndroid.getLogs()
    }
}
