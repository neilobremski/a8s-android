# a8s-android

A high-reliability messaging bridge for the [a8s (Agent Infinity System)](https://github.com/neilobremski/bin/blob/main/apps/a8s/README.md). It turns an Android phone into a messaging gateway agent that can send and receive SMS/RCS messages via MQTT.

## Features

- **a8s Integration:** Operates as a remote node in an a8s cluster.
- **Command-driven model:** Phonebook participants issue `/command` messages to the device; commands execute locally and reply over MQTT.
- **Explicit SMS sending:** `/send`, `/mms`, `/reply` — no implicit forwarding.
- **SMS/RCS to MQTT:** Incoming SMS and intercepted RCS notifications publish back to the cluster.
- **Media receive:** Extracts images/video from RCS notifications (3 strategies) and MMS via ContentObserver.
- **RCS reply actions:** Caches notification reply intents for `/reply` — enables RCS-capable responses without being the default SMS app.
- **Persistent Connectivity:** Uses foreground services, wake locks, and wifi locks to stay online 24/7.
- **Auto-update:** Checks GitHub Releases every 6 hours, auto-downloads newer APKs, prompts to install.
- **Tabbed UI:** Dashboard (WebView), Logs, and Setup tabs. Dashboard content and background are remotely controllable via `/dashboard`.
- **Interactive Configuration:** Simple UI to load and update `a8s.json` configuration via Android Storage Access Framework.

## Configuration (a8s.json)

The app is configured via a JSON file with the following schema:

```json
{
  "device": "android-pixel-7",
  "phonebook": {
    "Neil": "+15550009999",
    "Clover": "+15550001111",
    "Gerry": "+15550002222"
  },
  "remotes": {
    "hivemq": {
      "transport": "mqtt",
      "broker": "ssl://your-broker.hivemq.cloud:8883",
      "topic": "a8s/messaging",
      "username": "your-user",
      "password": "your-password"
    }
  },
  "services": {
    "tempfile": {
      "service": "tempfile_org",
      "url": "https://tempfile.org"
    }
  }
}
```

The legacy `1.9.0` shape (a flat `remote` object instead of `remotes`)
still parses — it's wrapped as `remotes: { "default": ... }` so an
in-place upgrade doesn't break. The pre-1.16.0 `forward` and `owner`
keys are ignored (with a startup warning) — the phonebook is now the
single auth gate.

- **`device`** — the participant name this phone identifies as on the a8s
  cluster. When other agents `tell <device> "..."`, the message arrives at
  this phone.
- **`phonebook`** — `name → phone-number` map. The single auth gate:
  - **Slash commands**: a phonebook sender whose `content` starts with
    `/` runs the command on-device; the reply is `tell`'d back to that
    sender. There is no separate "owner" — phonebook membership *is* the
    privilege. Non-phonebook senders are dropped.
  - **Inbound SMS**: SMS from a known number publishes back to MQTT as
    that name.
  - **Non-command content**: messages to this device from a phonebook
    sender that don't start with `/` produce a `NotACommand` route
    (logged, not forwarded). There is no implicit SMS forwarding.
- **`remotes`** — map of MQTT brokers this device subscribes/publishes to.
  Each entry is keyed by an arbitrary local name and contains
  `transport` (default `"mqtt"`), `broker`, `topic`, optional
  `username`/`password` (also accepts `user`/`pass` aliases). Outbound
  publishes fan out to every connected remote; inbound funnels through
  one shared handler regardless of which remote delivered it. The host
  cluster's a8s router dedups by ULID, so multi-remote delivery is
  idempotent.
- **`services`** *(optional)* — map of cross-cluster file-storage
  backends. Each entry has a `service` kind and a `url`. Currently the
  only supported kind is `tempfile_org` (https://tempfile.org;
  pure-stdlib HTTP). Required when an envelope's `files[i].storage`
  URLs need to be downloaded for inbound or uploaded for outbound.
  Per-service options: `expiry_hours` (1, 6, 24, 48; default 24) and
  `timeout_s` (default 30).

### Slash commands (phonebook-only)

Any phonebook participant can send `tell <device> "/<command> [args]"`
and the device executes it locally. Responses come back as
`tell <sender> "..."` over MQTT. Senders not in the phonebook are dropped.

| Command | Description |
|---|---|
| `/info` | App version, device model, Android release, MQTT state, network, battery, memory, storage, display, power, permissions, services, uptime, config. Add `verbose` (or `-v` / `--verbose`) for the full ~150-field dump (identifiers, Wi-Fi SSID/BSSID, IPs, sensors, last-known location, camera details, etc.). See `INFO_FIELD_RESEARCH.md` for the full field catalogue. |
| `/logs [N]` | Last `N` lines of the in-app log buffer (default 50, max 500). |
| `/send <number> <message>` | Send an SMS to an explicit phone number. |
| `/mms <number> <url>` | Download media from `url`; send the URL as text SMS to `number` (true MMS requires default-SMS-app role). |
| `/reply <number> <text>` | Fire the cached notification reply action for `number` (RCS-capable). Lists cached numbers if none match. |
| `/download <url> [filename]` | Download a file to `/sdcard/Download`. Tries configured storage services first, falls back to raw HTTP. |
| `/dashboard bg <url>` | Download an image and set it as the Dashboard tab background. |
| `/dashboard content <html>` | Set arbitrary HTML as Dashboard tab content. |
| `/dashboard clear` | Remove dashboard background and content. |
| `/update` | Fetch the latest GitHub Release APK and trigger the system install dialog. |
| `/update --check` | Compare the installed version to the latest release without downloading. |
| `/update <url>` | Download + install a specific APK URL (bypasses the version comparison). |
| `/screenshot` | Capture the phone's screen, upload via the first configured storage service, reply with `files: [{storage: [url]}]`. Requires one-time consent via the **Enable Screen Capture** button + a configured storage service. Consent is held in-memory only — after an in-place upgrade or process restart, re-grant via the **Grant All Permissions** button. |
| `/photo [front\|back]` | Take a JPEG via Camera2 (defaults to back). Reply attaches the file. |
| `/video [seconds]` | Record an MP4 (default 10s, max 30s) via Camera2 + MediaRecorder. Reply attaches the file. |
| `/audio [seconds]` | Record audio-only M4A via MediaRecorder MIC source (default 10s, max 60s). Reply attaches the file. |
| `/location` | One-shot location fix (FusedLocationProvider if available, else `LocationManager` GPS+Network). 30s timeout. Reply: `lat=… lng=… accuracy=…m age=… provider=…`. |
| `/say <text>` | Speak text aloud through the phone speaker via Android TTS. Reply: `Spoke: <text>` after playback finishes. |
| `/notify <title>\|<body>` | Post a system notification on the phone. Pipe-separated; missing pipe puts the whole input in the body and titles it `a8s`. |
| `/ls [<path>]` | List directory entries (default `/sdcard/Download`). Plain-text listing with type, size, mtime, name. |
| `/cat <path>` | Read a file. Text files <= 10 KB are returned inline; everything else is sent as an attachment via the configured storage service. |
| `/rm <path>` | Delete a file or empty directory (refuses to recurse into non-empty dirs). |
| `/tap x y` | Synthesize a tap at screen-pixel coordinates via the accessibility service. Reply attaches a post-tap screenshot. |
| `/longtap x y [ms]` | Synthesize a long-press (default 800ms). Reply attaches a post-press screenshot. |
| `/swipe x1 y1 x2 y2 [ms]` | Synthesize a straight-line swipe (default 300ms). Reply attaches a post-swipe screenshot. |
| `/key <NAME>` | Perform a global accessibility action: `BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `LOCK_SCREEN` (case-insensitive). Reply attaches a screenshot. |
| `/input <text>` | Type the rest of the line into the currently-focused input field via `ACTION_SET_TEXT`. Reports failure if no field is focused. |
| `/find <label>` | Walk the active window's accessibility tree, find a node whose `text` or `contentDescription` contains `label` (case-insensitive), and click it. Reply attaches a screenshot. |
| `/macro step1 \| step2 \| …` | Run a sequence of UI-automation steps with full evidence: a `before` screenshot, a screen recording of the run, an `after` screenshot, and a per-step status summary. Step verbs: `tap x y`, `longtap x y [ms]`, `swipe x1 y1 x2 y2 [ms]`, `key NAME`, `input <text>`, `find <label>`, `delay ms`. Pipes inside `input` text are not escapable — use `delay`-bracketed segments to keep the text on its own. |
| `/<unknown>` | Replies with the list of known commands. |

UI-automation commands (`/tap`, `/longtap`, `/swipe`, `/key`, `/input`,
`/find`, `/macro`) require the **a8s Automation** accessibility service
to be enabled in Settings. Accessibility access is a special permission
that can't be granted via the runtime dialog — you must toggle it in
**Settings → Accessibility → Installed services → a8s Automation**.
The **Enable Accessibility Service** button in the app jumps directly
to that page; the **Grant All Permissions** flow also opens it if our
service isn't enabled yet. Once enabled, Android shows the periodic
"a8s Automation has full access to your device" toast — that's the
contract for accessibility services with full window content + gesture
dispatch and there's no way to suppress it. The `/macro` command also
needs Screen Capture consent (same one used by `/screenshot`) for the
recording + before/after screenshots.

Stock Android can't silently install APKs without device-owner setup, so
`/update` shows the system's install confirmation on the phone screen
and the operator taps **Install** once. Subsequent upgrades are in-place
because every build signs with the committed `app/debug.keystore`.
One-time setup: in **Settings → Apps → a8s Android → Install unknown
apps**, toggle **Allow from this source** on. Without it, the install
dialog shows but the install is blocked.

Phonebook membership is the only auth gate. A phonebook sender whose
content starts with `/` runs a slash command; non-command content from
a phonebook sender produces a `NotACommand` result (logged, not
forwarded). Non-phonebook senders are dropped before any of this.

## Media receive

Incoming media is captured through two complementary paths:

- **RCS (notification listener):** `SmsNotificationListener` intercepts
  Google Messages notifications. `MediaExtractor` tries three strategies
  in priority order:
  1. `MessagingStyle.dataUri` — content URI from the structured notification.
  2. `BigPictureStyle` / `EXTRA_PICTURE_ICON` — bitmap from expanded notification.
  3. `EXTRA_LARGE_ICON_BIG` — fallback for large inline thumbnails (> 128px).

  Extracted files are uploaded via the configured storage service and
  attached to the outbound MQTT envelope.

- **MMS (ContentObserver):** `MmsObserver` watches `content://mms` for
  new inbox messages. After a 2-second settle delay it queries the MMS
  parts table, extracts image/video/audio parts, and publishes them
  the same way. This catches media that arrives as true MMS rather
  than RCS.

Reply actions from RCS notifications are cached per phone number so
`/reply` can fire them later without being the default SMS app.

## Auto-update

`A8sService` schedules a periodic check (every 6 hours, first check
60 seconds after boot) against the GitHub Releases API. If a newer
`versionName` is found:

1. The APK asset is downloaded to the cache directory.
2. A system install prompt is shown on screen (requires the
   "Install unknown apps" permission toggle).
3. The operator taps **Install** once.

Manual trigger: `/update` (download + prompt), `/update --check`
(compare only), `/update <url>` (arbitrary APK URL).

## Tabbed UI

`MainActivity` presents three tabs:

| Tab | Content |
|---|---|
| **Dashboard** | A full-screen `WebView` showing remotely-set HTML content and/or a background image. Controlled via `/dashboard bg <url>`, `/dashboard content <html>`, `/dashboard clear`. State is persisted across restarts (`Dashboard.kt`). |
| **Logs** | Live scrolling view of the in-app log ring (same buffer served by `/logs`). |
| **Setup** | Configuration loading, permission grants, status panel — the original single-screen UI. |

## Persistence — does it keep running with the screen off?

Yes. The app is designed to stay online 24/7. Specifically:

- **Foreground service.** `A8sService` calls `startForeground` in `onCreate`
  and declares `foregroundServiceType="connectedDevice"` in the manifest,
  which means Android won't kill it when the screen turns off. The
  persistent notification you see in the status bar is the contract: as
  long as it's showing, the OS leaves the service alone.
- **Partial wake lock** (`PowerManager.PARTIAL_WAKE_LOCK`, tag `a8s:mqtt`)
  keeps the CPU on so the MQTT client can keep its TCP connection alive.
- **Wifi lock** (`WIFI_MODE_FULL_LOW_LATENCY` on API 29+, falling back to
  `WIFI_MODE_FULL_HIGH_PERF` on older) keeps the wifi radio out of deep
  sleep.
- **Battery-optimization exemption.** Requested on first launch via
  `requestBatteryOptimizationExclusion()`. Without it, Doze mode would
  suspend the network and the MQTT subscription would silently miss
  messages — grant it when prompted.
- **Boot recovery.** `BootReceiver` re-launches the service on
  `BOOT_COMPLETED`, so a reboot doesn't drop the bridge offline.

What *will* stop the service: swiping the app out of recents on
aggressive-battery OEMs (Xiaomi, OnePlus, Samsung in some modes), or
denying the battery-optimization exemption. If messages stop arriving
without an obvious cause, those are the first two things to check.

## Setup

1. **Install:** Download the latest APK from the
   [Releases page](https://github.com/neilobremski/a8s-android/releases/latest)
   (filename: `a8s-android-<version>-debug.apk`). Sideload via
   `adb install` or copy to the device and tap to install.
2. **Open the app and grant permissions:**
   - On first launch, the app prompts for SMS (send/receive/read), phone
     state, and notifications. Grant all — denying any disables the
     corresponding direction of traffic.
   - Tap **Open Notification Access (for RCS)** to enable Google Messages
     interception in `Settings > Notifications > Notification access`.
     This is a special permission that can't be requested via the
     standard runtime dialog.
   - **Battery Optimization** exemption is requested automatically.
3. **Configure:** Use the **Load Configuration JSON** button to select
   your `a8s.json` file. The status panel shows what's granted, what's
   missing, and the device's a8s identity once configured. Tick
   **Permanently delete source file after loading** to overwrite the
   picked file with zeros and remove it via the SAF provider after a
   successful load — best-effort secure delete (works on the local
   Files-app provider; cloud providers like Google Drive may ignore
   the zero-overwrite). MQTT credentials in the loaded config are
   stored encrypted-at-rest via Keystore-backed
   `EncryptedSharedPreferences`.

## Development

Built with Kotlin and Android SDK. 

### Build locally
```bash
./gradlew assembleDebug
```

### Pre-push verification

Before pushing, run static analysis + unit tests + a Kotlin compile:

```bash
./gradlew detekt test :app:compileDebugKotlin
```

To wire this into git so a broken branch can't be pushed:

```bash
git config core.hooksPath .githooks
```

The hook in `.githooks/pre-push` runs the same Gradle command and blocks the
push on failure.

### One-time toolchain setup (macOS)

```bash
brew install openjdk@17                                   # JDK
brew install --cask android-commandlinetools              # SDK platform-tools / build-tools
sdkmanager --licenses                                     # accept all
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

Add the two `export` lines to your shell rc so future sessions inherit them.

### Tests

Unit tests live in `app/src/test/java/...` and run on the host JVM (no
emulator needed). Pure Kotlin only — anything that touches the Android
framework (Context, Intent, NotificationListener, MQTT client) stays in
the production code paths and is exercised end-to-end via the live device.

The pattern: extract pure decision logic into top-level functions like
`decideRoute(payload, config)` and unit-test those; let the Android-side
`A8sService` do nothing but wire IO to those decisions. See
`MqttRoute.kt` + `MqttRouteTest.kt` for the template.

### Linting

[Detekt](https://detekt.dev/) runs against the app sources. Config lives
at `detekt.yml` in the repo root — reasonably loose defaults, tightens
where the team has signal (e.g. `LongMethod`, `MaxLineLength`). Adjust by
editing that file rather than disabling rules at call sites.

### Versioning and releases

`main` is protected. Every PR that merges into `main` must bump
`versionName` (and ideally `versionCode`) in `app/build.gradle.kts`; the
**Version bump check** workflow blocks the PR otherwise.

When a bump merges, the **Release** workflow tags `v<versionName>`,
builds a debug APK, and publishes a GitHub Release with the APK
attached as `a8s-android-<version>-debug.apk`. If a tag for the current
versionName already exists (e.g. force-push that didn't bump), the
workflow no-ops rather than failing the run.

### Signing key (debug, stable)

Debug APKs are signed with a single keystore committed at
`app/debug.keystore` (alias `androiddebugkey`, password `android`). This
exists so consecutive builds on different machines and CI runners produce
APKs signed with the *same* cert — without it, every release has a fresh
auto-generated debug cert and Android refuses upgrade-in-place with
*"App not installed as package conflicts with an existing package."*

This keystore is **not** for Play Store distribution; it's a sideload-only
debug key. Anyone with the repo can sign as this cert. That's acceptable
for a one-person internal-use app; do not adopt this pattern for anything
public-facing.

After switching to this stable key for the first time, you'll need to
uninstall any previously-installed version once — the prior APK is signed
with a different (per-runner) cert and Android can't replace it with the
new one. Subsequent upgrades just work.
