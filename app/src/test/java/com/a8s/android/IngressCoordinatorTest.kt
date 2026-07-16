package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class IngressCoordinatorTest {
    private fun candidate(
        source: IngressSource,
        id: String,
        time: Long,
        body: String = "yes",
        richness: Int = 0,
    ) = IngressCandidate(source, id, time, "operator-phone", body, richness, "$id-value")

    @Test
    fun `same source event is rejected but identical new message is accepted`() {
        val coordinator = IngressCoordinator<String>(debounceMs = 10)
        assertEquals(IngressDecision.Accepted, coordinator.accept(candidate(IngressSource.SMS, "1", 100), 0))
        assertEquals(
            IngressDecision.DuplicateSourceEvent,
            coordinator.accept(candidate(IngressSource.SMS, "1", 100), 1),
        )
        assertEquals(IngressDecision.Accepted, coordinator.accept(candidate(IngressSource.SMS, "2", 200), 2))
    }

    @Test
    fun `different sources coalesce and richer observation wins`() {
        val coordinator = IngressCoordinator<String>(debounceMs = 10)
        coordinator.accept(candidate(IngressSource.NOTIFICATION, "n1", 100, richness = 1), 0)
        assertEquals(
            IngressDecision.Coalesced,
            coordinator.accept(candidate(IngressSource.MMS, "m1", 105, richness = 2), 1),
        )
        val ready = coordinator.drainReady(11).single()
        assertEquals("m1-value", ready.candidate.value)
    }

    @Test
    fun `same source observations never coalesce by body`() {
        val coordinator = IngressCoordinator<String>(debounceMs = 10)
        coordinator.accept(candidate(IngressSource.SMS, "1", 100), 0)
        assertEquals(IngressDecision.Accepted, coordinator.accept(candidate(IngressSource.SMS, "2", 101), 1))
        assertEquals(2, coordinator.drainReady(11).size)
    }

    @Test
    fun `completed hashed identity survives restart without plaintext`() {
        val file = File.createTempFile("ingress", ".json")
        file.deleteOnExit()
        val first = IngressCoordinator<String>(store = FileDedupStore(file), debounceMs = 0)
        val input = candidate(IngressSource.NOTIFICATION, "secret-source-id", 100, body = "secret body")
        first.accept(input, 0)
        first.complete(first.drainReady(0).single(), 0)
        val persisted = file.readText()
        assertTrue("secret" !in persisted)

        val second = IngressCoordinator<String>(store = FileDedupStore(file), debounceMs = 0)
        assertEquals(IngressDecision.DuplicateSourceEvent, second.accept(input, 1))
    }

    @Test
    fun `expired identity is accepted after retention`() {
        val coordinator = IngressCoordinator<String>(debounceMs = 0, retentionMs = 10)
        val input = candidate(IngressSource.MMS, "7", 100)
        coordinator.accept(input, 0)
        coordinator.complete(coordinator.drainReady(0).single(), 0)
        assertEquals(IngressDecision.Accepted, coordinator.accept(input, 11))
    }
}
