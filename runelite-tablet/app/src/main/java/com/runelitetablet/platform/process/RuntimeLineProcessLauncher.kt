package com.runelitetablet.platform.process

import com.runelitetablet.domain.process.LineProcess
import com.runelitetablet.domain.process.LineProcessLauncher
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Sole owner of raw process creation for line-oriented local diagnostics.
 */
class RuntimeLineProcessLauncher : LineProcessLauncher {
    override fun launch(command: List<String>): LineProcess {
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        return object : LineProcess {
            override fun readLine(): String? = reader.readLine()

            override fun close() {
                reader.close()
                process.destroy()
            }
        }
    }
}
