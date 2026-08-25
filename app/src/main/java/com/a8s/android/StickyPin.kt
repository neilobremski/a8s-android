package com.a8s.android

/**
 * Staleness rules for the SMS sticky routing pin. Pure Kotlin — no Android
 * types — so the re-pin decision an inbound agent forward makes can be unit
 * tested without touching `SharedPreferences`. The stored state is read and
 * mutated inside `IncomingSmsRouter`'s pin lock; these functions only decide.
 */
object StickyPin {

    enum class Decision {
        /** Fresh pin — leave it alone. */
        KEEP,

        /**
         * Legacy pin from before timestamps existed: persist a timestamp now
         * and keep the target. It gets one full TTL window from this first
         * observation, then ages normally — never immortal, never instantly
         * stolen mid-conversation by the first forward after an upgrade.
         */
        STAMP_AND_KEEP,

        /** No pin at all — adopt the candidate. */
        PIN_FIRST,

        /** Pin is stale — the candidate supersedes it. */
        REPIN,
    }

    /**
     * The inbound re-pin decision. [currentTarget] and [pinnedAtMs] are the
     * stored pin state ([pinnedAtMs] null when the pin predates timestamps);
     * the caller reads them and applies the decision inside one critical
     * section.
     */
    fun decide(currentTarget: String?, pinnedAtMs: Long?, nowMs: Long, ttlMs: Long): Decision = when {
        currentTarget == null -> Decision.PIN_FIRST
        pinnedAtMs == null -> Decision.STAMP_AND_KEEP
        shouldRepin(nowMs, pinnedAtMs, ttlMs) -> Decision.REPIN
        else -> Decision.KEEP
    }

    /**
     * True when a pin set at [pinnedAtMs] is old enough, as of [nowMs], for a
     * fresh inbound sender to re-pin over it. `ttlMs == 0` disables expiry
     * entirely — the pin is never stale.
     */
    fun shouldRepin(nowMs: Long, pinnedAtMs: Long, ttlMs: Long): Boolean {
        if (ttlMs == 0L) return false
        return nowMs - pinnedAtMs >= ttlMs
    }
}
