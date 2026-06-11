# a8s cluster integration — upstream daemon & Android bridge

How the [a8s (Agent Infinity System)](https://github.com/neilobremski/bin/tree/main/apps/a8s)
daemon routes messages over MQTT, and how **a8s-android** participates as a
remote cluster node. Written for future agents and developers working on
either side of the wire.

**Upstream source of truth** (on the operator's machine):

```
~/bin/apps/a8s/          # or $HOME/bin/apps/a8s
├── README.md            # mental model, remotes, wire format
├── mailbox.py           # outbox ingest, local + remote routing
├── network.py           # MQTT publish/receive, ULID dedup
├── daemon.py            # attached loop, subscriber threads
└── transports/mqtt.py   # Paho transport (QoS 1, persistent session)
```

This Android repo implements the **phone-side participant** — not a full a8s
daemon, but a subscriber/publisher on the same shared MQTT topic with
compatible envelope JSON.

---

## Executive summary

| Layer | Role |
|---|---|
| **a8s daemon** (Linux/macOS) | Registry of local agents; routes `.outbox/` → local inboxes **or** MQTT remotes when recipient unknown locally |
| **MQTT broker** | Shared bus; all clusters on the same `topic` see the same envelopes |
| **a8s-android** | One logical participant (`device` in `a8s.json`); slash commands, SMS gateway, file upload via storage services |

Key invariant from upstream README: **recipient opacity** — senders `tell <name>`
without knowing whether the recipient is an LLM, a script, or a phone bridge.

---

## Wire format (shared contract)

Both sides use the same JSON envelope (see `AGENTS.md` in this repo and
`README.md` upstream):

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

With attachments:

```json
"files": [
  { "filename": "photo.jpg", "storage": ["https://tempfile.org/abc/"] }
]
```

| Field | Upstream | Android |
|---|---|---|
| `id` | Required; `network.receive_envelope` drops invalid/missing ULID | `Ulid.new()` on every outbound publish |
| `from` | Force-stamped from outbox owner at routing (`mailbox.py`) | Set to `config.device` (or sub-identity — see #38) |
| `to` | Any string; resolved against local registry | Must match routing rules (see below) |
| `files` | Downloaded via storage services into recipient `.files/` | Uploaded via `TempFileOrgService` on outbound; parsed on inbound commands |

Cluster-wide receive dedup: `~/.a8s/seen-ids` ring (upstream) and host
router dedup. Android relies on broker + upstream dedup for generic inbound;
see `MQTT_COMMAND_DEDUP.md` for Android-side **command** dedup (issue #36).

---

## Upstream routing (outbound from a daemon)

When a local agent writes to its `.outbox/`, `mailbox.route_outboxes` runs a
two-phase pass (`mailbox.py`):

1. **Ingest** — rename `.outbox/*.json` → `~/.a8s/agents/<sender>/pending/`
2. **Process** — for each pending envelope:
   - Try **local delivery** if `to` resolves in the registry (`resolve_name`)
   - If unknown locally **and remotes configured** → **publish to MQTT**
   - If unknown locally **and no remotes** → trash with
     `unknown recipient …; trashing` (`txlog: DROPPED`)

Relevant logic (`mailbox.py:_process_pending`):

```python
try:
    kind, member_names = resolve_name(recipient_name)
    local_target_known = True
except KeyError:
    pass  # unknown locally — remotes (if any) might still deliver
```

So **`to` can be any string** — including names that only exist on another
cluster (e.g. an Android `device` name, or a planned `text-360-219-6756`
sub-identity). If the sending machine doesn't know that name, it still
**fans out to all configured remotes** (README, “Remotes” section).

Publish uses QoS 1 with per-remote exponential backoff
(`network.make_publish_remotes`). File payloads are uploaded to storage
services first, then the wire-shaped `files[]` is published (#90).

**`from` on the wire:** For messages originating from a registered local
agent, the daemon **overwrites** `from` with the sender's canonical name
(the outbox location is the unforgeable identity). For Android-originated
publishes, the phone sets `from` directly (today: `config.device`; planned:
opaque sub-identity for `/tell` — issue #38).

---

## Upstream routing (inbound on a daemon)

Each running daemon starts one MQTT **subscriber per remote** on the **same
topic** (`network.start_remotes`). Incoming bytes hit
`network.receive_envelope`:

1. Parse JSON; require valid ULID `id`
2. Dedup against `seen-ids` ring — silent return if already seen
3. `resolve_name(to)` against **local registry only**
4. If recipient **unknown locally** → **drop silently** (no log spam;
   `network.py` ~328–332)
5. If known → stage JSON into recipient `inbox/`, log `RECEIVED_REMOTE`,
   append ULID to seen-ids
6. If `files[].storage` URLs present → download via configured storage
   services into recipient's `.files/`

This is why **arbitrary participant names work** for cross-cluster traffic:

- Linux daemon tells `text-360-219-6756` → not in local registry → **published to MQTT**
- Other Linux daemons receive it → `text-360-219-6756` not local → **dropped**
- Android receives it → **matches sub-identity handler** (planned #38) → SMS forward

No per-agent MQTT topics. Everyone shares one `topic`; **routing is by
envelope `to`**, filtered client-side.

---

## Android routing (today)

Android is **not** a full a8s daemon. It does not have a registry, inbox, or
wake loop. It implements a **narrow participant**:

### MQTT subscribe

- One Paho client **per** `remotes` entry in `a8s.json` (same broker/topic
  can appear multiple times with different local names)
- All inbound payloads → `A8sService.handleMqttMessage` → `decideRoute`
  (`MqttRoute.kt`)

### Inbound command gate (`decideRoute`)

For `to == config.device`:

| Check | Result |
|---|---|
| `from == config.device` | `Drop` (broker echo / self-loopback) |
| `from` not in phonebook **keys** | `Drop` (auth gate) |
| `content` starts with `/` | `Command` → execute locally |
| else | `NotACommand` (logged; no implicit SMS forward) |

Phonebook maps **participant name → phone number** for SMS/RCS reverse lookup
and authorization. Only phonebook **names** (not raw numbers) pass the MQTT
command gate today.

### Outbound publishes

- Command replies: `publishToSender` → fan-out to all connected remotes
- SMS/RCS inbound: `publishIncoming` → MQTT to matched phonebook names
- Failed publishes: `PublishRetryQueue` (Android-side outbound retry —
  opposite direction from issue #36; see `MQTT_COMMAND_DEDUP.md`)

### SMS / RCS bridge (today)

| Direction | Path |
|---|---|
| SMS in | `SmsReceiver` / `SmsNotificationListener` / `MmsObserver` → `publishIncoming` → MQTT |
| SMS out | `/send`, `/reply`, `/mms` commands → `sendSms` |
| Media | Extract → upload via storage service → `files[]` on envelope |

Inbound SMS does **not** execute slash commands locally today (issue #38).

---

## Planned: SMS commands & opaque sub-identity (#38)

Tracked in [GitHub issue #38](https://github.com/neilobremski/a8s-android/issues/38).

### Phase 1 — SMS → local command

Operator texts `/logs` from an allowed number → execute on device → reply
via **SMS** (not MQTT).

### Phase 2 — `/tell` with sub-identity

Operator texts `/tell Bob hello`. Phone publishes:

```json
{
  "from": "text-13602196756",
  "to": "Bob",
  "content": "hello",
  "id": "<new ULID>"
}
```

- `from` uses configurable prefix + normalized operator number — **not**
  `config.device` (keeps the real device name off the network)
- Upstream daemons don't know `text-13602196756` → publish to MQTT on
  send; **drop silently** on receive — **no registration required**
- Android filters `to == text-13602196756` → forward `content` + `files[]`
  to operator via SMS (same attachment handling as `/send`)

```mermaid
sequenceDiagram
    participant Phone as Operator handset
    participant And as a8s-android
    participant MQTT as Shared MQTT topic
    participant Daemon as Linux a8s daemon
    participant Bob as Agent Bob

    Phone->>And: SMS /tell Bob hello
    And->>MQTT: from text-13602196756 to Bob
    MQTT->>Daemon: envelope unknown to
    Note over Daemon: resolve_name fails, drop
    MQTT->>Bob: envelope if Bob on cluster
    Bob->>MQTT: from Bob to text-13602196756
    MQTT->>Daemon: envelope unknown to
    Note over Daemon: drop silently
    MQTT->>And: envelope
    Note over And: to matches sub-identity
    And->>Phone: SMS reply and file URLs
```

---

## Design decisions (#38, operator-confirmed)

### Phone number normalization & sub-identity format

**Rule:** strip every character that is **not a digit** (`0-9` only). No
`+`, spaces, or dashes in the canonical form.

This matches the digits-only path already used in `A8sAndroid.getReplyActionByDigits`
and suffix matching in `publishIncoming`. (Elsewhere the repo also has
`[^0-9+]` normalization in `CmdHelpers.normalizePhone` and
`publishIncoming` direct match — #38 will introduce **one shared helper**
and converge call sites.)

**Planned helper** (pure Kotlin, unit-tested):

```kotlin
/** Digits only — e.g. "+1 (360) 219-6756" → "13602196756" */
fun normalizePhoneDigits(raw: String): String

/** MQTT participant alias for SMS-origin /tell — e.g. prefix "text" → "text-13602196756" */
fun buildSmsSubIdentity(prefix: String, phoneNumber: String): String
```

| Input | `normalizePhoneDigits` | `buildSmsSubIdentity("text", …)` |
|---|---|---|
| `+1 360-219-6756` | `13602196756` | `text-13602196756` |
| `3602196756` | `3602196756` | `text-3602196756` |

Country code is preserved when present in the source string (the leading
`1` is a digit). **Do not** insert decorative dashes into the wire id
(`text-1360-219-6756` is not the canonical form).

`tell_prefix` is configurable per device (default `text`). One sub-identity
per **operator phone number** that is authorized for SMS commands (see below).

### SMS command authorization — phonebook vs allowlist

The existing `phonebook` in `a8s.json` is already loaded at startup and
shown in Setup. It maps **participant name → phone number** and serves two
roles today:

| Role | Uses phonebook… |
|---|---|
| MQTT command auth | **keys** (names): `from` must be a known participant name |
| Inbound SMS → MQTT | **values** (numbers): reverse-lookup sender → publish as name |

**Decision for SMS-originated commands:** authorize by **phone number**
(the **values** in `phonebook`), not by participant name.

- Inbound SMS from a number whose normalized digits match any phonebook
  **value** may execute slash commands and receive replies via SMS.
- For the current single-operator setup (one entry, your number), that
  number is the only one that can SMS-command the device.

**Number matching** is canonical-digits with a suffix tolerance for a
missing country code, but only above `MIN_SUFFIX_MATCH_DIGITS` (7) so a
short number / short-code can't match a phonebook entry by tail
coincidence. The SMS path and the MQTT-publish path both go through
`PhoneNormalize.matchPhonebookEntries` so they can't diverge.

**Contrast with MQTT:** MQTT still requires `from` ∈ phonebook **keys**.
SMS uses phonebook **values**. Same config file, different field.

### SMS threat model — verb allow-list (`SmsCommandPolicy`)

SMS/RCS is **not** a trustworthy auth channel and is treated as such:

- Raw SMS originating addresses are spoofable (SMS gateways let a sender
  set an arbitrary `from`). MQTT `from` is host-stamped over TLS; SMS has
  no equivalent.
- The RCS path is marginally harder to spoof (the transport authenticates
  the registered number), **but** `SmsNotificationListener` only sees a
  notification *display name* resolved through `Contacts` — a fuzzy,
  attacker-influenceable mapping. So in practice both channels collapse to
  "a phone number / name we matched," which is not a security boundary.

Because of this, SMS-originated commands are gated by a **verb allow-list**
(`SmsCommandPolicy`), independent of channel:

- **Safe-by-default subset:** `info, logs, location, say, notify, ls,
  find, dashboard, photo, video, audio, screenshot, tell`. Observe /
  notify / capture only.
- **Excluded by default** (require explicit opt-in): `update` (installs
  arbitrary APKs), `rm` / `cat` (filesystem delete / exfiltration),
  `send` / `mms` / `reply` (sends SMS *as the victim*), and the
  UI-automation verbs `macro` / `tap` / `longtap` / `swipe` / `key` /
  `input`.
- **Opt in** via `sms_command.allowed_commands` — an explicit verb list,
  or `["*"]` to accept the full surface (operator accepts the risk).
- A disallowed verb from a known phonebook number gets a short
  `'/verb' is not permitted over SMS.` reply; MQTT commands are
  unaffected and keep the full surface.

```json
"sms_command": {
  "tell_prefix": "text",
  "allowed_commands": ["info", "logs", "location", "tell"]
}
```

**Anti-abuse:** SMS-originated commands are deduped per
participant+body (a single message can arrive via both `SmsReceiver` and
the Google Messages notification), and sub-identity → SMS forwards are
deduped per envelope ULID + destination so broker redelivery can't
amplify into multiple texts. Sub-identity forwards remain open by design
(any agent the operator `/tell`'d — or any agent at all — can reply to the
operator's number); this is a deliberate trade-off, mitigated by dedup and
the masked-number logging.

### Sub-identity lifetime: always-on (not session-scoped)

Two models were on the table:

| Model | Behavior |
|---|---|
| **Session-scoped** | Only accept `to: text-<digits>` for N minutes after each `/tell`; then drop replies until the next `/tell` opens a new window. |
| **Always-on** *(chosen)* | `text-<digits>` is a **stable alias** for this device + operator number whenever config is loaded. Any envelope to that address is forwarded to SMS. |

**Operator choice: always-on.** The sub-identity is not a short-lived
session token; it is the opaque participant name for “this phone's SMS
operator” on the shared topic.

Implications:

- Bob (or anyone who learns the opaque id) can `tell text-13602196756 …`
  at any time and the phone will SMS it to the operator — same as knowing
  any participant name on the cluster.
- No timer or “session end” state to manage in the app.
- Security relies on opacity of the id + MQTT topic credentials, not a
  TTL. Acceptable for a single-operator handset.

If spam or misdirected tells become a problem later, we can add an optional
session window without changing the wire format.

---

## Android vs daemon: responsibility matrix

| Concern | a8s daemon | a8s-android |
|---|---|---|
| Agent registry | `~/.a8s/a8s.json` | `a8s.json` `device` + `phonebook` only |
| Local inbox / wake | Yes | No — commands run in `A8sService` |
| Unknown `to` outbound | Publish to remotes | N/A (phone is usually the leaf) |
| Unknown `to` inbound | Silent drop | Planned: sub-identity handler (#38) |
| `from` stamping | Force-stamp from outbox owner | App sets `from` on publish |
| File cross-cluster | `services` in `network.json` | `services` in `a8s.json` |
| Command auth | Registry membership | Phonebook gate on `device` address |
| Outbound MQTT retry | `mailbox` pending + backoff | `PublishRetryQueue` |
| Inbound command dedup | ULID `seen-ids` ring | `CommandDedup` (issue #36) |

---

## Asymmetric MQTT (why duplicates happen)

On weak Wi‑Fi the phone can **receive** commands while **acks or upstream
publish confirmation** fail. The Linux daemon may republish with a **new ULID**
and the same body; Android used to execute every copy → duplicate SMS.

See **`MQTT_COMMAND_DEDUP.md`** for the postmortem and Android mitigations.

---

## Code map

### Upstream (`~/bin/apps/a8s`)

| File | Read this for |
|---|---|
| `README.md` | Mental model, remotes, opacity invariant |
| `mailbox.py` | Local vs remote routing, unknown recipient, pending retry |
| `network.py` | `receive_envelope`, `make_publish_remotes`, ULID validation |
| `transports/mqtt.py` | QoS 1, subscribe loop, publish |
| `services/tempfile_org.py` | Storage wire format (mirrored in Android) |
| `tests/test_mailbox.py` | `test_unknown_recipient_with_remotes_accepted` |
| `tests/test_network.py` | `test_unknown_recipient_dropped_silently` |

### Android (`app/src/main/java/com/a8s/android/`)

| File | Read this for |
|---|---|
| `MqttRoute.kt` | `decideRoute`, envelope `id`, `files[]` parse |
| `A8sService.kt` | MQTT clients, `handleMqttMessage`, `publishIncoming` |
| `CommandDispatch.kt` / `CommandDedup.kt` | Inbound command gate + dedup |
| `Network.kt` / `TempFileOrgService.kt` | Config + storage parity with upstream |
| `SmsReceiver.kt` / `SmsNotificationListener.kt` | Inbound SMS → MQTT (not commands yet) |

---

## Related docs & issues

| Reference | Topic |
|---|---|
| [issue #38](https://github.com/neilobremski/a8s-android/issues/38) | SMS-originated commands + `/tell` sub-identity |
| [issue #36](https://github.com/neilobremski/a8s-android/issues/36) | Duplicate `/send` from MQTT retries |
| `MQTT_COMMAND_DEDUP.md` | Android dedup postmortem |
| `AGENTS.md` | Agent onboarding for this repo |
| `~/bin/apps/a8s/README.md` | Canonical a8s documentation |
| `~/bin/apps/a8s/DEVELOPMENT.md` | Upstream contributor notes |

---

## Design notes for joint changes

When changing wire behavior, check **both** sides:

1. **ULID required?** — upstream drops without it; Android must always send one.
2. **New `to` address family?** — upstream publishes unknown names to remotes;
   daemons drop on receive unless registered; Android may need a new inbound filter.
3. **Files** — wire shape uses `storage: [url]`; both sides need `tempfile_org`
   (or compatible) for bytes to flow.
4. **Auth** — daemon trusts outbox location for `from`; Android trusts phonebook
   for `to == device`. Sub-identity (#38) is a third auth path.
5. **Tests** — `MqttRoute.kt` / `Network.kt` on Android;
   `tests/test_network.py` / `tests/test_mailbox.py` upstream.
