package com.a8s.android

import java.io.File

/**
 * Common message + post-action screenshot path for the single-step UI
 * automation commands (`/tap`, `/longtap`, `/swipe`, `/key`, `/input`,
 * `/find`). Each captures a screenshot after the gesture so the caller
 * can verify the resulting UI state.
 *
 * MediaProjection consent is best-effort here — if the user never
 * granted screen capture, the gesture still ran; the reply just lacks
 * the visual evidence and notes that fact.
 */
internal const val A11Y_DISABLED_MSG: String =
    "Accessibility service not enabled. Open the app and tap " +
        "\"Enable Accessibility Service\"."

/** ms to wait after a gesture before grabbing the post-action screenshot
 *  so the UI has a chance to redraw. */
internal const val POST_GESTURE_SETTLE_MS: Long = 250

internal object UiActionReply {

    fun send(
        service: A8sService,
        config: A8sAndroid.Config,
        owner: String,
        body: String,
        kind: String,
    ) {
        if (!service.hasProjectionConsent()) {
            service.replyToOwner(
                config, owner,
                "$body (no screen-capture consent — re-grant via Grant All Permissions to attach a verification screenshot)",
            )
            return
        }
        val dest = File(File(service.cacheDir, "ui-actions"), "$kind-${System.currentTimeMillis()}.png")
        val captured = service.captureScreenshotPng(dest)
        if (captured) {
            service.replyToOwner(config, owner, body, files = listOf(dest))
        } else {
            service.replyToOwner(config, owner, "$body (post-action screenshot capture failed)")
        }
    }
}
