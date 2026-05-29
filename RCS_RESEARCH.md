# RCS on Android: Third-Party App Access Research

Research date: May 2026

## Executive Summary

RCS (Rich Communication Services) on Android is effectively a closed system controlled by Google
Messages and carrier partnerships through Google Jibe. There is **no public SDK** for third-party
apps to send or receive RCS messages. The platform APIs that exist (`android.telephony.ims.*`) are
system-only, requiring privileged permissions that third-party apps cannot obtain. This document
covers what works, what doesn't, and practical workarounds for a messaging bridge app.

---

## 1. Why RCS Is Hard for Third-Party Apps

### The Google Messages Monopoly

- Google Messages is the **sole RCS client** on virtually all Android devices. Unlike SMS/MMS
  (which any app can become the "default" handler for), RCS has no equivalent "default RCS app"
  role in Android.
- RCS is delivered through **Google Play Services** and the Google Messages app, not through AOSP
  telephony APIs. This means it's a proprietary Google layer, not an open platform feature.
- The GSMA Universal Profile standard defines the protocol, but does not mandate that OS vendors
  expose it to third-party apps.

### No Public SDK

- Google has never released a public RCS SDK for person-to-person messaging.
- The only developer-facing RCS product is **RCS Business Messaging** (formerly Google Jibe RBM),
  which is exclusively for verified businesses sending to consumers — not P2P messaging.
- There is no `RcsManager` or `RcsMessageSender` equivalent to `SmsManager` in the public API.

### System-Only Platform APIs

Android does have RCS-related classes in `android.telephony.ims`:

| Class | Purpose | Access Level |
|-------|---------|--------------|
| `ImsRcsManager` | RCS service management | `@SystemApi` — system apps only |
| `RcsUceAdapter` | User Capability Exchange (presence) | `@SystemApi` — system apps only |
| `RcsConfig` | RCS provisioning config | `@SystemApi` — system apps only |
| `RcsClientConfiguration` | Client config parameters | `@SystemApi` — system apps only |
| `RcsContactUceCapability` | Contact capability info | `@SystemApi` — system apps only |

All require permissions like `READ_PRIVILEGED_PHONE_STATE`, `ACCESS_RCS_USER_CAPABILITY_EXCHANGE`,
or `MODIFY_PHONE_STATE` — none grantable to third-party apps.

### What UCE Actually Does

The only semi-public RCS API surface is **User Capability Exchange** — it tells you whether a
contact supports RCS features (video calling, chat, file transfer). But even this is locked behind
system permissions. You cannot use it to **send** or **receive** messages.

---

## 2. Current State of RCS APIs (2024-2026)

### Android Platform (AOSP)

- **No public messaging API.** The `android.telephony.ims` package provides plumbing for OEMs and
  carriers to implement RCS at the system level, but nothing for app developers.
- **No content provider for RCS messages.** Unlike SMS (`content://sms`) and MMS (`content://mms`),
  RCS messages are stored in Google Messages' private database, not in a system-wide content
  provider.
- **No "default RCS app" role.** The `Telephony.Sms.getDefaultSmsPackage()` role does not extend
  to RCS. Even if your app is the default SMS app, RCS messages still go to Google Messages.

### Google Jibe / RCS Business Messaging

- Jibe is Google's RCS infrastructure that carriers connect to (or let Google host on their behalf).
- The developer-facing product is **RCS for Business** — a B2C messaging platform.
- Requirements: registered partner, verified brand, agent creation, launch approval.
- **Not usable for P2P messaging or personal automation.** It's designed for businesses sending
  branded messages to customers.

### Carrier APIs

- Some carriers (historically) offered proprietary RCS APIs (e.g., Sprint's RCS SDK before T-Mobile
  merger). These are all **defunct or deprecated**.
- No major US carrier currently offers a third-party RCS API.
- The carrier relationship is now mediated entirely through Google Jibe.

### Samsung

- Samsung Messages historically had its own RCS implementation but has largely ceded to Google
  Messages on newer devices.
- Samsung does not expose RCS APIs to third-party developers either.
- Some Samsung devices still run Samsung Messages with RCS, but there's no SDK.

---

## 3. Workarounds for SENDING Images/Files via RCS

### What Definitely Does NOT Work

- **SmsManager** — Only handles SMS and MMS. No RCS support whatsoever.
- **Direct RCS protocol** — You cannot implement RCS yourself because it requires carrier
  provisioning, authentication with the Jibe/carrier hub, and SIM-level credentials.
- **ImsRcsManager** — System-only. Will throw SecurityException.

### What Partially Works

#### Intent-Based Sharing (Best Available Option)

```kotlin
// Share an image to Google Messages with ACTION_SEND
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/jpeg"
    putExtra(Intent.EXTRA_STREAM, imageUri)
    setPackage("com.google.android.apps.messaging")
}
startActivity(intent)
```

**Limitations:**
- Opens the Google Messages UI — not background/programmatic.
- Cannot pre-fill the recipient phone number via ACTION_SEND.
- User must manually select the conversation and tap send.
- Not suitable for automated bridge operation.

#### smsto: URI + Attachment (Limited)

```kotlin
val intent = Intent(Intent.ACTION_SENDTO).apply {
    data = Uri.parse("smsto:+1234567890")
    putExtra("sms_body", "Check this image")
    putExtra(Intent.EXTRA_STREAM, imageUri)
}
startActivity(intent)
```

**Limitations:**
- `smsto:` scheme targets SMS/MMS, not guaranteed to use RCS.
- Google Messages may upgrade to RCS automatically if recipient supports it, but this is not
  controllable or guaranteed.
- Still requires UI interaction (not fully programmatic).

#### Accessibility Service Automation (Fragile)

An Accessibility Service could theoretically:
1. Launch Google Messages to a specific conversation
2. Attach a file via UI automation
3. Tap the send button

**Limitations:**
- Extremely fragile — breaks on Google Messages UI updates.
- Google actively restricts Accessibility Service usage via Play Store policies.
- Not permitted for apps distributed through Google Play unless the app serves users with
  disabilities.
- Viable only for sideloaded/personal-use apps.

#### MMS Fallback (Reliable but Low Quality)

```kotlin
// SmsManager.sendMultimediaMessage() — requires being default SMS app
val smsManager = SmsManager.getDefault()
smsManager.sendMultimediaMessage(
    context,
    contentUri,  // PDU content URI
    locationUrl,
    configOverrides,
    sentIntent
)
```

**Requirements:**
- App **must be the default SMS app** to use this API (Android 4.4+).
- Constructs MMS PDUs manually (complex but documented).
- Falls back to MMS protocol (1MB limit, lower image quality).
- If recipient has RCS, Google Messages on their end still shows it as MMS.

**Assessment for a8s-android:** MMS fallback is the most realistic programmatic option for
sending media. It requires becoming the default SMS app, which is a significant UX trade-off
but technically feasible.

---

## 4. Workarounds for RECEIVING Images/Files via RCS

### Current Approach: NotificationListenerService

The current a8s-android approach intercepts notifications from Google Messages. Here's how to
maximize media extraction:

#### MessagingStyle Extraction (Most Promising)

Google Messages uses `Notification.MessagingStyle` for its notifications. The `Message` objects
within can contain media URIs:

```kotlin
class RcsNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.apps.messaging") return

        val notification = sbn.notification
        val extras = notification.extras

        // Extract MessagingStyle
        val messagingStyle = Notification.MessagingStyle
            .extractMessagingStyleFromNotification(notification)

        messagingStyle?.messages?.forEach { message ->
            val text = message.text
            val sender = message.senderPerson?.name
            val timestamp = message.timestamp

            // KEY: Media URI extraction
            val mimeType = message.dataMimeType  // e.g., "image/jpeg"
            val dataUri = message.dataUri         // content:// URI to the image

            if (dataUri != null) {
                // Attempt to read the image
                try {
                    val inputStream = contentResolver.openInputStream(dataUri)
                    // Successfully got the image data!
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                } catch (e: SecurityException) {
                    // URI permission not granted — common failure mode
                }
            }
        }
    }
}
```

**Reality check on dataUri:**
- Google Messages **sometimes** includes a `content://` URI in MessagingStyle messages for images.
- Whether your NotificationListenerService can actually **read** that URI depends on whether
  Google Messages grants `FLAG_GRANT_READ_URI_PERMISSION` on the notification.
- In practice, this is **inconsistent** — sometimes it works, sometimes you get SecurityException.
- Google Messages has changed this behavior across versions without notice.

#### BigPictureStyle Fallback

For some image messages, Google Messages may use `BigPictureStyle` instead:

```kotlin
val bigPicture = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
if (bigPicture != null) {
    // Got the image as a Bitmap directly from notification extras
}

// Or the URI version (API 31+)
val pictureIcon = extras.getParcelable<Icon>(Notification.EXTRA_PICTURE_ICON)
```

**Limitations:**
- BigPictureStyle bitmaps are **thumbnails** — heavily compressed, not full resolution.
- Not all image messages use BigPictureStyle; Google Messages prefers MessagingStyle.

#### Reading Google Messages Database Directly (Root/Hack)

On rooted devices or via `adb backup` exploitation:

- Google Messages stores data in `/data/data/com.google.android.apps.messaging/databases/`
- The database contains full message content including file paths.
- **Not viable for production apps** — requires root or exploit.

#### Content Provider Snooping

Google Messages does **not** expose a public content provider for RCS messages. However:

- SMS/MMS messages from Google Messages **do** appear in `content://sms` and `content://mms`.
- RCS messages do **NOT** appear in these providers — they're stored privately.
- There is no `content://rcs` provider.

#### Accessibility Service for Media Extraction

An Accessibility Service can observe the Google Messages UI and potentially:
1. Detect when an image message appears
2. Long-press to trigger save/share options
3. Intercept the share intent to capture the image

**Limitations:**
- Same fragility and policy issues as sending via Accessibility.
- Only works when Google Messages UI is active/visible.
- Cannot capture messages received while the UI is not rendered.

---

## 5. Alternative Approaches and Practical Recommendations

### Tier 1: What Actually Works Today (for a8s-android)

| Approach | Sending | Receiving | Media Quality | Reliability |
|----------|---------|-----------|---------------|-------------|
| NotificationListener + MessagingStyle | No | Text + sometimes images | Thumbnail to full | Medium |
| MMS via default SMS app role | Yes (MMS only) | Yes (MMS only) | ~1MB limit | High |
| Intent sharing (manual) | Yes (requires UI) | No | Full quality | High |

### Tier 2: Possible But Fragile

| Approach | Notes |
|----------|-------|
| Accessibility Service automation | Works but breaks frequently, policy-restricted |
| Content URI extraction from notifications | Works when Google Messages grants permission |
| BigPictureStyle bitmap extraction | Low resolution thumbnails only |

### Tier 3: Not Currently Viable

| Approach | Why Not |
|----------|---------|
| Direct RCS API calls | System-only, no public API |
| RCS Business Messaging | B2C only, requires brand verification |
| Carrier RCS APIs | Defunct/unavailable |
| AOSP telephony providers for RCS | RCS messages not stored there |
| Implementing RCS protocol directly | Requires carrier provisioning impossible without cooperation |

### Recommended Architecture for a8s-android

**For receiving (current + enhanced):**

1. **Keep NotificationListenerService** as primary ingest path.
2. **Enhance MessagingStyle extraction** — attempt `dataUri` reads for every message with media.
   Handle SecurityException gracefully; log when URIs are present but unreadable.
3. **Cache successful URI patterns** — track which content:// authority patterns are readable
   to optimize future attempts.
4. **Fall back to BigPictureStyle** bitmap when MessagingStyle dataUri fails.
5. **Accept the limitation** — some images will be thumbnail quality or text-only descriptions
   ("Alice sent an image"). This is an inherent constraint of the notification interception
   approach.

**For sending:**

1. **MMS as the reliable fallback** — if a8s-android becomes the default SMS app, use
   `SmsManager.sendMultimediaMessage()` for images. Quality is limited but it works
   programmatically.
2. **Intent-based RCS sending** — for cases where manual interaction is acceptable, use
   ACTION_SEND with Google Messages package. Not automatable.
3. **Accept text-only for automated RCS** — if the bridge must be fully automated with no UI,
   text-only via notification reply actions is the only reliable path.

**Notification Reply Actions for sending text via RCS:**

```kotlin
// Extract the RemoteInput (reply action) from notification
val actions = notification.actions
val replyAction = actions?.find { action ->
    action.remoteInputs?.isNotEmpty() == true
}

replyAction?.let { action ->
    val remoteInput = action.remoteInputs?.first()
    val intent = Intent().apply {
        val bundle = Bundle()
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, bundle.apply {
            putCharSequence(remoteInput.resultKey, "Reply text here")
        })
    }
    action.actionIntent.send(context, 0, intent)
}
```

This lets you send **text replies** through RCS via the notification's reply action — the most
reliable automated sending mechanism. But it only works for text, not media.

---

## 6. Recent Developments (2024-2026)

### Apple RCS Adoption (iOS 18, September 2024)

- Apple added RCS support to iPhone in iOS 18.
- Uses GSMA Universal Profile for cross-platform messaging.
- Carrier-by-carrier rollout (not all carriers supported initially).
- **Impact on third-party apps:** None. Apple's implementation is equally closed — only the
  native Messages app handles RCS on iOS.
- **Ecosystem effect:** Validates RCS as the successor to SMS/MMS but does not pressure
  platform vendors to open APIs.

### Google Jibe Consolidation

- Google Jibe has become the dominant RCS backend globally.
- More carriers have migrated to Google-hosted Jibe rather than running their own RCS hubs.
- This consolidation makes it **less likely** that third-party access will emerge through carrier
  diversity — there's effectively one gatekeeper now.

### Android 14-16 Changes

- No new public RCS APIs introduced in Android 14, 15, or 16.
- No new "default RCS app" role created.
- No expansion of telephony content providers to include RCS messages.
- The platform direction appears to be keeping RCS as a Google Messages exclusive indefinitely.

### Regulatory Landscape

- The EU Digital Markets Act (DMA) targets interoperability for messaging platforms, but its
  current focus is on large messaging platforms (WhatsApp, iMessage, etc.) with 45M+ EU users.
- Whether RCS/Google Messages qualifies as a DMA "gatekeeper" messaging service is unclear —
  the enforcement has focused on WhatsApp and iMessage.
- No US regulatory pressure exists to open RCS APIs.
- **Speculation:** If the DMA were applied to Google Messages, it could force API access, but
  this has not happened and is not imminent.

### GSMA Universal Profile

- The Universal Profile specification defines the wire protocol and features but does not
  mandate OS-level API exposure to third-party apps.
- The spec is carrier/OEM-facing, not developer-facing.
- No movement toward a standardized "RCS SDK" specification.

---

## 7. Key Takeaways

1. **RCS is a walled garden.** Google controls it end-to-end on Android through Messages + Jibe.
   There is no legitimate, reliable, programmatic way for a third-party app to send or receive
   RCS messages.

2. **NotificationListenerService is the best receive path.** It gives you text reliably and
   images sometimes (via MessagingStyle dataUri). This is what a8s-android should continue using,
   with enhanced media extraction attempts.

3. **For sending, text-only via notification reply actions works.** For media, you're limited to
   MMS (if you're the default SMS app) or manual intent sharing.

4. **The situation is unlikely to change soon.** Android 14-16 added nothing. Apple's adoption
   validated RCS but didn't open it. No regulatory pressure is imminent. Google has no incentive
   to open this — Messages is their iMessage competitor.

5. **MMS remains the only programmatic media sending path.** It's low quality (1MB limit) and
   requires being the default SMS app, but it actually works without user interaction.

6. **Plan for graceful degradation.** The bridge should handle: full RCS text (reliable),
   RCS images (best-effort via notification extraction), and MMS media fallback (reliable but
   lower quality).

---

## Appendix: Key Class/API Reference

| Class/API | Package | Access | Notes |
|-----------|---------|--------|-------|
| `SmsManager` | `android.telephony` | Public | SMS/MMS only, no RCS |
| `SmsManager.sendMultimediaMessage()` | `android.telephony` | Default SMS app only | MMS PDU sending |
| `ImsRcsManager` | `android.telephony.ims` | @SystemApi | UCE + registration state |
| `RcsUceAdapter` | `android.telephony.ims` | @SystemApi | Capability exchange |
| `NotificationListenerService` | `android.service.notification` | Requires user grant | Best path for interception |
| `Notification.MessagingStyle` | `android.app` | Public (read) | Extract messages + media URIs |
| `Notification.MessagingStyle.Message` | `android.app` | Public (read) | `.dataUri` + `.dataMimeType` |
| `RemoteInput` | `android.app` | Public | Reply via notification actions |
| Google Messages package | `com.google.android.apps.messaging` | N/A | Target for intents |
| `content://sms`, `content://mms` | `android.provider.Telephony` | READ_SMS | SMS/MMS only, no RCS |

---

## Appendix: Test Matrix for Media Extraction

When implementing enhanced notification media extraction, test these scenarios:

| Scenario | Expected dataUri? | Expected bitmap? | Notes |
|----------|-------------------|------------------|-------|
| Single image in 1:1 chat | Sometimes | Sometimes (BigPicture) | Best case |
| Multiple images in 1:1 chat | First only in notification | No | Notifications truncate |
| Image in group chat | Sometimes | Rare | Group notifications are summarized |
| Video message | URI if present | Thumbnail maybe | Video files harder to extract |
| File/document attachment | Unlikely | No | Usually text-only notification |
| Voice message | Unlikely | No | May get EXTRA_AUDIO_CONTENTS_URI |
| Sticker/GIF | Sometimes | Sometimes | Treated like images |

The "Sometimes" values depend on Google Messages version, Android version, and notification
channel configuration. Defensive coding with graceful fallback is essential.
