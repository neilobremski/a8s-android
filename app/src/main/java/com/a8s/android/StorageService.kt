package com.a8s.android

import java.io.File

/**
 * Pluggable cross-cluster file backend.
 *
 * Mirrors the contract in `apps/a8s/services/__init__.py`. Sender-side
 * `store(file)` uploads bytes and returns a URL the receiver can fetch.
 * Receiver-side `retrieve(url, dest)` downloads to `dest`. A service
 * returns `false` from `retrieve` when the URL doesn't belong to it
 * (so a multi-service install can route mixed URLs by trying each
 * service in turn). Real failures throw [StorageException].
 *
 * Stateless — no start/stop lifecycle. One instance per `services`
 * entry in the JSON config, shared between the upload + download
 * paths.
 */
interface StorageService {
    /** Stable identifier from the `services` map key. */
    val id: String

    /**
     * True when [store] returns a URL any recipient can GET with no
     * credentials of its own.
     *
     * This phone cannot send bytes — MMS needs the default SMS app role — so a
     * public URL is the only way an attachment reaches anyone. An upload that
     * produces no public URL has not delivered the file, however well it
     * succeeded, and the sender must be told rather than shipping a filename
     * nobody can fetch.
     */
    val producesPublicUrl: Boolean get() = true

    /**
     * Upload order, low first. Ties keep config order. A recipient tries the
     * URLs in the order the envelope lists them, so this is what "preferred
     * storage" means in practice.
     */
    val preference: Int get() = PREFERENCE_DEFAULT

    /** Upload a local file's bytes; return a URL the receiver can fetch.
     *  Throws [StorageException] on failure. */
    fun store(file: File): String

    /** Download a URL into `dest`. Returns `true` on success, `false`
     *  when the URL isn't recognized as belonging to this service.
     *  Throws [StorageException] on transport failures (so a caller
     *  iterating over multiple services can distinguish "not mine"
     *  from "mine but broken"). */
    fun retrieve(url: String, dest: File): Boolean
}

/** A store you run yourself, preferred over a public paste host. */
const val PREFERENCE_OWN_STORE: Int = 10
const val PREFERENCE_DEFAULT: Int = 50

/** Wire constant for an attachment the recipient cannot fetch. Matches
 *  `apps/a8s/services/attachment_errors.py` upstream. */
const val ATTACHMENT_UNAVAILABLE: String = "ATTACHMENT_UNAVAILABLE"

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
