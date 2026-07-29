package com.runelitetablet.logging

import java.util.ArrayDeque

data class RetainedDiagnosticEvent(
    val cursor: Long,
    val encodedJson: String,
    val encodedBytes: Int
)

data class DiagnosticLogPage(
    val runId: String,
    val requestedAfter: Long,
    val entries: List<String>,
    val nextCursor: Long,
    val oldestCursor: Long?,
    val newestCursor: Long?,
    val gapDetected: Boolean,
    val evictedCount: Long,
    val droppedCount: Long,
    val truncated: Boolean
)

class DiagnosticLogStore(
    private val runId: String,
    private val maxEntries: Int = 30_000,
    private val maxBytes: Int = 32 * 1024 * 1024,
    private val maxEntryBytes: Int = 64 * 1024,
    private val maxPageBytes: Int = 2 * 1024 * 1024
) {
    private val retained = ArrayDeque<RetainedDiagnosticEvent>()
    private var retainedBytes = 0
    private var nextCursor = 1L
    private var evicted = 0L
    private var dropped = 0L

    init {
        require(maxEntries in 1..30_000)
        require(maxBytes > 0)
        require(maxEntryBytes in 1..64 * 1024)
        require(maxPageBytes > 0)
    }

    @Synchronized
    fun append(sourceJson: String): RetainedDiagnosticEvent? {
        val cursor = nextCursor++
        val encoded = attachCursor(sourceJson, cursor)
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        if (bytes > maxEntryBytes || bytes > maxBytes) {
            dropped++
            return null
        }
        while (retained.size >= maxEntries || retainedBytes + bytes > maxBytes) {
            val removed = retained.removeFirst()
            retainedBytes -= removed.encodedBytes
            evicted++
        }
        return RetainedDiagnosticEvent(cursor, encoded, bytes).also {
            retained.addLast(it)
            retainedBytes += bytes
        }
    }

    @Synchronized
    fun query(after: Long, limit: Int): DiagnosticLogPage {
        require(after >= 0)
        require(limit in 1..500)
        val oldest = retained.firstOrNull()?.cursor
        val newest = retained.lastOrNull()?.cursor
        val gap = oldest != null && after < oldest - 1
        val selected = mutableListOf<String>()
        var pageBytes = 0
        var truncated = false
        for (event in retained) {
            if (event.cursor <= after) continue
            if (selected.size >= limit || pageBytes + event.encodedBytes > maxPageBytes) {
                truncated = true
                break
            }
            selected += event.encodedJson
            pageBytes += event.encodedBytes
        }
        val next = selected.lastOrNull()
            ?.let(::extractCursor)
            ?: after
        return DiagnosticLogPage(
            runId = runId,
            requestedAfter = after,
            entries = selected,
            nextCursor = next,
            oldestCursor = oldest,
            newestCursor = newest,
            gapDetected = gap,
            evictedCount = evicted,
            droppedCount = dropped,
            truncated = truncated
        )
    }

    @Synchronized
    fun stats(): Map<String, Long> = mapOf(
        "retainedEntries" to retained.size.toLong(),
        "retainedBytes" to retainedBytes.toLong(),
        "evictedCount" to evicted,
        "droppedCount" to dropped,
        "nextCursor" to nextCursor
    )

    private fun attachCursor(source: String, cursor: Long): String {
        val trimmed = source.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Diagnostic event must be a JSON object."
        }
        return """{"cursor":$cursor,${trimmed.substring(1)}"""
    }

    private fun extractCursor(json: String): Long =
        Regex(""""cursor":(\d+)""").find(json)?.groupValues?.get(1)?.toLong()
            ?: error("Retained diagnostic event lost its cursor.")
}
