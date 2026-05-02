# A8s‑Android

## What an agent might miss

- **Build shortcut** – `./gradlew assembleDebug`: produces `app/build/outputs/apk/debug/app-debug.apk`.
- **Platform** – Android 17 SDK, Kotlin 1.9‑ish, Java 17.
- **Permissions needed at install time**:
  - SMS (SEND_SMS, RECEIVE_SMS, READ_SMS)
  - Notifications (POST_NOTIFICATIONS, BIND_NOTIFICATION_LISTENER_SERVICE)
  - Battery optimization exemption
  - Foreground service & wake‑lock
- **Runtime config** – the app loads a JSON file named `a8s.json` via the Storage Access Framework. The schema is shown in the README.
- **Default launch** – run `adb install app/build/outputs/apk/debug/app-debug.apk` and open the app. The UI will prompt for the config file.
- **Gradle wrapper** – uses Gradle 8.5. On Windows call `./gradlew.bat`.
- **Project name** – `a8s-android`. Refer to this when filtering Android logs.
- **Service** – a foreground `A8sService` (type `connectedDevice`) keeps the device online at all times.
- **Test/CI** – unit tests live in `app/src/test/...`; CI runs Detekt + tests + APK build via `.github/workflows/ci.yml`.

## Quick start

1. Clone the repo.
2. Run `./gradlew assembleDebug`.
3. Install the APK on a device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. Open the app, grant the required permissions, and load `a8s.json`.
5. The bridge will immediately start listening for SMS and MQTT.

## Troubleshooting

- **Package not found**: On Android 13+ the app must have permission to access the notification listener. Go to `Settings > Apps > a8s Android > Advanced > Notification access` and enable it.
- **Battery optimization kills service**: Add the app to the exemption list via `Settings > Battery > Battery optimization > All apps > a8s Android > Never`. Or run `adb shell am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS` to open the panel.
- **MQTT connection fails**: Verify that the broker URL in `a8s.json` uses the correct port and TLS settings.
- **Log output**: Use `adb logcat -s com.a8s.android` to restrict logs to this app.

## File references

- `app/src/main/AndroidManifest.xml` – declares all required Android permissions.
- `app/src/main/java/com/a8s/android/A8sService.kt` – foreground service.
- `app/src/main/java/com/a8s/android/SmsReceiver.kt` – handles inbound SMS.
- `app/src/main/java/com/a8s/android/SmsNotificationListener.kt` – intercepts RCS notifications.
- `gradle/wrapper/gradle-wrapper.properties` – uses Gradle 8.5.
- `README.md` – summary of features and configuration.
