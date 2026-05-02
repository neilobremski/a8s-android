package com.a8s.android

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Crockford-base32 ULID generator matching the Python a8s implementation
 * (`apps/a8s/ulid.py`). 26 chars: 10 chars of 48-bit millisecond timestamp
 * + 16 chars of 80-bit CSPRNG randomness. Used to populate the `id` field
 * of every outbound MQTT envelope so the host's `_process_pending` ULID
 * dedup ring accepts it.
 *
 * Pure-stdlib so it stays unit-testable without an Android Context.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun new(): String = build(System.currentTimeMillis(), randomBytes())

    internal fun build(tsMs: Long, rand: ByteArray): String {
        require(rand.size == 10) { "rand must be 10 bytes (80 bits)" }
        val sb = StringBuilder(26)
        for (i in 9 downTo 0) {
            sb.append(ALPHABET[((tsMs ushr (i * 5)) and 0x1F).toInt()])
        }
        val big = BigInteger(1, rand)
        val mask = BigInteger.valueOf(0x1F)
        for (i in 15 downTo 0) {
            val bits = big.shiftRight(i * 5).and(mask).toInt()
            sb.append(ALPHABET[bits])
        }
        return sb.toString()
    }

    private fun randomBytes(): ByteArray = ByteArray(10).also { random.nextBytes(it) }
}
