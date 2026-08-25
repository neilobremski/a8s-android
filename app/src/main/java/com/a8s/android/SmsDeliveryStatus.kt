package com.a8s.android

enum class SmsDeliveryClass {
    POSITIVE,
    PENDING,
    NEGATIVE,
    UNDECODABLE,
}

/** Pure status-report classification; Android PDU decoding stays in the receiver layer. */
object SmsDeliveryStatus {

    fun classify(format: String?, rawStatus: Int): SmsDeliveryClass = when (format) {
        FORMAT_3GPP -> classify3gpp(rawStatus)
        FORMAT_3GPP2 -> classify3gpp2(rawStatus)
        else -> SmsDeliveryClass.UNDECODABLE
    }

    internal fun classify3gpp(rawStatus: Int): SmsDeliveryClass = when (rawStatus and 0xff) {
        in 0x00..0x1f -> SmsDeliveryClass.POSITIVE
        in 0x20..0x3f -> SmsDeliveryClass.PENDING
        else -> SmsDeliveryClass.NEGATIVE
    }

    internal fun classify3gpp2(rawStatus: Int): SmsDeliveryClass =
        when ((rawStatus ushr CDMA_ERROR_CLASS_SHIFT) and CDMA_ERROR_CLASS_MASK) {
            0 -> SmsDeliveryClass.POSITIVE
            2 -> SmsDeliveryClass.PENDING
            else -> SmsDeliveryClass.NEGATIVE
        }

    const val FORMAT_3GPP = "3gpp"
    const val FORMAT_3GPP2 = "3gpp2"
    private const val CDMA_ERROR_CLASS_SHIFT = 24
    private const val CDMA_ERROR_CLASS_MASK = 0x03
}
