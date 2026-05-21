package com.a8s.android

import java.io.File

object FileDownloader {

    data class DownloadResult(val file: File?, val fallbackUrl: String?)

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
            return DownloadResult(null, null)
        }
        val dest = File(destDir, ef.filename)
        for (url in ef.storageUrls) {
            for (svc in services) {
                try {
                    if (svc.retrieve(url, dest)) {
                        return DownloadResult(dest, null)
                    }
                } catch (e: StorageException) {
                    A8sAndroid.log("Download ${ef.filename} from ${svc.id} failed: ${e.message}")
                }
            }
        }
        return DownloadResult(null, ef.storageUrls.first())
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
