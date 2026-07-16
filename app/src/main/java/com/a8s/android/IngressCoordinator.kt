package com.a8s.android

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

fun hashIngressIdentity(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

enum class IngressSource {
    SMS,
    NOTIFICATION,
    MMS,
}

data class IngressCandidate<T>(
    val source: IngressSource,
    val sourceEventId: String,
    val eventTimeMs: Long,
    val principal: String,
    val body: String,
    val richness: Int,
    val value: T,
)

sealed class IngressDecision {
    data object Accepted : IngressDecision()
    data object Coalesced : IngressDecision()
    data object DuplicateSourceEvent : IngressDecision()
}

data class ReadyIngress<T>(
    val candidate: IngressCandidate<T>,
    internal val eventHashes: Set<String>,
)

/**
 * Short, bounded coalescing window in front of SMS/RCS command execution and
 * MQTT publication. Persisted identities are SHA-256 hashes of source event
 * IDs, never message bodies.
 */
class IngressCoordinator<T>(
    private val store: FileDedupStore? = null,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val merge: (current: T, incoming: T) -> T = { _, incoming -> incoming },
) {
    private data class Pending<T>(
        var preferred: IngressCandidate<T>,
        val firstSeenMs: Long,
        val sources: MutableSet<IngressSource>,
        val eventHashes: MutableSet<String>,
    )

    private val committed = store?.load()?.toMutableMap() ?: mutableMapOf()
    private val pending = mutableListOf<Pending<T>>()

    @Synchronized
    fun accept(candidate: IngressCandidate<T>, now: Long = System.currentTimeMillis()): IngressDecision {
        evict(now)
        val eventHash = hashIngressIdentity("${candidate.source}|${candidate.sourceEventId}")
        if (eventHash in committed || pending.any { eventHash in it.eventHashes }) {
            return IngressDecision.DuplicateSourceEvent
        }

        val match = pending.firstOrNull { equivalent(it, candidate) }
        if (match != null) {
            match.sources += candidate.source
            match.eventHashes += eventHash
            val preferred = if (candidate.richness > match.preferred.richness) candidate else match.preferred
            match.preferred = preferred.copy(value = merge(match.preferred.value, candidate.value))
            return IngressDecision.Coalesced
        }

        pending += Pending(
            preferred = candidate,
            firstSeenMs = now,
            sources = mutableSetOf(candidate.source),
            eventHashes = mutableSetOf(eventHash),
        )
        while (pending.size > maxEntries) pending.removeAt(0)
        return IngressDecision.Accepted
    }

    @Synchronized
    fun drainReady(now: Long = System.currentTimeMillis()): List<ReadyIngress<T>> {
        val ready = pending.filter { now - it.firstSeenMs >= debounceMs }
        pending.removeAll(ready.toSet())
        return ready.map { ReadyIngress(it.preferred, it.eventHashes.toSet()) }
    }

    @Synchronized
    fun complete(ready: ReadyIngress<T>, now: Long = System.currentTimeMillis()) {
        ready.eventHashes.forEach { committed[it] = now }
        evict(now)
        boundCommitted()
        store?.save(committed)
    }

    @Synchronized
    fun clear() {
        pending.clear()
        committed.clear()
        store?.save(committed)
    }

    private fun equivalent(pending: Pending<T>, candidate: IngressCandidate<T>): Boolean {
        if (candidate.source in pending.sources) return false
        val current = pending.preferred
        if (current.principal != candidate.principal) return false
        if (abs(current.eventTimeMs - candidate.eventTimeMs) > COALESCE_TIME_MS) return false
        return normalizeBody(current.body) == normalizeBody(candidate.body) ||
            current.body in GENERIC_MEDIA_BODIES || candidate.body in GENERIC_MEDIA_BODIES
    }

    private fun evict(now: Long) {
        val cutoff = now - retentionMs
        committed.entries.removeAll { it.value < cutoff }
    }

    private fun boundCommitted() {
        while (committed.size > maxEntries) {
            val oldest = committed.minByOrNull { it.value } ?: break
            committed.remove(oldest.key)
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 2_500L
        const val DEFAULT_RETENTION_MS = IngressStaleness.SMS_MAX_AGE_MS
        const val DEFAULT_MAX_ENTRIES = 2_000
        private const val COALESCE_TIME_MS = 10_000L
        private val GENERIC_MEDIA_BODIES = setOf("[MMS media]", "Audio clip")

        private fun normalizeBody(body: String): String = body.trim().replace(Regex("\\s+"), " ")
    }
}
