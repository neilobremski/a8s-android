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
