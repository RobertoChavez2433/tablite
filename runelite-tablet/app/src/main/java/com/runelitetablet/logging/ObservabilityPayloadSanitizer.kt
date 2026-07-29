package com.runelitetablet.logging

object ObservabilityPayloadSanitizer {
    private const val MAX_MESSAGE_CHARS = 4_096
    private val email = Regex(
        """[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""
    )
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")
    private val sensitiveAssignment = Regex(
        """(?i)\b(token|password|secret|authorization|access[_-]?key|refresh[_-]?token)=([^\s&]+)"""
    )
    private val chatOrInput = Regex(
        """(?i)\b(chat(?:Text|Message)?|typed(?:Text|Keys)?|input(?:Text|Value)?)=([^\s]+)"""
    )

    fun scrubString(value: String): String {
        val scrubbed = value
            .replace(bearer, "Bearer [redacted]")
            .replace(jwt, "[redacted-jwt]")
            .replace(email, "[redacted-email]")
            .replace(sensitiveAssignment) { "${it.groupValues[1]}=[redacted]" }
            .replace(chatOrInput) { "${it.groupValues[1]}=[redacted]" }
        return if (scrubbed.length <= MAX_MESSAGE_CHARS) {
            scrubbed
        } else {
            scrubbed.take(MAX_MESSAGE_CHARS) + "...[truncated]"
        }
    }
}
