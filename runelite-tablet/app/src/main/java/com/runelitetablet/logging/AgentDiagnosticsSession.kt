package com.runelitetablet.logging

import android.content.Context
import com.runelitetablet.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Serializable
data class AgentDiagnosticsSession(
    val schemaVersion: Int = 1,
    val runId: String,
    val token: String,
    val baseUrl: String,
    val createdAtUtc: String
) {
    fun authorize(value: String?): Boolean {
        val supplied = value
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.toByteArray(Charsets.UTF_8)
            ?: return false
        return constantTimeEquals(token.toByteArray(Charsets.UTF_8), supplied)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val SESSION_RELATIVE_PATH = "diagnostics/agent-session.json"
        private val json = Json { prettyPrint = true }

        fun create(
            context: Context,
            port: Int,
            nowUtc: String = java.time.Instant.now().toString()
        ): AgentDiagnosticsSession {
            check(BuildConfig.DEBUG) {
                "Agent diagnostics sessions are forbidden outside debug builds."
            }
            val random = ByteArray(32).also(SecureRandom()::nextBytes)
            val session = AgentDiagnosticsSession(
                runId = UUID.randomUUID().toString(),
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(random),
                baseUrl = "http://127.0.0.1:$port",
                createdAtUtc = nowUtc
            )
            val target = File(context.filesDir, SESSION_RELATIVE_PATH)
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(session))
            target.setReadable(false, false)
            target.setWritable(false, false)
            target.setReadable(true, true)
            target.setWritable(true, true)
            return session
        }

        internal fun constantTimeEquals(expected: ByteArray, actual: ByteArray): Boolean {
            var difference = expected.size xor actual.size
            val count = maxOf(expected.size, actual.size)
            for (index in 0 until count) {
                val left = if (index < expected.size) expected[index].toInt() else 0
                val right = if (index < actual.size) actual[index].toInt() else 0
                difference = difference or (left xor right)
            }
            return difference == 0
        }
    }
}
