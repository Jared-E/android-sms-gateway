package me.capcom.smsgateway.modules.localserver.domain.messages

data class MmsMessage(
    val subject: String?,           // Optional MMS subject
    val text: String?,              // Optional body text
    val attachments: List<Attachment>,
) {
    data class Attachment(
        val contentType: String,    // MIME type, e.g. "image/jpeg"
        val name: String?,          // Optional file name
        val data: String,           // Base64-encoded content
    )
}
