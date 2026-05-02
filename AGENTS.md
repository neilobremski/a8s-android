# a8s-android — agent onboarding

This file is the dense, accumulated knowledge a fresh agent (human or LLM)
needs to be productive in this repo without re-deriving everything from
the source tree. Keep it terse. Update when you learn something a future
contributor would want to know — especially gotchas that cost you time.

## What this is

An Android app that bridges the [a8s (Agent Infinity System)](https://github.com/neilobremski/bin/tree/main/apps/a8s)
cluster to a phone. Acts as a participant on the MQTT topic and:

- **MQTT → SMS gateway** — `tell <name> "..."` from any cluster agent
  becomes an SMS to whatever number that name maps to in the local
  phonebook. The phone is invisible to senders; opacity per a8s design.
- **SMS/RCS → MQTT** — incoming SMS or intercepted Google Messages RCS
  notifications publish back to the cluster as if they came from the
  matched phonebook participant.
- **Self-receive (forward)** — `tell <device-name>` SMSs the operator at
  a configured `forward` number, prefixed with the sender name.
- **Owner /commands** — a single trusted `owner` participant can issue
  `/info`, `/logs [N]`, etc. and get a `tell`'d response back.

## Module map (under `app/src/main/java/com/a8s/android/`)

| File | Role |
|---|---|
| `A8sAndroid.kt` | `Application` subclass. Owns the static `Config` (parsed from JSON), the in-app log ring (50 lines, surfaced to the UI via `onLogListener`), and `loadConfig`/`saveUri`/`getSavedUri` for SAF persistence. Triggers `requestBatteryOptimizationExclusion` on first launch. |
| `A8sService.kt` | The brains. Foreground `LifecycleService` (type `connectedDevice`). Holds the MQTT client (Paho v3 standalone JAR), the wake/wifi locks, the `PublishDedup` cache, and the SMS-sent broadcast receiver. Routes inbound MQTT via `decideRoute` and acts on the result. |
| `MainActivity.kt` | Configuration / status UI. Requests dangerous perms via `RequestMultiplePermissions`. Buttons: **Load Configuration JSON** (SAF), **Open Notification Access (for RCS)**. Shows installed version + grant state in the status panel. |
| `SmsReceiver.kt` | `BroadcastReceiver` for `SMS_RECEIVED_ACTION`. Uses `Telephony.Sms.Intents.getMessagesFromIntent` (modern API; older PDU-extraction path was removed). Forwards to `A8sService.publishIncoming`. |
| `SmsNotificationListener.kt` | `NotificationListenerService` for `com.google.android.apps.messaging`. Pulls `EXTRA_TITLE` (contact display name) + `EXTRA_TEXT`. Forwards to `A8sService.publishIncoming`. |
| `BootReceiver.kt` | Re-launches `A8sService` on `BOOT_COMPLETED`. |
| `MqttRoute.kt` | Sealed class + pure-Kotlin `decideRoute(payload, config)`. Variants: `Forward` / `Phonebook` / `Command(owner, name, args)` / `Drop(reason)` / `ParseError(reason)`. **All routing logic lives here so it's unit-testable.** Service layer just dispatches. |
| `Commands.kt` | Pure formatters for slash-command output. `InfoSnapshot` data class is filled in by `A8sService.snapshotInfo()` and passed to `renderInfo`. `renderLogs(logs, n)` does tail+header. Keeps Android-specific `Build`/`BatteryManager`/`ConnectivityManager` calls out of the formatter so it tests without a Context. |
| `PublishDedup.kt` | Bounded LRU keyed on `<recipient>\|<body>`, default 5-minute window / 100 entries. Stops the duplicate-publish bug from Google Messages re-posting notifications. |
| `Ulid.kt` | Crockford-base32 ULID generator matching Python `apps/a8s/ulid.py`. Pure stdlib (`SecureRandom` + `BigInteger`). Required for `id` field on every outbound MQTT envelope so the host's `_process_pending` dedup ring accepts it. |
| `Updater.kt` | `/update` plumbing — fetches GitHub Releases JSON, picks the `a8s-android-*-debug.apk` asset, downloads it, and `compareVersions` to decide if newer. The actual install kicks off via `ACTION_VIEW` + FileProvider in `A8sService.triggerInstallPrompt`. JSON parsing + version compare are unit-tested; HTTP and FileProvider sit in thin Android-only wrappers. |
| `Screenshot.kt` | `/screenshot` plumbing — `MediaProjection` + `ImageReader` + `VirtualDisplay` to grab one frame, write as PNG. The user grants projection consent once via the **Enable Screen Capture** button in MainActivity; the `(resultCode, Intent)` pair is held in the service for the lifetime of the process. Each `/screenshot` builds a fresh `MediaProjection` from the cached consent, captures + releases to keep the OS's media-projection notification quiet between shots. **Consent is in-memory only and lost on process restart** (e.g. after `/update` reinstalls the app). The "Grant All Permissions" button in MainActivity re-fires the projection-consent dialog so it's bundled into one tap with the other dangerous-perm grants. |
| `CmdHelpers.kt` | Pure-Kotlin parsing + formatters for `/photo`, `/video`, `/location`, `/say`, `/notify`, `/ls`, `/cat`. `KNOWN_COMMANDS` is the single source of truth for the `unknown command` listing. Unit-tested in `CmdHelpersTest`. |
| `CmdPhoto.kt` | `/photo [front\|back]` — Camera2 still capture on a per-call HandlerThread. Saves JPEG to `cacheDir/photos/photo-<ts>.jpg`, replies via `replyToOwner(... files=...)`. |
| `CmdVideo.kt` | `/video [seconds]` — Camera2 + MediaRecorder MP4 capture. Default 10s, hard-capped at 30s. Saves to `cacheDir/videos/`. Audio source = camcorder; H264/AAC at 720p/4Mbps. |
| `CmdLocation.kt` | `/location` — best-effort one-shot fix. Tries FusedLocationProviderClient via reflection (so we don't pull in `play-services-location`); falls back to `LocationManager` GPS + NETWORK with a 30s timeout. |
| `CmdSay.kt` | `/say <text>` — TextToSpeech lifecycle (init → speak → wait via `UtteranceProgressListener` → shutdown). Blocks the worker thread until playback completes so the reply (`Spoke: …`) reflects completion. |
| `CmdNotify.kt` | `/notify <title>\|<body>` — posts a NotificationCompat.BigTextStyle notification. IDs auto-increment so notifications stack rather than replace. |
| `CmdLs.kt` | `/ls [<path>]` — directory listing rendered by `CmdHelpers.renderLs`. Default path is `/sdcard/Download`. |
| `CmdCat.kt` | `/cat <path>` — reads file. Text files <= 10 KB return inline; binary or larger files attach via `replyToOwner(files=...)` (uses the configured storage service path). |
| `CmdRm.kt` | `/rm <path>` — deletes a file or empty directory. Refuses to recurse into non-empty dirs. |
| `A11yService.kt` | `AccessibilityService` for synthetic gestures (`tap`, `longTap`, `swipe`), global actions (`globalAction("BACK"/"HOME"/...)`), focused-field text entry (`inputText`), and node-walk-and-click (`findAndTap`). Companion `instance` exposes the live binding to the `Cmd<Verb>.kt` handlers. Each gesture method blocks on a `CountDownLatch` waiting for `GestureResultCallback` so the worker thread can sequence multiple gestures with `Thread.sleep` between. Configured via `res/xml/accessibility_service_config.xml` (`canPerformGestures`, `canRetrieveWindowContent`, `flagDefault`). User must enable in Settings — accessibility is special-permission. |
| `MacroParser.kt` | Pure-Kotlin parser for `/macro <step1> \| <step2> \| …`. Sealed-class `MacroStep` with one variant per verb (`Tap`/`LongTap`/`Swipe`/`Key`/`Input`/`Find`/`Delay`/`ParseError`). Bad numeric arg or unknown verb produces a `ParseError` step rather than throwing; the runner aborts on the first error. Unit-tested in `MacroParserTest`. |
| `CmdMacro.kt` | `/macro <step1> \| <step2> \| …` — runs the parsed steps with full evidence: a `before` PNG (via `service.captureScreenshotPng`), a screen recording driven by `MediaProjection` + `MediaRecorder` writing H264 MP4 (720p, 4 Mbps, no audio), an `after` PNG, and a per-step `step N ok` / `step N (verb) failed: <reason>` summary. Aborts the rest of the macro on the first failed step. Reuses the same MediaProjection consent token A8sService caches for `/screenshot`. |
| `CmdTap.kt` / `CmdLongtap.kt` / `CmdSwipe.kt` / `CmdKey.kt` / `CmdInput.kt` / `CmdFind.kt` | Single-action UI-automation verbs. Each calls the matching `A11yService.instance` method, sleeps `POST_GESTURE_SETTLE_MS` for redraw, then sends a status message + post-action PNG via `UiActionReply.send`. If the accessibility service isn't enabled they reply with the `A11Y_DISABLED_MSG` constant; if MediaProjection consent isn't granted they still send the text reply but skip the screenshot. |
| `UiActionReply.kt` | Shared `Cmd*` helper: takes the gesture's text reply and a `kind` label, captures a post-action screenshot via `service.captureScreenshotPng`, and forwards through `service.replyToOwner(... files = listOf(png))`. Constants `A11Y_DISABLED_MSG` and `POST_GESTURE_SETTLE_MS` live here. |
| `RemoteConfig.kt` | One MQTT remote (transport, broker, topic, username, password). |
| `Network.kt` | Pure-Kotlin `parseRemotes` / `parseServices` — turns the JSON config into typed `Map<String, RemoteConfig>` + `List<StorageService>`. Accepts both new (`remotes` map) and legacy (singular `remote` block) shapes; rejects unknown spec keys to fail loud on typos. |
| `StorageService.kt` | Interface for cross-cluster file backends — `store(file): URL`, `retrieve(url, dest): Bool`. Stateless. |
| `TempFileOrgService.kt` | First (and currently only) `StorageService` impl. Pure-stdlib `HttpURLConnection` multipart upload + GET-`/download` retrieval. 50 MiB cap to stay well under the upstream's 100 MB hard limit. |

Tests under `app/src/test/java/com/a8s/android/` mirror the pure-Kotlin
files. Anything that touches Android framework classes lives in the
service layer and is exercised end-to-end on a real phone.

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

- `id` is **required**. The host's `network.py:316-318` drops envelopes
  with no/invalid ULID. Use `Ulid.new()`. (Bug history: legacy outbound
  used `body` instead of `content` and `to: "all"` — both broke routing
  on the host side. Fixed; do not re-introduce.)
- `from` is force-stamped by the host's a8s router on its own outbox
  pass (see `apps/a8s/mailbox.py:526` upstream). On our side, treat
  `from` as the **unforgeable identity** for authorization decisions.
- `files: []` is mandatory shape; we don't currently send attachments.

## Routing decision (decideRoute)

Pure function. Order of checks matters — early returns shape the
contract:

1. **Self-loopback.** `from == config.device` → `Drop`. Brokers echo our
   own publishes back to every subscriber on the topic; without this
   filter we'd try to re-route them as fresh SMS, and (when `to`
   matches a phonebook entry) infinite-loop.
2. **Missing `to`.** → `Drop`.
3. **`to == config.device`** (this device is the recipient):
   - **Owner command.** If `owner` is set AND `from == owner` AND
     `content.startsWith("/")` → `Command(owner, verb, args)`. Verb is
     lowercased; empty verb (`"/   "`) drops. Bypasses phonebook gate
     because owner *is* the gate.
   - **Forward.** Else, `forward` must be set, and `from` must be a
     phonebook key (sender verification — only known cluster
     participants can SMS the operator). SMS body is `"<from>: <content>"`.
4. **`to` in phonebook** → `Phonebook(name, number, content)`, SMSed to
   that number with the bare content (no `from:` prefix; the phonebook
   target is a stranger, the sender's identity isn't theirs to see).
5. **Else** → `Drop("not in phonebook and not this device")`.

## Configuration JSON (`a8s.json`)

```json
{
  "device":   "<this phone's participant name>",
  "forward":  "<optional: number for messages addressed to device>",
  "owner":    "<optional: the only name allowed to run /commands>",
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
    "tempfile": { "service": "tempfile_org", "url": "https://tempfile.org" }
  }
}
```

**Multiple remotes** — `remotes` is a map; each entry gets its own
paho client, subscriber thread, and reconnect loop. Outbound publishes
fan out to every connected remote (the host cluster's a8s router
dedups by ULID, so this is idempotent). Inbound funnels into one
shared `handleMqttMessage` regardless of source.

**Storage services** — `services` is a map keyed by local name, each
entry dispatched by `service` field. Currently only `tempfile_org` is
implemented. The actual upload/download wiring to the message-routing
flows is plumbed but not yet consumed (no MMS attachment path lands
file payloads on this side); the interface and config parsing are in
place so future PRs that add MMS or APK-share flows can pick them up.

**Backwards compatibility (1.9.0 → 1.10.0):** the parser also accepts
the legacy singular `"remote"` block (with `"url"` instead of
`"broker"`) and wraps it as `remotes: { "default": ... }`. Lets an
in-place `/update` not require the user to rewrite the config first.

- The user picks the file via Storage Access Framework; the URI is
  persisted (`takePersistableUriPermission`) so reloads work post-reboot.
- `phonebook` is the union of (a) outbound: `name → number` for SMSing
  cluster sends; (b) inbound: reverse-lookup for naming the publisher
  on SMS→MQTT.
- `forward` is typically the operator's own number. Without it,
  self-addressed messages drop.
- `owner` defaults to none — without it, slash-prefixed content from
  anyone falls through to the regular forward path (which still gates
  on phonebook membership).

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
| `RECORD_AUDIO` | `/video` audio track (and future mic capture) | runtime prompt |
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
- **`InfoSnapshot` pattern.** When you need device info in a pure
  formatter (`Commands.renderInfo`), define a data class with all the
  fields, gather them in the service layer, pass them in. Tests
  construct the snapshot directly.
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
- **Don't trust the `from` for forwarding without checking the
  phonebook.** That's what the sender-verification gate is for. Owner
  is the *only* identity allowed to skip it (and only for
  slash-commands).
- **Don't add a fresh debug keystore.** Let the committed
  `app/debug.keystore` sign every build, or in-place upgrades break.

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
