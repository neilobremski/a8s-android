package com.a8s.android

import java.io.File

/**
 * Safe destination for attachment bytes inside a per-message directory.
 *
 * Mirrors `apps/a8s/services/attachment_path.py` upstream. `filename` arrives
 * on the wire from whoever published the envelope, so it is a single path
 * segment or it is refused. Without this, `File(destDir, filename)` with a
 * name like `../../x` writes wherever the sender points.
 */
object AttachmentPath {

    data class Resolved(val file: File?, val reason: String)

    fun bundleFile(destDir: File, filename: String): Resolved {
        val name = filename.trim()
        if (name.isEmpty()) return Resolved(null, "missing filename")
        if (name != File(name).name || name == "." || name == "..") {
            return Resolved(null, "filename '$name' is not a basename")
        }
        val root = destDir.canonicalFile
        val dest = File(root, name).canonicalFile
        val within = dest == root ||
            dest.path.startsWith(root.path + File.separator)
        if (!within) {
            return Resolved(null, "path escapes the attachment directory")
        }
        return Resolved(dest, "")
    }
}
