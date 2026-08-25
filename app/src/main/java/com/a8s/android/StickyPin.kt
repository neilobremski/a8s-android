package com.a8s.android

/**
 * Staleness rule for the SMS sticky routing pin. Pure Kotlin — no Android
 * types — so an inbound agent forward can decide whether it may steal the
 * pin from the last explicit `tell`/`hey`/`ok` target without touching
 * `SharedPreferences`.
 */
object StickyPin {

    /**
     * True when a pin set at [pinnedAtMs] is old enough, as of [nowMs], for a
     * fresh inbound sender to re-pin over it. `ttlMs == 0` disables expiry
     * entirely — the pin is never stale. A pin decoded from a legacy value
     * with no stored timestamp is passed in here as `pinnedAtMs == nowMs`
     * (fresh-now), which is why it evaluates to not-stale.
     */
    fun shouldRepin(nowMs: Long, pinnedAtMs: Long, ttlMs: Long): Boolean {
        if (ttlMs == 0L) return false
        return nowMs - pinnedAtMs >= ttlMs
    }
}
