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

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
