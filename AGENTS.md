# a8s-android — agent onboarding

This file is the dense, accumulated knowledge a fresh agent (human or LLM)
needs to be productive in this repo without re-deriving everything from
the source tree. Keep it terse. Update when you learn something a future
contributor would want to know — especially gotchas that cost you time.

## What this is

An Android app that bridges the [a8s (Agent Infinity System)](https://github.com/neilobremski/bin/tree/main/apps/a8s)
cluster to a phone. Upstream daemon code lives at `~/bin/apps/a8s` on the
operator machine — see `A8S_CLUSTER_INTEGRATION.md` for how routing, remotes,
and opaque sub-identities relate to this app. Acts as a participant on the
MQTT topic and:

- **Command-driven model** — phonebook participants issue
  `/command args` messages; the device executes locally and replies
  over MQTT. There is no implicit forwarding or SMS gateway behavior.
- **Explicit SMS** — `/send <number> <message>`, `/mms <number> <url>`,
  `/reply <number> <text>` (fires cached RCS notification reply action).
- **SMS/RCS → MQTT** — incoming SMS or intercepted Google Messages RCS
  notifications publish back to the cluster as if they came from the
  matched phonebook participant. Media is extracted, uploaded via
  configured storage services, and attached to the outbound envelope.
- **Phonebook /commands** — any phonebook participant can issue
  `/info`, `/logs`, `/send`, `/screenshot`, `/dashboard`, etc. and get a
  `tell`'d response back. Phonebook membership *is* the privilege; there
  is no separate `owner`. Non-phonebook senders drop.

Full slash-command catalogue with arg shapes lives in `README.md`.

## Module map (under `app/src/main/java/com/a8s/android/`)

| File | Role |
|---|---|
| `A8sAndroid.kt` | `Application` subclass. Owns the static `Config` (parsed from JSON), the in-app log ring (50 lines, surfaced to the UI via `onLogListener`), and `loadConfig`/`saveUri`/`getSavedUri` for SAF persistence. Triggers `requestBatteryOptimizationExclusion` on first launch. |
| `A8sService.kt` | The brains. Foreground `LifecycleService` (type `connectedDevice`). Holds one Paho v3 client per configured remote, wake/wifi locks, `PublishDedup`, `PublishRetryQueue`, the SMS-sent broadcast receiver, and the `MmsObserver`. Routes inbound MQTT via `decideRoute` and dispatches commands via the `asyncCommands` map. Manages auto-update checks (6-hour interval, GitHub Releases API). |
| `MainActivity.kt` | Tabbed UI (Dashboard / Logs / Setup). The Dashboard tab is a full-screen `WebView` driven by `Dashboard.kt` state. The Logs tab shows the live log ring. The Setup tab has permission grants, configuration loading, and the status panel. Requests dangerous perms via `RequestMultiplePermissions`. |
| `SmsReceiver.kt` | `BroadcastReceiver` for `SMS_RECEIVED_ACTION`. Uses `Telephony.Sms.Intents.getMessagesFromIntent` (modern API; older PDU-extraction path was removed). Forwards to `A8sService.publishIncoming`. |
| `SmsNotificationListener.kt` | `NotificationListenerService` for `com.google.android.apps.messaging`. Pulls `EXTRA_TITLE` (contact display name) + `EXTRA_TEXT`. Extracts media via `MediaExtractor` (on a worker thread) and caches the notification's reply action for `/reply`. Forwards to `A8sService.publishIncoming` with optional file attachments. |
| `BootReceiver.kt` | Re-launches `A8sService` on `BOOT_COMPLETED`. |
| `MqttRoute.kt` | Sealed class + pure-Kotlin `decideRoute(payload, config)`. Variants: `Command(sender, name, args, files, envelopeId)` / `NotACommand(sender)` / `Drop(reason)` / `ParseError(reason)`. `EnvelopeFile` + `parseEnvelopeFiles` extract inbound `files[]` attachments. **All routing logic lives here so it's unit-testable.** Service layer just dispatches. |
| `Commands.kt` | Pure formatters for slash-command output. Consumes `InfoSnapshotter.InfoSnapshot` and renders via `renderInfo` / `renderLogs`. Keeps Android-specific gathering out of the formatter so it tests without a Context. |
| `InfoSnapshotter.kt` | Android-side gatherer for `/info`. `capture(context, config, verbose)` builds `InfoSnapshot` (~150 fields in verbose mode). Field catalogue in `INFO_FIELD_RESEARCH.md`. |
| `PublishDedup.kt` | Bounded LRU keyed on `<recipient>\|<body>`, default 5-minute window / 100 entries. Stops the duplicate-publish bug from Google Messages re-posting notifications. |
| `CommandDedup.kt` | Inbound dedup for `/send`, `/reply`, `/mms` before SMS/RCS fires. Two layers: envelope ULID (`id` field) and payload fingerprint (`CmdHelpers.outboundSmsDedupKey`). 5-minute window / 200 entries each. Fixes duplicate texts when upstream MQTT retries allocate fresh ULIDs (issue #36). `gateInboundSmsCommand` is the entry point. |
| `CommandDispatch.kt` | Thin wrapper: dedup gate + log + delegate to `A8sService.executeCommand`. Extracted to keep `A8sService` under detekt's `LargeClass` limit. |
| `PublishRetryQueue.kt` | Per-remote FIFO of failed MQTT publishes. Exponential backoff (1s base, 30s cap, 10 attempts max). `flushOnReconnect` drains on successful connect; `publishFn` retries against any connected client. Unit-tested. |
| `Ulid.kt` | Crockford-base32 ULID generator matching Python `apps/a8s/ulid.py`. Pure stdlib (`SecureRandom` + `BigInteger`). Required for `id` field on every outbound MQTT envelope so the host's `_process_pending` dedup ring accepts it. |
| `Updater.kt` | `/update` plumbing — fetches GitHub Releases JSON, picks the `a8s-android-*-debug.apk` asset, downloads it, and `compareVersions` to decide if newer. The actual install kicks off via `ACTION_VIEW` + FileProvider in `A8sService.triggerInstallPrompt`. JSON parsing + version compare are unit-tested; HTTP and FileProvider sit in thin Android-only wrappers. |
| `Screenshot.kt` | `/screenshot` plumbing — `MediaProjection` + `ImageReader` + `VirtualDisplay` to grab one frame, write as PNG. The user grants projection consent once via the **Enable Screen Capture** button in MainActivity; the `(resultCode, Intent)` pair is held in the service for the lifetime of the process. Each `/screenshot` builds a fresh `MediaProjection` from the cached consent, captures + releases to keep the OS's media-projection notification quiet between shots. **Consent is in-memory only and lost on process restart** (e.g. after `/update` reinstalls the app). The "Grant All Permissions" button in MainActivity re-fires the projection-consent dialog so it's bundled into one tap with the other dangerous-perm grants. |
| `CmdHelpers.kt` | Pure-Kotlin parsing + formatters for device commands. `KNOWN_COMMANDS` is the single source of truth for the `unknown command` listing. `buildSendBody` appends envelope-file storage URLs to SMS text (1600-char budget). Unit-tested in `CmdHelpersTest`. |
| `FileDownloader.kt` | Pure-Kotlin helper: given `List<EnvelopeFile>` + storage services, downloads to a dest dir (tries each service per URL). `buildSmsBody` merges fallback URLs for failed downloads. Unit-tested; `/send` currently appends URLs inline via `buildSendBody` rather than downloading attachments first. |
| `CmdMms.kt` | `/mms <number> <url>` — downloads media (storage service or raw HTTP), sends URL as text SMS (true MMS sending requires default SMS app role). |
| `CmdReply.kt` | `/reply <number> <text>` — fires the cached notification reply action intent for the given phone number (RCS-capable). Lists cached numbers on mismatch. |
| `CmdDownload.kt` | `/download <url> [filename]` — downloads a file to `/sdcard/Download`. Tries configured storage services first, falls back to raw HTTP. |
| `CmdDashboard.kt` | `/dashboard bg <url> \| content <html> \| clear` — controls the Dashboard tab state. Downloads images for the background via storage services or raw HTTP. |
| `MediaExtractor.kt` | Notification media extraction. Three strategies in priority order: `MessagingStyle.dataUri`, `BigPictureStyle`/`EXTRA_PICTURE_ICON`, `EXTRA_LARGE_ICON_BIG`. `mimeTypeToExtension` maps MIME types to file extensions. |
| `MmsObserver.kt` | `ContentObserver` on `content://mms`. Watches for new inbox MMS messages, extracts text and media parts from the telephony content provider, and publishes them via `A8sService.publishIncoming`. |
| `Dashboard.kt` | State manager for the Dashboard tab. Persists HTML content to `dashboard.html` in `filesDir` and background image path in SharedPreferences. Notifies `MainActivity` of updates via `onUpdate` callback. |
| `CmdPhoto.kt` | `/photo [front\|back]` — Camera2 still capture on a per-call HandlerThread. Saves JPEG to `cacheDir/photos/photo-<ts>.jpg`, replies via `replyToSender(... files=...)`. |
| `CmdVideo.kt` | `/video [seconds]` — Camera2 + MediaRecorder MP4 capture. Default 10s, hard-capped at 30s. Saves to `cacheDir/videos/`. Audio source = camcorder; H264/AAC at 720p/4Mbps. |
| `CmdAudio.kt` | `/audio [seconds]` — MediaRecorder MIC-only M4A capture. Default 10s, hard-capped at 60s. Saves to `cacheDir/audio/`. |
| `CmdLocation.kt` | `/location` — best-effort one-shot fix. Tries FusedLocationProviderClient via reflection (so we don't pull in `play-services-location`); falls back to `LocationManager` GPS + NETWORK with a 30s timeout. |
| `CmdSay.kt` | `/say <text>` — TextToSpeech lifecycle (init → speak → wait via `UtteranceProgressListener` → shutdown). Blocks the worker thread until playback completes so the reply (`Spoke: …`) reflects completion. |
| `CmdNotify.kt` | `/notify <title>\|<body>` — posts a NotificationCompat.BigTextStyle notification. IDs auto-increment so notifications stack rather than replace. |
| `CmdLs.kt` | `/ls [<path>]` — directory listing rendered by `CmdHelpers.renderLs`. Default path is `/sdcard/Download`. |
| `CmdCat.kt` | `/cat <path>` — reads file. Text files <= 10 KB return inline; binary or larger files attach via `replyToSender(files=...)`. |
| `CmdRm.kt` | `/rm <path>` — deletes a file or empty directory. Refuses to recurse into non-empty dirs. |
| `A11yService.kt` | `AccessibilityService` for synthetic gestures (`tap`, `longTap`, `swipe`), global actions (`globalAction("BACK"/"HOME"/...)`), focused-field text entry (`inputText`), and node-walk-and-click (`findAndTap`). Companion `instance` exposes the live binding to the `Cmd<Verb>.kt` handlers. Each gesture method blocks on a `CountDownLatch` waiting for `GestureResultCallback` so the worker thread can sequence multiple gestures with `Thread.sleep` between. Configured via `res/xml/accessibility_service_config.xml` (`canPerformGestures`, `canRetrieveWindowContent`, `flagDefault`). User must enable in Settings — accessibility is special-permission. |
| `MacroParser.kt` | Pure-Kotlin parser for `/macro <step1> \| <step2> \| …`. Sealed-class `MacroStep` with one variant per verb (`Tap`/`LongTap`/`Swipe`/`Key`/`Input`/`Find`/`Delay`/`ParseError`). Bad numeric arg or unknown verb produces a `ParseError` step rather than throwing; the runner aborts on the first error. Unit-tested in `MacroParserTest`. |
| `CmdMacro.kt` | `/macro <step1> \| <step2> \| …` — runs the parsed steps with full evidence: a `before` PNG (via `service.captureScreenshotPng`), a screen recording driven by `MediaProjection` + `MediaRecorder` writing H264 MP4 (720p, 4 Mbps, no audio), an `after` PNG, and a per-step `step N ok` / `step N (verb) failed: <reason>` summary. Aborts the rest of the macro on the first failed step. Reuses the same MediaProjection consent token A8sService caches for `/screenshot`. |
| `CmdTap.kt` / `CmdLongtap.kt` / `CmdSwipe.kt` / `CmdKey.kt` / `CmdInput.kt` / `CmdFind.kt` | Single-action UI-automation verbs. Each calls the matching `A11yService.instance` method, sleeps `POST_GESTURE_SETTLE_MS` for redraw, then sends a status message + post-action PNG via `UiActionReply.send`. If the accessibility service isn't enabled they reply with the `A11Y_DISABLED_MSG` constant; if MediaProjection consent isn't granted they still send the text reply but skip the screenshot. |
| `UiActionReply.kt` | Shared `Cmd*` helper: takes the gesture's text reply and a `kind` label, captures a post-action screenshot via `service.captureScreenshotPng`, and forwards through `service.replyToSender(... files = listOf(png))`. Constants `A11Y_DISABLED_MSG` and `POST_GESTURE_SETTLE_MS` live here. |
| `SecureConfigStore.kt` | Wraps `androidx.security.crypto.EncryptedSharedPreferences` (Keystore-backed AES-256-GCM). On every successful `loadConfig`, the parsed `remotes` JSON blob is mirrored into `secure_config.xml` so MQTT credentials at rest are ciphertext. Threat model: an attacker abusing `/cat` of our own data dir gets opaque bytes, not the broker password. |
| `RemoteConfig.kt` | One MQTT remote (transport, broker, topic, username, password). |
| `Network.kt` | Pure-Kotlin `parseRemotes` / `parseServices` — turns the JSON config into typed `Map<String, RemoteConfig>` + `List<StorageService>`. Accepts both new (`remotes` map) and legacy (singular `remote` block) shapes; rejects unknown spec keys to fail loud on typos. |
| `StorageService.kt` | Interface for cross-cluster file backends — `store(file): URL`, `retrieve(url, dest): Bool`. Stateless. |
| `TempFileOrgService.kt` | First (and currently only) `StorageService` impl. Pure-stdlib `HttpURLConnection` multipart upload + GET-`/download` retrieval. 50 MiB cap to stay well under the upstream's 100 MB hard limit. Per-service opts: `expiry_hours` (1/6/24/48, default 24), `timeout_s` (default 30). |

Tests under `app/src/test/java/com/a8s/android/` mirror the pure-Kotlin
files. Anything that touches Android framework classes lives in the
service layer and is exercised end-to-end on a real phone.

## Research / postmortem docs

| File | Topic |
|---|---|
| `A8S_CLUSTER_INTEGRATION.md` | How the upstream a8s daemon routes over MQTT vs this Android participant; sub-identity / SMS command design (#38) |
| `MQTT_COMMAND_DEDUP.md` | Duplicate `/send` from MQTT upstream retries (issue #36) — asymmetric Wi‑Fi, daemon vs Android mitigations |
| `INFO_FIELD_RESEARCH.md` | `/info` verbose field catalogue |
| `RCS_RESEARCH.md` | Third-party RCS access limits and workarounds |

## Wire format (the a8s envelope)

Every MQTT message — outbound *and* inbound — is JSON of this exact shape:

```json
{
  "id":      "<26-char Crockford-base32 ULID>",
  "date":    "2026-05-02T03:14:15Z",
  "from":    "<participant>",
  "to":      "<participant or alias>",
  "content": "<text>",
  "files":   []
}
```

With attachments, `files` is a non-empty array:

```json
"files": [
  { "filename": "photo.jpg", "storage": ["https://tempfile.org/abc/"] }
]
```

- `id` is **required**. The host's `network.py:316-318` drops envelopes
  with no/invalid ULID. Use `Ulid.new()`. (Bug history: legacy outbound
  used `body` instead of `content` and `to: "all"` — both broke routing
  on the host side. Fixed; do not re-introduce.)
- `from` is force-stamped by the host's a8s router on its own outbox
  pass (see `apps/a8s/mailbox.py:526` upstream). On our side, treat
  `from` as the **unforgeable identity** for authorization decisions.
- `files` is mandatory shape (empty array when no attachments). Outbound
  replies upload local files via `A8sService.buildFilesArray` (every
  configured storage service gets a try; all successful URLs land in
  `storage`). Inbound slash commands parse `files` into
  `MqttRoute.Command.files` for handlers like `/send`.

## Routing decision (decideRoute)

Pure function. Order of checks matters — early returns shape the
contract:

1. **Self-loopback.** `from == config.device` → `Drop`. Brokers echo our
   own publishes back to every subscriber on the topic; without this
   filter we'd re-route them as fresh commands.
2. **Missing `to`.** → `Drop`.
3. **`to != config.device`** → `Drop("not this device")`. The app only
   processes messages addressed directly to it.
4. **`to == config.device`** (this device is the recipient):
   - **Phonebook gate.** `from` must be a phonebook key. Otherwise the
     envelope drops — non-phonebook senders cannot reach the operator
     or run commands.
   - **Slash command.** If `content.startsWith("/")` →
     `Command(sender, verb, args, files)`. Verb is lowercased; empty verb
     (`"/   "`) drops. Envelope `files` are parsed and passed through.
   - **Not a command.** Else → `NotACommand(sender)`. Logged but not
     acted on — there is no implicit SMS forwarding.

## Configuration JSON (`a8s.json`)

```json
{
  "device":   "<this phone's participant name>",
  "phonebook": { "Clover": "+15550001111", "Gerry": "+15550002222" },
  "remotes": {
    "hivemq": {
      "transport": "mqtt",
      "broker":    "ssl://broker:8883",
      "topic":     "...",
      "username":  "...",
      "password":  "..."
    }
  },
  "services": {
    "tempfile": {
      "service": "tempfile_org",
      "url": "https://tempfile.org",
      "expiry_hours": 24,
      "timeout_s": 30
    }
  }
}
```

The pre-1.16.0 `forward` and `owner` keys are dropped on parse with a
startup warning. Phonebook membership is the single auth gate, and
`phonebook[from]` is the per-sender forward target. The MQTT
credentials are persisted encrypted-at-rest via `SecureConfigStore`.

**Multiple remotes** — `remotes` is a map; each entry gets its own
paho client, subscriber thread, and reconnect loop. Outbound publishes
fan out to every connected remote (the host cluster's a8s router
dedups by ULID, so this is idempotent). Inbound funnels into one
shared `handleMqttMessage` regardless of source. Failed per-remote
publishes land in `PublishRetryQueue` and flush on reconnect.

**Storage services** — `services` is a map keyed by local name, each
entry dispatched by `service` field. Currently only `tempfile_org` is
implemented. Active paths: outbound uploads (`/screenshot`, `/photo`,
`/video`, `/audio`, `/cat` attachments, incoming SMS/RCS/MMS media),
inbound retrieval (`/download`, `/mms`, `/dashboard bg`). `/screenshot`
requires at least one configured service.

**Backwards compatibility (1.9.0 → 1.10.0):** the parser also accepts
the legacy singular `"remote"` block (with `"url"` instead of
`"broker"`) and wraps it as `remotes: { "default": ... }`. Lets an
in-place `/update` not require the user to rewrite the config first.

- The user picks the file via Storage Access Framework; the URI is
  persisted (`takePersistableUriPermission`) so reloads work post-reboot.
  The **Permanently delete source file after loading** checkbox in
  `MainActivity` does a best-effort secure delete (zero-overwrite then
  SAF delete) of the picked file after the parse succeeds; per-launch
  state, default unchecked.
- `phonebook` plays two roles: (a) inbound reverse-lookup naming the
  publisher on SMS→MQTT; (b) auth gate — only phonebook senders can
  reach `device` and run commands.

## Permissions

| Permission | Why | Granted via |
|---|---|---|
| `SEND_SMS` / `RECEIVE_SMS` / `READ_SMS` | gateway IO | runtime prompt on launch |
| `READ_PHONE_STATE` | required for SMS APIs | runtime prompt |
| `READ_CONTACTS` | resolve RCS notification's contact display name → phone number for the phonebook lookup | runtime prompt |
| `POST_NOTIFICATIONS` | foreground service notification on API 33+ | runtime prompt (gated on `>= TIRAMISU`) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | RCS interception | special — Settings → Notification access (manifest declares it; the **Open Notification Access** button in MainActivity opens the page) |
| `BIND_ACCESSIBILITY_SERVICE` | UI automation (`/tap`, `/swipe`, `/macro`, …) | special — declared on the `<service>` element only (signature-protected, no `<uses-permission>`); user toggles it on in **Settings → Accessibility → Installed services → a8s Automation**. The **Enable Accessibility Service** button + the Grant-All flow both jump to that page. Android shows a recurring "has full access to your device" toast while it's on; not suppressible. |
| `REQUEST_INSTALL_PACKAGES` | `/update` command shows the system install dialog | manifest only — but user must enable **Settings → Apps → a8s Android → Install unknown apps → Allow from this source** once before the dialog will actually install. Without that toggle the prompt appears and is then blocked. |
| `CAMERA` | `/photo`, `/video` | runtime prompt (added in 1.12.0) |
| `RECORD_AUDIO` | `/video` audio track, `/audio` | runtime prompt |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | `/location` | runtime prompt |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_AUDIO` | scoped media access for `/ls` and `/cat` (API 33+) | runtime prompt |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE*` (incl. `_CAMERA`/`_MICROPHONE`/`_LOCATION`), `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | normal/special — implicit at install | n/a |

Runtime perms are requested as a single batch in `MainActivity.requestMissingPermissions()` (a `RequestMultiplePermissions` launcher). `onResume` re-checks both the dangerous perms and the notification-listener flag so returning from Settings updates the status panel.

## Persistence (24/7 operation)

The phone is meant to stay online with the screen off:
- Foreground service with `connectedDevice` type → OS won't kill it.
- `PARTIAL_WAKE_LOCK` → CPU stays awake for the MQTT TCP keepalive.
- `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) / `FULL_HIGH_PERF` fallback
  → wifi radio doesn't enter deep sleep.
- Battery optimization exemption requested at launch → Doze doesn't
  cut the network.
- `BootReceiver` re-launches on `BOOT_COMPLETED`.

What *will* break it: swiping the app out of recents on aggressive-battery
OEMs (Xiaomi, OnePlus, Samsung in some battery modes), revoked battery
exemption, or the user disabling notification access while RCS routing
is needed.

## Build & verification

```bash
# One-time toolchain (macOS)
brew install openjdk@17
brew install --cask android-commandlinetools
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# Pre-push gate (the same command the .githooks/pre-push hook runs):
./gradlew detekt test :app:compileDebugKotlin
# Or full build:
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

To wire the local pre-push gate so a broken branch can't even be pushed:
`git config core.hooksPath .githooks`.

## Signing & install

`app/debug.keystore` is **committed** (alias `androiddebugkey`, password
`android`, 10000-day validity). All builds — local and CI — sign with
this stable cert so APK upgrades don't trip Android's *"App not
installed as package conflicts with an existing package"*. The keystore
is **sideload-only**, never for Play Store distribution. Anyone with
the repo can sign as this cert; that's accepted for a one-person
internal tool. Don't generalize this pattern.

## CI / release / branch protection

`main` is protected. To merge:
- A PR is required.
- Two checks must pass: `verify` (`ci.yml` — detekt + tests + APK
  build) and `versionName-bumped` (`version-check.yml` — diffs
  `versionName` in `app/build.gradle.kts` against the PR base; fails
  if unchanged).
- Linear history (squash or rebase merge only). No force-pushes, no
  branch deletion.

On push to `main`, `release.yml` reads the new `versionName`, tags
`v<version>`, builds a debug APK, attaches it to a GitHub Release as
`a8s-android-<version>-debug.apk` with auto-generated notes from the
merged PRs. Skips if the tag already exists (e.g. someone bypassed the
PR check via direct push).

**Always bump versionName** in `app/build.gradle.kts` when opening a
PR. Skip past a reserved version if another PR is open (e.g. PR A took
1.7.0 → PR B picks 1.8.0).

## Workflow / git hygiene

- Branches: short-lived, off `main`. Conventional commit prefixes
  (`feat:`, `fix:`, `chore:`, `ci:`).
- **Don't stack PRs against non-main branches.** Squash-merging the
  parent leaves the child orphaned on a stale branch with the merge
  button still active. Always `gh pr create --base main`. (Bug history:
  PR #2 was lost this way; recovered as PR #3.)
- Squash-merge is the norm here. After merging, sync `main` and delete
  the local branch. Subsequent PRs rebase onto fresh `main`.

## Known testing patterns

- **Pure-Kotlin first.** Pull decision logic into a top-level function
  returning a sealed-class result (`decideRoute`, `decideCommand`).
  Test the function with `JUnit 5`. Don't try to mock Android framework
  classes — instead, take a snapshot data class as input.
- **`InfoSnapshot` pattern.** Device info gathering lives in
  `InfoSnapshotter.capture`; pure formatting in `Commands.renderInfo`.
  Tests construct the snapshot directly (`CommandsRenderInfoTest`).
- **Detekt is opinionated.** `detekt.yml` at repo root tightens
  `LongMethod` (80), `CyclomaticComplexMethod` (20), `MaxLineLength`
  (140); disables `NestedBlockDepth` and `LoopWithTooManyJumpStatements`
  for the Android `BroadcastReceiver` and SDK-permission flow shapes.
  If a long-line warning fires, prefer breaking the string with
  concatenation across lines rather than disabling the rule.

## Common pitfalls

- **Don't log secrets.** Detekt won't catch this; the in-app log ring
  is shown on screen and surfaced via `/logs`.
- **Don't use `body` on the wire.** It's `content`. Regression test
  exists: `MqttRouteTest::content field is read instead of body`.
- **Don't publish with `to: "all"`.** That was the legacy outbound
  shape; it doesn't resolve on the host unless an alias literally
  named `all` exists. Use the matched phonebook participant name.
- **Don't bypass `PublishDedup`.** Google Messages re-posts
  notifications on thread updates; without dedup the cluster sees N
  copies of the same SMS reply.
- **Don't bypass `CommandDedup` for outbound SMS verbs.** `/send`,
  `/reply`, and `/mms` must pass through `CommandDispatch` /
  `gateInboundSmsCommand` before queuing SMS — upstream MQTT retries with
  fresh ULIDs will otherwise deliver duplicate texts.
- **Don't trust the `from` without checking the phonebook.** Phonebook
  membership is the single auth gate for self-addressed envelopes.
  Non-phonebook senders are dropped before any command processing.
- **Don't add a fresh debug keystore.** Let the committed
  `app/debug.keystore` sign every build, or in-place upgrades break.
- **Don't assume MQTT publish succeeded.** `publishToAllRemotes` queues
  to `PublishRetryQueue` on disconnect; check logs for
  `publish queued` / `discarding after N attempts`.
- **`/screenshot` needs storage + projection.** Missing either fails
  with an explicit reply — not a silent no-op.

## Useful adb commands

```bash
adb logcat -s A8sAndroid:I A8sService:I    # filtered logs
adb install -r app/build/outputs/apk/debug/app-debug.apk    # in-place upgrade
adb shell am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS
adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
```

## Issue / PR style

PR descriptions follow this shape (see merged PRs for examples):
- One-paragraph **Summary** of *what* and *why*.
- A bug? Include the failing log line / screenshot.
- Always a **Version bump** line.
- A **Test plan** with checkboxes — what was verified locally, what
  needs hands-on phone testing post-merge.

When you fix something the user just reported, lead the PR title with
`fix:` and quote the exact symptom they reported in the body. It
makes the PR self-documenting and easy to grep later.
