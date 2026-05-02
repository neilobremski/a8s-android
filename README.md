# a8s-android

A high-reliability messaging bridge for the [a8s (Agent Infinity System)](https://github.com/neilobremski/bin/blob/main/apps/a8s/README.md). It turns an Android phone into a messaging gateway agent that can send and receive SMS/RCS messages via MQTT.

## Features

- **a8s Integration:** Operates as a remote node in an a8s cluster.
- **Bi-directional Messaging:**
    - **MQTT to SMS:** Translates `tell` messages into physical SMS.
    - **SMS to MQTT:** Forwards incoming SMS/RCS to the a8s cluster.
- **RCS Support:** Intercepts notifications from Google Messages to support RCS.
- **Persistent Connectivity:** Uses foreground services, wake locks, and wifi locks to stay online 24/7.
- **Interactive Configuration:** Simple UI to load and update `a8s.json` configuration via Android Storage Access Framework.

## Configuration (a8s.json)

The app is configured via a JSON file with the following schema:

```json
{
  "device": "android-pixel-7",
  "forward": "+15550009999",
  "phonebook": {
    "Clover": "+15550001111",
    "Gerry": "+15550002222"
  },
  "remote": {
    "url": "ssl://your-broker.hivemq.cloud:8883",
    "topic": "a8s/messaging",
    "username": "your-user",
    "password": "your-password"
  }
}
```

- **`device`** — the participant name this phone identifies as on the a8s
  cluster. When other agents `tell <device> "..."`, the message arrives at
  this phone.
- **`forward`** *(optional)* — phone number where messages addressed to
  `device` are SMS'd. Without it, self-addressed messages are dropped.
  Typical setup: this is the phone owner's own number, so agents can reach
  the operator out-of-band. Replies SMS'd back into the device are routed
  via the phonebook (so the operator's number must also be in the phonebook
  under whatever participant name the cluster should see).
- **`phonebook`** — `name → phone-number` map for the SMS gateway role.
  Outbound: agents `tell <name> "..."` → SMS to that number. Inbound: SMS
  from a known number publishes back to MQTT as that name.

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
   missing, and the device's a8s identity once configured.

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
