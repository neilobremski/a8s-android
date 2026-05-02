package com.a8s.android

/**
 * One MQTT remote. Mirrors `apps/a8s/network.py`'s remote spec — the
 * `transport` field exists to keep room for future protocols (HTTPS
 * long-poll, peer-to-peer TCP) without breaking the shape.
 *
 * `username`/`password` are nullable: anonymous brokers exist.
 */
data class RemoteConfig(
    val transport: String = "mqtt",
    val broker: String,
    val topic: String,
    val username: String? = null,
    val password: String? = null,
)
