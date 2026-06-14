package me.capcom.smsgateway.domain

sealed class MessageContent {
    data class Text(val text: String) : MessageContent() {
        override fun toString(): String {
            return text
        }
    }

    data class Data(val data: String, val port: UShort) : MessageContent() {
        override fun toString(): String {
            return "$data:$port"
        }
    }

    data class Multimedia(
        val subject: String?,
        val text: String?,
        val parts: List<Part>,
    ) : MessageContent() {
        /**
         * A single MMS attachment. [data] is Base64-encoded binary content.
         */
        data class Part(
            val contentType: String,
            val name: String?,
            val data: String,
        )

        override fun toString(): String {
            return "mms[subject=$subject, text=$text, parts=${parts.size}]"
        }
    }
}