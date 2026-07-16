package com.a8s.android

/** Aggregates per-remote retry outcomes into one user-visible `/tell` failure. */
class TellRetryTracker(private val maxTracked: Int = DEFAULT_MAX_TRACKED) {
    data class FailureNotice(
        val replyTo: String,
        val target: String,
        val attempts: Int,
    )

    private data class Watch(
        val replyTo: String,
        val target: String,
        val pendingRemotes: MutableSet<String>,
    )

    private val watches = linkedMapOf<String, Watch>()

    @Synchronized
    fun watch(envelopeId: String, replyTo: String, target: String, remotes: Set<String>) {
        if (envelopeId.isBlank() || replyTo.isBlank() || remotes.isEmpty()) return
        watches[envelopeId] = Watch(replyTo, target, remotes.toMutableSet())
        while (watches.size > maxTracked) watches.remove(watches.keys.first())
    }

    @Synchronized
    fun accepted(envelopeId: String) {
        watches.remove(envelopeId)
    }

    @Synchronized
    fun exhausted(envelopeId: String, remoteName: String, attempts: Int): FailureNotice? {
        val watch = watches[envelopeId] ?: return null
        watch.pendingRemotes.remove(remoteName)
        if (watch.pendingRemotes.isNotEmpty()) return null
        watches.remove(envelopeId)
        return FailureNotice(watch.replyTo, watch.target, attempts)
    }

    @Synchronized
    fun clear() {
        watches.clear()
    }

    companion object {
        const val DEFAULT_MAX_TRACKED = 200
    }
}
