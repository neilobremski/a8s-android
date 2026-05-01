# a8s-android

A high-reliability messaging bridge for the [a8s (Agent Infinity System)](https://github.com/google-gemini/gemini-cli). It turns an Android phone into a messaging gateway agent that can send and receive SMS/RCS messages via MQTT.

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

## Setup

1. **Install:** Download the latest APK from the GitHub Actions artifacts.
2. **Grant Permissions:**
    - SMS (Send/Receive/Read)
    - Notifications (for Foreground Service)
    - **Notification Access:** Manually enable in `Settings > Notifications > Notification access > a8s Android`.
    - **Battery Optimization:** Grant exemption when prompted.
3. **Configure:** Open the app and use the "Load Configuration" button to select your `a8s.json` file.

## Development

Built with Kotlin and Android SDK. 

### Build locally
```bash
./gradlew assembleDebug
```
