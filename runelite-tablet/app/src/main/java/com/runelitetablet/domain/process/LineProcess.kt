package com.runelitetablet.domain.process

/**
 * Read-only line stream from an app-owned operating-system process.
 *
 * The domain contract keeps raw Runtime/ProcessBuilder ownership out of
 * consumers such as logging and telemetry.
 */
interface LineProcess : AutoCloseable {
    fun readLine(): String?
}

fun interface LineProcessLauncher {
    fun launch(command: List<String>): LineProcess
}
