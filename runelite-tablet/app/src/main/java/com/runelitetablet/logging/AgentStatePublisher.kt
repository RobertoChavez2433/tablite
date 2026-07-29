package com.runelitetablet.logging

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class AgentStateSubscription internal constructor(
    private val capacity: Int,
    private val onClose: (AgentStateSubscription) -> Unit
) {
    private val queue = ArrayBlockingQueue<String>(capacity)
    private val closed = AtomicBoolean(false)

    fun enqueue(event: String) {
        if (closed.get()) return
        if (!queue.offer(event)) {
            close()
        }
    }

    fun next(timeoutMs: Long): String? =
        if (closed.get()) null else queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        onClose(this)
    }

    fun isClosed(): Boolean = closed.get()
}

class AgentStatePublisher(
    private val snapshotProvider: () -> AgentStateSnapshotV1,
    private val historyCapacity: Int = 256,
    private val maxSubscribers: Int = 8,
    private val subscriberQueueCapacity: Int = 16
) {
    private val json = Json { encodeDefaults = true }
    private val generation = UUID.randomUUID().toString()
    private val history = ArrayDeque<Pair<Long, String>>()
    private val subscribers = CopyOnWriteArrayList<AgentStateSubscription>()
    private var revision = 0L
    private var fingerprint: String? = null
    private var currentSnapshot: AgentStateSnapshotV1? = null

    val subscriberCount: Int get() = subscribers.size
    fun generation(): String = generation

    @Synchronized
    fun observeNow(): AgentStateSnapshotV1 {
        val snapshot = snapshotProvider()
        val stable = json.encodeToString(snapshot.stableCopy())
        currentSnapshot = snapshot
        if (stable == fingerprint) return snapshot
        fingerprint = stable
        revision++
        val event = encodeStateEvent("state", revision, snapshot)
        history.addLast(revision to event)
        while (history.size > historyCapacity) history.removeFirst()
        subscribers.forEach { it.enqueue(event) }
        return snapshot
    }

    @Synchronized
    fun subscribe(lastEventId: String?): AgentStateSubscription {
        check(subscribers.size < maxSubscribers) {
            "state_subscriber_limit_reached"
        }
        val snapshot = observeNow()
        lateinit var subscription: AgentStateSubscription
        subscription = AgentStateSubscription(subscriberQueueCapacity) {
            subscribers.remove(it)
        }
        subscribers += subscription
        val resume = parseEventId(lastEventId)
        when {
            lastEventId.isNullOrBlank() -> subscription.enqueue(
                history.lastOrNull()?.second ?: encodeStateEvent("state", revision, snapshot)
            )
            resume == null -> subscription.enqueue(
                encodeStateEvent("reset", revision, snapshot, "invalid_event_id")
            )
            resume.first != generation -> subscription.enqueue(
                encodeStateEvent("reset", revision, snapshot, "generation_changed")
            )
            history.isNotEmpty() && resume.second < history.first().first - 1 ->
                subscription.enqueue(
                    encodeStateEvent("reset", revision, snapshot, "history_evicted")
                )
            resume.second > revision -> subscription.enqueue(
                encodeStateEvent("reset", revision, snapshot, "revision_ahead")
            )
            else -> history.filter { it.first > resume.second }
                .forEach { subscription.enqueue(it.second) }
        }
        return subscription
    }

    fun heartbeat() {
        val data =
            """{"protocolVersion":1,"generation":"$generation","emittedAtUtc":"${Instant.now()}"}"""
        val event = "event: heartbeat\ndata: $data\n\n"
        subscribers.forEach { it.enqueue(event) }
    }

    private fun encodeStateEvent(
        kind: String,
        revision: Long,
        snapshot: AgentStateSnapshotV1,
        reason: String? = null
    ): String {
        val payload = buildString {
            append("""{"protocolVersion":1,"generation":"$generation","revision":$revision,"emittedAtUtc":"${Instant.now()}","snapshot":""")
            append(json.encodeToString(snapshot))
            if (reason != null) append(""","reason":"$reason"""")
            append("}")
        }
        return "id: $generation:$revision\nevent: $kind\ndata: $payload\n\n"
    }

    private fun parseEventId(value: String?): Pair<String, Long>? {
        if (value.isNullOrBlank()) return null
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return null
        val parsedRevision = value.substring(separator + 1).toLongOrNull()
            ?: return null
        if (parsedRevision < 0) return null
        return value.substring(0, separator) to parsedRevision
    }
}
