package com.a8s.android

import java.io.File

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
    ): List<DownloadResult> {
        destDir.mkdirs()
        return files.map { ef -> downloadOne(ef, services, destDir) }
    }

    private fun downloadOne(
        ef: EnvelopeFile,
        services: List<StorageService>,
        destDir: File,
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
        val dest = File(destDir, ef.filename)
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
                    A8sAndroid.log("Download ${ef.filename} from ${svc.id} failed: ${e.message}")
                }
            }
        }
        val url = ef.storageUrls.first()
        return DownloadResult(
            file = null,
            fallbackUrl = url,
            filename = ef.filename,
            outcome = DownloadOutcome.FAILED,
            detail = if (services.isEmpty()) "no storage services configured" else "all services failed for $url",
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
