package com.a8s.android

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.json.JSONObject

data class EnvelopePublishResult(
    val envelopeId: String,
    val accepted: Int,
    val failed: Int,
) {
    val total: Int get() = accepted + failed
}

data class PublishMetadata(
    val envelopeId: String,
    val from: String,
    val to: String,
    val contentLength: Int,
)

/** Safe MQTT publication logging correlated by envelope ULID. */
object MqttPublishDiagnostics {

    fun metadata(payload: ByteArray): PublishMetadata = try {
        val json = JSONObject(String(payload))
        PublishMetadata(
            envelopeId = json.optString("id"),
            from = json.optString("from"),
            to = json.optString("to"),
            contentLength = json.optString("content").length,
        )
    } catch (_: Exception) {
        PublishMetadata("", "", "", 0)
    }

    fun accepted(remoteName: String, topic: String, meta: PublishMetadata) {
        A8sAndroid.log(
            "MQTT[$remoteName] publish accepted id=${shortId(meta.envelopeId)} " +
                "from=${meta.from} to=${meta.to} topic=$topic (${meta.contentLength} chars; awaiting ack)",
        )
        record(remoteName, meta, TransactionTrace.Status.PARTIAL, "client accepted publish; awaiting broker ack")
    }

    fun rejected(remoteName: String, meta: PublishMetadata, error: Throwable) {
        val reason = errorText(error)
        A8sAndroid.log("MQTT[$remoteName] publish rejected id=${shortId(meta.envelopeId)}: $reason")
        record(remoteName, meta, TransactionTrace.Status.FAIL, "publish rejected: $reason")
    }

    fun listener(remoteName: String, meta: PublishMetadata): IMqttActionListener =
        object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                A8sAndroid.log("MQTT[$remoteName] broker acknowledged id=${shortId(meta.envelopeId)}")
                record(remoteName, meta, TransactionTrace.Status.OK, "broker acknowledged publish")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                val reason = exception?.let(::errorText) ?: "unknown failure"
                A8sAndroid.log("MQTT[$remoteName] async publish failed id=${shortId(meta.envelopeId)}: $reason")
                record(remoteName, meta, TransactionTrace.Status.FAIL, "async publish failed: $reason")
            }
        }

    private fun record(
        remoteName: String,
        meta: PublishMetadata,
        status: TransactionTrace.Status,
        summary: String,
    ) {
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = meta.envelopeId,
                flow = "MQTT_OUT",
                status = status,
                from = meta.from,
                to = meta.to,
                summary = "$summary on $remoteName",
            ),
        )
    }

    private fun errorText(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: "no message"}"

    private fun shortId(id: String): String = id.take(8).ifEmpty { "?" }
}
