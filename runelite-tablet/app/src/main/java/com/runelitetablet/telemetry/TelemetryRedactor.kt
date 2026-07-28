package com.runelitetablet.telemetry

/**
 * Defense-in-depth redaction for text artifacts before host collection.
 * Capture code must still avoid collecting gameplay/chat/input content.
 */
object TelemetryRedactor {
    private val assignments = Regex(
        """(?i)\b(JX_(?:SESSION_ID|CHARACTER_ID|DISPLAY_NAME)|access_token|refresh_token)\s*[=:]\s*([^\s,"']+)"""
    )
    private val javaProperties = Regex(
        """(?i)(-D(?:JX_(?:SESSION_ID|CHARACTER_ID|DISPLAY_NAME)|[^=\s]*(?:token|secret|password)[^=\s]*)=)([^\s]+)"""
    )
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")

    fun redact(text: String): String = text
        .replace(assignments) { "${it.groupValues[1]}=***" }
        .replace(javaProperties) { "${it.groupValues[1]}***" }
        .replace(bearer, "Bearer ***")
}
