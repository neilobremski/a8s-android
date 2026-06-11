# MQTT command dedup — duplicate `/send` postmortem

Research / postmortem for GitHub issue **#36** (June 2026). Documents the
failure mode, what we shipped on Android (v1.27.0), and options for a
cleaner end-to-end design now that we control **both** the upstream a8s
daemon and this app.

---

## Executive summary

On a weak Wi‑Fi link the phone can **receive** MQTT commands and execute
them (SMS sends successfully) while the **control plane** — PUBACK timing,
upstream publish retries, correlation — breaks down. The upstream daemon
assumes the command never landed and **republishes** the same logical
`/send` (often with a **fresh envelope ULID**). Android treated every
arrival as new and delivered duplicate texts.

This is **not** “Android sent SMS but failed to report success.” The
`SMS queued to …` command replies on the bus are correct acks. The bug is
**side-effect idempotency**: outbound SMS must happen at most once per
logical command even when MQTT delivers duplicates.

Android fix (v1.27.0): `CommandDedup` — envelope-`id` cache **plus**
payload fingerprint for `/send`, `/reply`, `/mms`. See `CommandDedup.kt`,
`CommandDispatch.kt`, `CmdHelpers.outboundSmsDedupKey`.

---

## Incident particulars (2026-06-11)

| Field | Value |
|---|---|
| Device | Motorola moto g play 2023, Android 13 |
| App | a8s-android v1.26.0 (build 31) |
| Radio | Weak Wi‑Fi (~−113 dBm) |
| Symptom | Five+ identical SMS within ~2 minutes |
| Upstream | a8s daemon on Linux → HiveMQ shared topic |

**User report:** “There seems to be some text retry problems this morning.
It's sending me the same message over and over.”

**Transaction pattern:** Multiple `RECEIVED_REMOTE` entries with **distinct
ULIDs** but identical body (“Morning Ritual complete…”), e.g.:

| ULID (prefix) | Time (UTC) |
|---|---|
| `01KTVEBX9H…` | 13:36:24 |
| `01KTVEC650…` | 13:36:33 |
| `01KTVEDHBH…` | 13:37:23 |
| … | through 13:38 |

**Device logs:** Each arrival logged a successful SMS queue/send.
**Daemon logs:** MQTT publish retries; Android self-loopback publishes
correctly dropped.

---

## Root cause (why read works but retry still happens)

MQTT runs over one TCP session, but **downlink and uplink reliability are
not symmetric** on marginal Wi‑Fi:

```
  upstream daemon                    broker                 Android phone
       |                                |                         |
       |--- PUBLISH /send (QoS 1) ----->|                         |
       |<-- PUBACK (may timeout) -------|   (daemon thinks fail)  |
       |                                |--- DELIVER /send ------->|
       |                                |<-- PUBACK (may lag) ----|  (phone executes /send)
       |                                |                         |-- SMS to carrier --> user
       |--- PUBLISH /send (RETRY) ----->|                         |
       |    new ULID, same body         |--- DELIVER again ------>|  (no dedup → SMS again)
       |                                |                         |
       |<-- "SMS queued to …" echo -----|<-- publish echo --------|  (these are fine)
```

Interesting detail from the incident: Wi‑Fi was **good enough to pull new
messages from MQTT** but not reliable enough for the **ack / retry story**
to converge. The phone did its job; the cluster’s retry semantics did not
know that.

### What was *not* the bug

| Component | Role | Verdict |
|---|---|---|
| `SMS queued to …` MQTT replies | Command ack to sender | **Expected** — not duplicate SMS |
| `PublishDedup` | Inbound SMS/RCS → MQTT dedup | Different direction; unrelated |
| `PublishRetryQueue` | Android **outbound** MQTT when broker down | Different direction; unrelated |
| Self-loopback filter (`from == device`) | Drop our own topic echoes | Working |

---

## Android mitigation (shipped v1.27.0)

**Gate location:** `CommandDispatch.handle` → `gateInboundSmsCommand` before
`A8sService.executeCommand`. Only verbs with real SMS/RCS side effects:
`send`, `reply`, `mms`.

**Layer 1 — envelope `id` (ULID):** Same JSON payload redelivered → drop.
Handles broker replay and exact duplicate publishes.

**Layer 2 — payload fingerprint:** Different ULID, same logical command →
drop. Keys from `CmdHelpers.outboundSmsDedupKey`:

| Verb | Fingerprint shape |
|---|---|
| `/send` | `send\|<normalized-number>\|<body>` where body includes file URLs from `buildSendBody` |
| `/reply` | `reply\|<normalized-number>\|<text>` |
| `/mms` | `mms\|<normalized-number>\|<url>` |

Phone normalization strips non-digit characters except `+`.

**Window:** 5 minutes, 200 entries per cache (ids and payloads tracked
separately). Tunables in `CommandDedup` companion.

**On duplicate:** Log `/<verb> duplicate dropped (id=01KTVEBX…)` and
**return silently** — no second SMS, no second ack echo.

### Limitations of the Android-only fix

- **Legitimate repeats blocked:** Sending the exact same text to the same
  number twice within 5 minutes will be dropped on the second attempt even
  if intentional. Acceptable for current use; revisit if operators need
  deliberate repeats.
- **Fingerprint scope:** Only the three SMS verbs above. `/photo`,
  `/notify`, etc. are unaffected.
- **Process lifetime:** Cache is in-memory; process kill clears it (rare
  during duplicate burst).
- **No cross-device dedup:** Two phones on the same topic would each send
  once (probably desirable).

---

## Daemon-side context (upstream a8s)

Issue #36 notes the daemon separately increased PUBACK timeout and backoff.
Android dedup is still warranted: any duplicate publish path (retries, dual
daemons, broker redelivery) should not become duplicate SMS.

Relevant upstream surfaces (in the [a8s tree](https://github.com/neilobremski/bin/tree/main/apps/a8s)):

- Envelope `id` / ULID generation and `_process_pending` dedup ring
- MQTT publish retry when PUBACK or connect ack times out
- `tell` / outbox before the message hits the shared topic

The daemon already dedups **outbound** by ULID on its outbox pass; the gap
was **inbound command execution on the phone** when the daemon **republishes
a new envelope** for the same intent.

---

## Better end-to-end designs (both sides)

These are ordered roughly from “quick win” to “architecturally clean.”
Since we own daemon + Android, we can combine layers.

### 1. Stable idempotency key (recommended daemon change)

When the daemon decides to send SMS via the phone, mint **one**
`command_id` (ULID or UUID) for the *logical operation* and **reuse it on
MQTT retry** instead of allocating a fresh envelope `id` per attempt.

| Retry style | Android layer 1 | Android layer 2 |
|---|---|---|
| Same `id` on retry | **Catches it** | — |
| New `id` per retry (today) | Misses | Layer 2 catches |

Daemon retry with stable `id` lets Android rely primarily on layer 1 and
makes logs correlate across attempts.

### 2. Explicit command lifecycle in envelope metadata

Extend the wire shape (daemon + Android) with optional fields, e.g.:

```json
{
  "id": "01KTVEBX…",
  "meta": {
    "command_id": "01KTVEAA…",
    "attempt": 2
  },
  "content": "/send +1… hello"
}
```

Android dedupes on `command_id` regardless of envelope `id`. Daemon logs
show attempt count. Backward compatible if `meta` is optional.

### 3. Correlated ack before daemon retry

Today the phone acks with `SMS queued to …` **after** queuing SMS. The
daemon may retry **before** that echo arrives.

Options:

- **Daemon:** Do not republish until the echo with matching correlation
  (envelope `id` or `command_id`) is seen on the topic, or until a longer
  timeout than PUBACK alone.
- **Android:** Echo immediately on **accept** (before slow work) with
  `accepted: /send …` then `completed: …` after SMS queue — gives the
  daemon an earlier positive signal. More wire churn; needs daemon parser.

### 4. At-least-once command log on the phone (persistent)

Replace the in-memory 5-minute window with a small SQLite / file log of
`(fingerprint → executed_at)` surviving process restarts. Heavier; only
needed if duplicates span reboots or long gaps.

### 5. MQTT QoS and session tuning (both sides)

- Confirm subscribe/publish QoS on the command topic (QoS 1 is typical).
- Review Paho `maxInflight`, keepalive, and reconnect clean-session on
  both daemon and Android.
- On Android we already hold wake + wifi locks; weak RSSI may still starve
  **uplink** PUBACKs — monitor `remoteStatuses` in `/info` during incidents.

### 6. Side-effect classification in the daemon

Before `tell device "/send …"`, daemon outbox could tag messages with
`side_effects: ["sms"]` and apply **daemon-side** “already dispatched this
fingerprint” for N minutes. Belt-and-suspenders with Android dedup; useful
if multiple devices or non-Android consumers appear later.

---

## Suggested joint contract (sketch)

Minimal addition that ages well:

1. **Daemon** assigns stable `command_id` per logical operator action;
   retries reuse it; only increment `attempt` in optional `meta`.
2. **Android** dedup order: `command_id` (if present) → envelope `id` →
   payload fingerprint (keep as fallback for old daemons).
3. **Daemon** suppresses MQTT republish if echo with same `id` or
   `command_id` is observed within retry window.
4. **Both** log duplicate suppression at INFO with shared key for grep.

---

## Debugging checklist

When duplicate texts are reported again:

1. **Phone `/logs`** — look for `duplicate dropped` vs multiple
   `/send from sender=…` without drops.
2. **Cluster transaction log** — same body, different ULIDs → upstream
   retry with new ids (layer 2 scenario).
3. **Same ULID twice** → broker redelivery or dual subscriber (layer 1).
4. **`/info`** — Wi‑Fi RSSI, `Remotes: N/M connected`, recent disconnects.
5. **Daemon logs** — publish retry, PUBACK timeout, time between publish
   and `SMS queued` echo.

---

## Code map (Android)

| File | Role |
|---|---|
| `CommandDedup.kt` | Two-layer cache + `gateInboundSmsCommand` |
| `CommandDispatch.kt` | Dedup gate before `executeCommand` |
| `CmdHelpers.outboundSmsDedupKey` | Payload fingerprint builder |
| `MqttRoute.Command.envelopeId` | Parsed from JSON `id` |
| `PublishRetryQueue.kt` | **Outbound** MQTT retry (opposite problem) |
| `PublishDedup.kt` | **Inbound** SMS → MQTT dedup (opposite problem) |

Tests: `CommandDedupTest`, `CmdHelpersTest` (dedup keys), `MqttRouteTest`
(envelope id passthrough).

---

## References

- GitHub issue [#36](https://github.com/neilobremski/a8s-android/issues/36)
- `AGENTS.md` — agent onboarding + pitfall “don't bypass CommandDedup”
- MQTT 3.1.1 QoS 1 flow: PUBLISH → PUBACK on each hop
