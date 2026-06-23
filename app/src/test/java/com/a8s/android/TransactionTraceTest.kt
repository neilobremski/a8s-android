package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransactionTraceTest {

    @BeforeEach
    fun clear() {
        TransactionTrace.clear()
    }

    @Test
    fun `render shows newest first with status and flow`() {
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = "01KTVEBX9HA8ZHMH5BP2EA38WQ",
                flow = "SUB_FWD",
                status = TransactionTrace.Status.OK,
                from = "Bob",
                to = "SMS ••••6756",
                summary = "text: Here is the file",
                detail = "  • photo.jpg: 1 storage url(s)\nsms: sent with inline URL(s)",
            ),
        )
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = "01KTVEC65041382PWQZW785YCK",
                flow = "MQTT_IN",
                status = TransactionTrace.Status.DROP,
                from = "Bob",
                to = "my-phone",
                summary = "not in phonebook",
            ),
        )
        val out = TransactionTrace.render(10)
        assertTrue(out.contains("trace: last 2 transaction(s)"))
        assertTrue(out.contains("SUB_FWD"))
        assertTrue(out.contains("01KTVEC6")) // newer first
        val idxNew = out.indexOf("01KTVEC6")
        val idxOld = out.indexOf("01KTVEBX")
        assertTrue(idxNew < idxOld)
    }

    @Test
    fun `summarizeEnvelopeFiles flags empty storage urls`() {
        val json = JSONObject(
            """{"files":[{"filename":"report.pdf","storage":[]}]}""",
        )
        val s = TransactionTrace.summarizeEnvelopeFiles(json)
        assertTrue(s.contains("report.pdf"))
        assertTrue(s.contains("0 url"))
    }

    @Test
    fun `summarizeEnvelopeFiles flags parse mismatch`() {
        val json = JSONObject(
            """{"files":[{"filename":"","storage":["https://x/"]}]}""",
        )
        val s = TransactionTrace.summarizeEnvelopeFiles(json)
        assertTrue(s.contains("1 in JSON, 0 parsed"))
    }

    @Test
    fun `summarizeDownloadResults shows NO_URLS and FAILED`() {
        val detail = TransactionTrace.summarizeDownloadResults(
            listOf(
                FileDownloader.DownloadResult(
                    null, null, "a.pdf",
                    FileDownloader.DownloadOutcome.NO_URLS, "empty storage[]",
                ),
                FileDownloader.DownloadResult(
                    null, "https://tempfile.org/x/", "b.jpg",
                    FileDownloader.DownloadOutcome.FAILED, "timeout",
                ),
            ),
        )
        assertTrue(detail.contains("NO storage urls"))
        assertTrue(detail.contains("download failed"))
        assertTrue(detail.contains("SMS will include"))
    }

    @Test
    fun `empty render`() {
        assertTrue(TransactionTrace.render().contains("empty"))
    }

    @Test
    fun `maskTo masks digit targets`() {
        assertTrue(TransactionTrace.maskTo("text-13602196756").contains("••••"))
        assertFalse(TransactionTrace.maskTo("Bob").contains("••••"))
    }
}
