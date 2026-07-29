package com.runelitetablet.logging

object ObservabilityEventCatalog {
    const val SCHEMA_VERSION = 1

    private val categories = setOf(
        "auth", "buffer", "cleanup", "cmd", "cpu_boost", "di", "directsurface",
        "display", "fd",
        "fd_tracker", "frame", "gl", "handoff", "host_vsync", "http",
        "hybrid_x11", "install", "ipc", "jank", "lifecycle", "native",
        "perf", "presentation", "script", "session", "state", "step",
        "surface", "surfacecontrolprobe", "thread", "ui", "unclassified",
        "verify", "xloriecomposite"
    )

    fun eventClass(tag: String): String {
        val normalized = tag
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "unclassified" }
        val declared = normalized.takeIf {
            it in categories || it.startsWith("native_")
        } ?: "unclassified"
        return "tablite.$declared"
    }

    fun isDeclared(eventClass: String): Boolean {
        val category = eventClass.removePrefix("tablite.")
        return eventClass.startsWith("tablite.") &&
            (category in categories || category.startsWith("native_"))
    }

    fun allEventClasses(): List<String> =
        categories.sorted().map { "tablite.$it" }
}
