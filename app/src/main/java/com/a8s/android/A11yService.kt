package com.a8s.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AccessibilityService that exposes synthetic gestures and node-walking
 * to the rest of the app via the static `instance` companion field.
 *
 * The service runs on the system-managed accessibility thread; gesture
 * dispatch must be funneled through the main thread, and the gesture
 * completion callbacks fire there too. Each public method blocks the
 * caller (a worker thread spawned by `A8sService.executeCommand`) on a
 * `CountDownLatch` so multiple steps can be sequenced without races.
 *
 * The accessibility-service permission (`BIND_ACCESSIBILITY_SERVICE`) is
 * signature-protected and declared on the service element in the
 * manifest — there is no runtime permission to request, the user has to
 * toggle the service on in Settings.
 */
class A11yService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        A8sAndroid.log("A11yService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        A8sAndroid.log("A11yService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    override fun onInterrupt() { /* no-op */ }

    fun tap(x: Float, y: Float, durationMs: Long = TAP_MS): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun longTap(x: Float, y: Float, durationMs: Long = LONG_TAP_MS): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = SWIPE_MS): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun globalAction(name: String): Boolean {
        val action = GLOBAL_ACTIONS[name.uppercase()] ?: return false
        return performGlobalAction(action)
    }

    fun inputText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        // AccessibilityNodeInfo.recycle() is deprecated post API 33 — the
        // platform manages the pool internally now and recycle is a no-op.
        // Drop the focused reference at scope end and let GC handle it.
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun findAndTap(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findMatching(root, label.lowercase()) ?: return false
        return try {
            match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            // Don't recycle root — the system owns it. The matched node
            // came from walking root's children; recycling it isn't safe
            // either if it IS the root, so just let it be GC'd.
        }
    }

    private fun findMatching(node: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        if (text.contains(needle) || desc.contains(needle)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findMatching(child, needle)
            if (hit != null) return hit
        }
        return null
    }

    private fun dispatch(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(g: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }
        // dispatchGesture must be invoked on the main thread; the callback
        // also fires on the main thread. The caller is a worker thread,
        // so post the dispatch and wait on the latch.
        mainHandler.post { dispatchGesture(gesture, callback, mainHandler) }
        return try {
            latch.await(GESTURE_TIMEOUT_S, TimeUnit.SECONDS) && success
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        const val TAP_MS: Long = 50
        const val LONG_TAP_MS: Long = 800
        const val SWIPE_MS: Long = 300
        private const val GESTURE_TIMEOUT_S: Long = 10

        private val GLOBAL_ACTIONS: Map<String, Int> = mapOf(
            "BACK" to GLOBAL_ACTION_BACK,
            "HOME" to GLOBAL_ACTION_HOME,
            "RECENTS" to GLOBAL_ACTION_RECENTS,
            "NOTIFICATIONS" to GLOBAL_ACTION_NOTIFICATIONS,
            "QUICK_SETTINGS" to GLOBAL_ACTION_QUICK_SETTINGS,
            "LOCK_SCREEN" to GLOBAL_ACTION_LOCK_SCREEN,
        )

        @Volatile
        var instance: A11yService? = null
            private set
    }
}
