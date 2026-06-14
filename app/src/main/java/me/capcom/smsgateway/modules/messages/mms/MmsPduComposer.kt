package me.capcom.smsgateway.modules.messages.mms

import java.io.ByteArrayOutputStream

/**
 * Minimal composer for the MMS `M-Send.req` PDU (OMA-MMS-ENC / WAP-230-WSP encoding).
 *
 * This is the outbound counterpart to [me.capcom.smsgateway.modules.receiver.parsers.MMSParser]
 * (which parses inbound `M-Notification-Ind` PDUs). The produced byte array is handed to
 * [android.telephony.SmsManager.sendMultimediaMessage], which does NOT require the app to be the
 * default SMS app.
 *
 * The body is encoded as `application/vnd.wap.multipart.mixed`, which needs no SMIL/start
 * parameters and is rendered by handsets as text + attachments.
 */
object MmsPduComposer {

    data class Part(
        /** MIME type, e.g. `image/jpeg`. */
        val contentType: String,
        /** Optional file name. */
        val name: String?,
        /** Raw (already Base64-decoded) content. */
        val data: ByteArray,
    )

    // --- MMS header field assigned numbers (high bit set on the wire) ---
    private const val FIELD_MESSAGE_TYPE = 0x0C
    private const val FIELD_TRANSACTION_ID = 0x18
    private const val FIELD_MMS_VERSION = 0x0D
    private const val FIELD_FROM = 0x09
    private const val FIELD_TO = 0x17
    private const val FIELD_SUBJECT = 0x16
    private const val FIELD_MESSAGE_CLASS = 0x0A
    private const val FIELD_DELIVERY_REPORT = 0x06
    private const val FIELD_CONTENT_TYPE = 0x04
    private const val FIELD_CONTENT_LOCATION = 0x0E

    // --- Values ---
    private const val MESSAGE_TYPE_SEND_REQ = 0x80          // m-send-req (128)
    private const val MMS_VERSION_1_2 = 0x12               // encoded as short-integer
    private const val FROM_INSERT_ADDRESS_TOKEN = 0x81      // 129
    private const val MESSAGE_CLASS_PERSONAL = 0x80         // 128
    private const val DELIVERY_REPORT_YES = 0x80            // 128
    private const val DELIVERY_REPORT_NO = 0x81             // 129

    // well-known multipart.mixed content type (0x23) as a short-integer
    private const val CONTENT_TYPE_MULTIPART_MIXED = 0xA3

    // well-known WSP parameter tokens
    private const val PARAM_CHARSET = 0x01
    private const val PARAM_NAME = 0x17

    // UTF-8 IANA MIBenum (106) as a short-integer
    private const val CHARSET_UTF8 = 0x6A

    private const val SHORT_LENGTH_MAX = 30
    private const val LENGTH_QUOTE = 0x1F

    /**
     * @param transactionId unique transaction id for this send
     * @param recipients normalized destination numbers
     * @param subject optional MMS subject
     * @param text optional body text (added as a `text/plain; charset=utf-8` part)
     * @param parts media attachments
     * @param deliveryReport request a delivery report from the MMSC
     */
    fun compose(
        transactionId: String,
        recipients: List<String>,
        subject: String?,
        text: String?,
        parts: List<Part>,
        deliveryReport: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // --- Headers ---
        appendShortValueField(out, FIELD_MESSAGE_TYPE, MESSAGE_TYPE_SEND_REQ)
        out.write(FIELD_TRANSACTION_ID or 0x80)
        appendTextString(out, transactionId)
        appendShortValueField(out, FIELD_MMS_VERSION, 0x80 or MMS_VERSION_1_2)

        // From: insert-address-token (MMSC fills in our address)
        out.write(FIELD_FROM or 0x80)
        out.write(1) // value-length
        out.write(FROM_INSERT_ADDRESS_TOKEN)

        // To: one header per recipient
        recipients.forEach { recipient ->
            out.write(FIELD_TO or 0x80)
            appendEncodedString(out, formatAddress(recipient))
        }

        if (!subject.isNullOrEmpty()) {
            out.write(FIELD_SUBJECT or 0x80)
            appendEncodedString(out, subject)
        }

        appendShortValueField(out, FIELD_MESSAGE_CLASS, MESSAGE_CLASS_PERSONAL)
        appendShortValueField(
            out,
            FIELD_DELIVERY_REPORT,
            if (deliveryReport) DELIVERY_REPORT_YES else DELIVERY_REPORT_NO
        )

        // Content-Type MUST be the last header before the body.
        out.write(FIELD_CONTENT_TYPE or 0x80)
        out.write(CONTENT_TYPE_MULTIPART_MIXED)

        // --- Multipart body ---
        val bodyParts = buildList {
            if (!text.isNullOrEmpty()) {
                add(
                    Part(
                        contentType = "text/plain",
                        name = null,
                        data = text.toByteArray(Charsets.UTF_8),
                    )
                )
            }
            addAll(parts)
        }

        appendUintvar(out, bodyParts.size.toLong())
        bodyParts.forEach { appendBodyEntry(out, it) }

        return out.toByteArray()
    }

    private fun appendBodyEntry(out: ByteArrayOutputStream, part: Part) {
        val isText = part.contentType.startsWith("text/", ignoreCase = true)

        val headers = ByteArrayOutputStream()
        appendPartContentType(headers, part.contentType, part.name, isText)
        if (!part.name.isNullOrEmpty()) {
            headers.write(FIELD_CONTENT_LOCATION or 0x80)
            appendTextString(headers, part.name)
        }
        val headerBytes = headers.toByteArray()

        appendUintvar(out, headerBytes.size.toLong())
        appendUintvar(out, part.data.size.toLong())
        out.write(headerBytes)
        out.write(part.data)
    }

    /** Content-type value (general form) used inside a multipart entry's header field. */
    private fun appendPartContentType(
        out: ByteArrayOutputStream,
        contentType: String,
        name: String?,
        withCharset: Boolean,
    ) {
        val media = textStringBytes(contentType)
        val params = ByteArrayOutputStream()
        if (withCharset) {
            params.write(PARAM_CHARSET or 0x80)
            params.write(CHARSET_UTF8 or 0x80)
        }
        if (!name.isNullOrEmpty()) {
            params.write(PARAM_NAME or 0x80)
            appendTextString(params, name)
        }
        val paramBytes = params.toByteArray()

        appendValueLength(out, (media.size + paramBytes.size).toLong())
        out.write(media)
        out.write(paramBytes)
    }

    private fun appendShortValueField(out: ByteArrayOutputStream, field: Int, value: Int) {
        out.write(field or 0x80)
        out.write(value)
    }

    private fun formatAddress(number: String): String {
        // PLMN (phone) address; emails would use a different suffix
        return if (number.contains('@')) number else "$number/TYPE=PLMN"
    }

    private fun appendEncodedString(out: ByteArrayOutputStream, value: String) {
        if (value.all { it.code in 0..127 }) {
            appendTextString(out, value)
            return
        }
        // charset form: value-length | charset (short-integer) | text-string
        val text = textStringBytes(value)
        appendValueLength(out, (1 + text.size).toLong())
        out.write(CHARSET_UTF8 or 0x80)
        out.write(text)
    }

    private fun appendTextString(out: ByteArrayOutputStream, value: String) {
        out.write(textStringBytes(value))
    }

    private fun textStringBytes(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream()
        // Quote if the first octet would be interpreted as a length/token (>= 0x80)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) {
            buf.write(0x7F)
        }
        buf.write(bytes)
        buf.write(0x00)
        return buf.toByteArray()
    }

    private fun appendValueLength(out: ByteArrayOutputStream, length: Long) {
        if (length <= SHORT_LENGTH_MAX) {
            out.write(length.toInt())
        } else {
            out.write(LENGTH_QUOTE)
            appendUintvar(out, length)
        }
    }

    private fun appendUintvar(out: ByteArrayOutputStream, value: Long) {
        if (value < 0x80) {
            out.write(value.toInt())
            return
        }
        // Build 7-bit groups, most significant first, continuation bit on all but the last.
        val groups = ArrayDeque<Int>()
        var v = value
        while (v > 0) {
            groups.addFirst((v and 0x7FL).toInt())
            v = v shr 7
        }
        for (i in groups.indices) {
            val isLast = i == groups.size - 1
            out.write(if (isLast) groups[i] else groups[i] or 0x80)
        }
    }
}
