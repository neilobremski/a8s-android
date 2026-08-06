package com.a8s.android

import java.io.File

/** Fetches a public https URL into a file. Injected so tests never touch the
 *  network, and so one downloader carries the rules for every caller. */
typealias HttpFetch = (String, File) -> HttpGet.Result

object FileDownloader {

    enum class DownloadOutcome { OK, NO_URLS, FAILED }

    data class DownloadResult(
        val file: File?,
        val fallbackUrl: String?,
        val filename: String = file?.name ?: "",
        val outcome: DownloadOutcome = when {
            file != null -> DownloadOutcome.OK
            fallbackUrl != null -> DownloadOutcome.FAILED
            else -> DownloadOutcome.NO_URLS
        },
        val detail: String = "",
    )

    fun downloadFiles(
        files: List<EnvelopeFile>,
        services: List<StorageService>,
        destDir: File,
        httpFetch: HttpFetch = { url, dest -> HttpGet.download(url, dest) },
    ): List<DownloadResult> {
        destDir.mkdirs()
        return files.map { ef -> downloadOne(ef, services, destDir, httpFetch) }
    }

    private fun downloadOne(
        ef: EnvelopeFile,
        services: List<StorageService>,
        destDir: File,
        httpFetch: HttpFetch,
    ): DownloadResult {
        if (ef.storageUrls.isEmpty()) {
            return DownloadResult(
                file = null,
                fallbackUrl = null,
                filename = ef.filename,
                outcome = DownloadOutcome.NO_URLS,
                detail = "envelope entry has empty storage[]",
            )
        }
        val resolved = AttachmentPath.bundleFile(destDir, ef.filename)
        val dest = resolved.file
        if (dest == null) {
            A8sAndroid.log("Rejected inbound attachment name '${ef.filename}': ${resolved.reason}")
            return DownloadResult(
                file = null,
                fallbackUrl = ef.storageUrls.first(),
                filename = ef.filename,
                outcome = DownloadOutcome.FAILED,
                detail = resolved.reason,
            )
        }
        var lastDetail = ""
        for (url in ef.storageUrls) {
            for (svc in services) {
                try {
                    if (svc.retrieve(url, dest)) {
                        return DownloadResult(
                            file = dest,
                            fallbackUrl = null,
                            filename = ef.filename,
                            outcome = DownloadOutcome.OK,
                            detail = "via ${svc.id}",
                        )
                    }
                } catch (e: StorageException) {
                    lastDetail = "${svc.id}: ${e.message}"
                    A8sAndroid.log("Download ${ef.filename} from ${svc.id} failed: ${e.message}")
                }
            }
            // No configured service claimed the URL. Presigned S3 links,
            // `rclone link` results and other public object URLs are ordinary
            // https, so fetch them directly — a receiver should not need the
            // sender's backend configured to read its attachments.
            when (val r = httpFetch(url, dest)) {
                is HttpGet.Result.Ok -> return DownloadResult(
                    file = dest,
                    fallbackUrl = null,
                    filename = ef.filename,
                    outcome = DownloadOutcome.OK,
                    detail = "via https GET",
                )
                is HttpGet.Result.NotHttps -> lastDetail = "not an https URL"
                is HttpGet.Result.Failed -> {
                    lastDetail = r.reason
                    A8sAndroid.log("Download ${ef.filename} over https failed: ${r.reason}")
                }
            }
        }
        val url = ef.storageUrls.first()
        val why = when {
            lastDetail.isNotEmpty() -> lastDetail
            services.isEmpty() -> "no storage services configured"
            else -> "all services failed for $url"
        }
        return DownloadResult(
            file = null,
            fallbackUrl = url,
            filename = ef.filename,
            outcome = DownloadOutcome.FAILED,
            detail = why,
        )
    }

    fun buildSmsBody(textContent: String, results: List<DownloadResult>): String {
        val extras = results.mapNotNull { r ->
            when {
                r.file != null -> null
                r.fallbackUrl != null -> r.fallbackUrl
                else -> null
            }
        }
        if (extras.isEmpty()) return textContent
        return textContent + "\n" + extras.joinToString("\n")
    }
}
