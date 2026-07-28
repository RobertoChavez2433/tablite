package com.runelitetablet.setup

import android.content.Context
import com.runelitetablet.BuildConfig
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.setup.ScriptDeployer
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ScriptBatchTransport(
    val arguments: Array<String>,
    val stdin: String
)

internal fun buildScriptBatchTransport(
    scriptsDir: String,
    scripts: List<Pair<String, String>>
): ScriptBatchTransport {
    val program = buildString {
        append("mkdir -p ").append(scriptsDir).append(" && ")
        for ((name, content) in scripts) {
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(content.toByteArray(Charsets.UTF_8))
            append("echo '").append(encoded).append("' | base64 -d > ")
                .append(scriptsDir).append("/").append(name).append(" && ")
        }
        append("chmod +x")
        for ((name, _) in scripts) {
            append(" ").append(scriptsDir).append("/").append(name)
        }
    }
    return ScriptBatchTransport(arguments = arrayOf("-s"), stdin = program)
}

class ScriptManager(
    private val context: Context,
    private val commandRunner: CommandRunner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ScriptDeployer {

    companion object {
        private const val SCRIPTS_DIR = "${CommandRunner.TERMUX_HOME_PATH}/scripts"
        private const val CONFIGS_DIR = "${CommandRunner.TERMUX_HOME_PATH}/scripts/configs"
        private const val JARS_DIR = "${CommandRunner.TERMUX_HOME_PATH}/.rlt"
        private const val VERSION_MARKER = "$JARS_DIR/deployed-version"
        private val SCRIPT_NAMES = listOf(
            "install-proot.sh", "install-java.sh", "download-runelite.sh",
            "check-markers.sh", "check-x11-socket.sh", "launch-runelite.sh",
            "launch-runelite-native.sh",
            "patch-lwjgl-bionic.sh",
            "libbionic-compat.c",
            "perf-sampler.sh",
            "update-runelite.sh", "health-check.sh", "shutdown-session.sh",
            "detect-gpu.sh", "setup-gpu.sh", "setup-gpu-mali.sh",
            "monitor-cpu-affinity.sh", "benchmark-pipeline.sh",
            "profile-virgl-env.sh", "check-kernel-caps.sh",
            "test-server-zink.sh", "test-user-ns.sh"
        )
        private val CONFIG_NAMES = listOf("openbox-rc.xml")
        private val JAR_NAMES = listOf("rlawt-1.8-bionic.jar")

        // Process-wide caches keyed by the APK's VERSION_CODE. On APK update, VERSION_CODE
        // changes, so we compare against the on-device marker file and re-deploy when they
        // differ. Fixes session 72+ re-deploy bug where a stale @Volatile Boolean blocked
        // new assets from reaching $HOME/scripts/. See project memory
        // project_script_redeploy_vs_stale_apk.md.
        @Volatile private var scriptsDeployedVersion: Int = -1
        @Volatile private var configsDeployedVersion: Int = -1
        @Volatile private var jarsDeployedVersion: Int = -1
        /** Set once per process after reading the device-side version marker. */
        @Volatile private var deviceMarkerChecked: Boolean = false
    }

    /** Current APK VERSION_CODE — the single source of truth for "are deployed assets fresh?" */
    private val currentVersion: Int = BuildConfig.VERSION_CODE

    /** Cache script content after first APK asset read to avoid repeated decompression */
    private val scriptContentCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Reset deployment cache, e.g. after an error that may have left scripts in a bad state */
    override fun invalidateDeployCache() {
        scriptsDeployedVersion = -1
        configsDeployedVersion = -1
        jarsDeployedVersion = -1
        deviceMarkerChecked = false
        scriptContentCache.clear()
        AppLog.script("invalidateDeployCache: deployment and content caches cleared")
    }

    /**
     * On first deploy call after process start, read the on-device version marker. If the
     * marker's VERSION_CODE differs from our BuildConfig.VERSION_CODE (i.e. an APK update
     * happened between the last run and now), invalidate the in-memory cache so we force a
     * re-deploy of every asset category. Writes the fresh marker after the first successful
     * deploy (see writeVersionMarker).
     */
    private suspend fun ensureMarkerChecked() {
        if (deviceMarkerChecked) return
        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", "cat $VERSION_MARKER 2>/dev/null || echo MISSING"),
            background = true,
            timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
        )
        val marker = result.stdout?.trim().orEmpty()
        val onDeviceVersion = marker.toIntOrNull() ?: -1
        if (onDeviceVersion != currentVersion) {
            AppLog.script(
                "ensureMarkerChecked: APK version drift detected (onDevice=$onDeviceVersion " +
                    "current=$currentVersion) — forcing re-deploy of scripts/configs/jars"
            )
            scriptsDeployedVersion = -1
            configsDeployedVersion = -1
            jarsDeployedVersion = -1
        } else {
            AppLog.script("ensureMarkerChecked: on-device version $onDeviceVersion matches APK $currentVersion")
        }
        deviceMarkerChecked = true
    }

    /**
     * Write the on-device version marker. Called only after all three deploy categories
     * (scripts, configs, jars) have completed for the current VERSION_CODE in this process,
     * so a partial deploy (e.g. scripts succeeded but jars failed) doesn't falsely mark the
     * device as up-to-date.
     */
    private suspend fun maybeWriteVersionMarker() {
        if (scriptsDeployedVersion == currentVersion &&
            configsDeployedVersion == currentVersion &&
            jarsDeployedVersion == currentVersion) {
            val cmd = "mkdir -p $JARS_DIR && printf '%s' $currentVersion > $VERSION_MARKER"
            commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", cmd),
                background = true,
                timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
            )
            AppLog.script("maybeWriteVersionMarker: marker written version=$currentVersion")
        }
    }

    override suspend fun deployScripts(): Boolean {
        ensureMarkerChecked()
        if (scriptsDeployedVersion == currentVersion) {
            AppLog.script("deployScripts: skipping — already deployed for version $currentVersion")
            return true
        }
        AppLog.script("deployScripts: starting scriptCount=${SCRIPT_NAMES.size} scripts=${SCRIPT_NAMES}")

        // Batch all scripts into a single Termux IPC call to avoid 12 sequential roundtrips
        // (~60ms each = ~850ms serial → ~80ms batched).
        val success = deployScriptsBatched(SCRIPT_NAMES)
        if (!success) {
            AppLog.script("deployScripts: batched deployment failed, falling back to serial")
            // Serial fallback
            for (scriptName in SCRIPT_NAMES) {
                if (!deployScript(scriptName)) {
                    AppLog.script("deployScripts: halting — deployment failed for $scriptName")
                    return false
                }
            }
        }

        AppLog.script("deployScripts: all scripts deployed successfully version=$currentVersion")
        scriptsDeployedVersion = currentVersion
        maybeWriteVersionMarker()
        return true
    }

    /**
     * Deploy all scripts in a single Termux IPC call.
     *
     * The generated program is passed on stdin to `bash -s`, not as the argument to
     * `bash -c`. Android's process launcher rejects a large command argument with E2BIG
     * before bash starts; the current script set is already large enough to cross that
     * boundary once its contents are base64 encoded.
     */
    private suspend fun deployScriptsBatched(scriptNames: List<String>): Boolean {
        val startMs = System.currentTimeMillis()
        // Read all scripts from assets
        val scripts = scriptNames.map { name ->
            val content = scriptContentCache.getOrPut(name) {
                withContext(ioDispatcher) {
                    context.assets.open("scripts/$name").use {
                        it.bufferedReader().readText().replace("\r", "")
                    }
                }
            }
            name to content
        }

        val transport = buildScriptBatchTransport(SCRIPTS_DIR, scripts)

        val totalBytes = scripts.sumOf { it.second.length }
        AppLog.script(
            "deployScriptsBatched: ${scripts.size} scripts, $totalBytes bytes total, " +
                "${transport.stdin.length} bytes over stdin"
        )

        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = transport.arguments,
            background = true,
            stdin = transport.stdin,
            timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
        )
        val durationMs = System.currentTimeMillis() - startMs

        return if (result.isSuccess) {
            AppLog.script("deployScriptsBatched: success durationMs=$durationMs")
            true
        } else {
            AppLog.w("SCRIPT", "deployScriptsBatched: failed exitCode=${result.exitCode} error=${result.error} durationMs=$durationMs")
            false
        }
    }

    override suspend fun deployConfigs(): Boolean {
        ensureMarkerChecked()
        if (configsDeployedVersion == currentVersion) {
            AppLog.script("deployConfigs: skipping — already deployed for version $currentVersion")
            return true
        }
        AppLog.script("deployConfigs: starting configCount=${CONFIG_NAMES.size} configs=${CONFIG_NAMES}")
        for (configName in CONFIG_NAMES) {
            val success = deployConfig(configName)
            if (!success) {
                AppLog.script("deployConfigs: halting — deployment failed for $configName")
                return false
            }
        }
        AppLog.script("deployConfigs: all configs deployed successfully version=$currentVersion")
        configsDeployedVersion = currentVersion
        maybeWriteVersionMarker()
        return true
    }

    /**
     * Deploy JAR assets under $JARS_DIR. JARs are binary and typically larger than scripts;
     * we read bytes, base64-encode, and stream via stdin — same shape as deployScriptsBatched
     * but one jar per IPC call because each file is ~260KB and individual base64 transfers fit
     * comfortably inside Termux's RUN_COMMAND Bundle while multi-file batches risk the 1MB cap.
     */
    override suspend fun deployJars(): Boolean {
        ensureMarkerChecked()
        if (jarsDeployedVersion == currentVersion) {
            AppLog.script("deployJars: skipping — already deployed for version $currentVersion")
            return true
        }
        AppLog.script("deployJars: starting jarCount=${JAR_NAMES.size} jars=${JAR_NAMES}")
        for (jarName in JAR_NAMES) {
            if (!deployJar(jarName)) {
                AppLog.script("deployJars: halting — deployment failed for $jarName")
                return false
            }
        }
        AppLog.script("deployJars: all jars deployed successfully version=$currentVersion")
        jarsDeployedVersion = currentVersion
        maybeWriteVersionMarker()
        return true
    }

    /**
     * Deploy a single JAR asset by instructing Termux to extract it from our APK via
     * `unzip -p "$APK" assets/libs/<jar>`. APKs are world-readable at /data/app/ on Android
     * 10+, so Termux (UID u0_a164) can read our APK (UID u0_aXXX) without any permission
     * grant. This avoids Binder transaction size limits that would apply if we tried to
     * stream the ~260KB jar through the RUN_COMMAND intent extras.
     */
    private suspend fun deployJar(jarName: String): Boolean {
        val packageName = context.packageName
        // `pm path` emits `package:/path/to/base.apk` (and possibly split APKs — we only need
        // base.apk since assets live there). Strip the prefix and take the first entry.
        val deployCommand = buildString {
            append("set -e && ")
            append("APK=\$(pm path ").append(packageName).append(" | head -1 | sed 's/^package://') && ")
            append("mkdir -p ").append(JARS_DIR).append(" && ")
            // -o overwrites without prompting; -p pipes to stdout so we can redirect
            append("unzip -p \"\$APK\" assets/libs/").append(jarName)
            append(" > ").append(JARS_DIR).append("/").append(jarName)
        }
        AppLog.script("deployJar: name=$jarName cmd='$deployCommand'")

        val deployStartMs = System.currentTimeMillis()
        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", deployCommand),
            background = true,
            timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
        )
        val deployDurationMs = System.currentTimeMillis() - deployStartMs

        return if (result.isSuccess) {
            AppLog.script("deployJar: success name=$jarName durationMs=$deployDurationMs")
            true
        } else {
            AppLog.e(
                "SCRIPT",
                "deployJar: FAILED name=$jarName durationMs=$deployDurationMs " +
                    "exitCode=${result.exitCode} error=${result.error} " +
                    "stdout=${result.stdout} stderr=${result.stderr}"
            )
            false
        }
    }

    override fun getScriptPath(name: String): String = "$SCRIPTS_DIR/$name"

    override fun getJarPath(name: String): String = "$JARS_DIR/$name"

    private suspend fun deployScript(scriptName: String): Boolean {
        val assetReadStartMs = System.currentTimeMillis()
        val scriptContent = scriptContentCache.getOrPut(scriptName) {
            withContext(ioDispatcher) {
                context.assets.open("scripts/$scriptName").use {
                    // Strip \r to ensure LF-only line endings — CRLF breaks shebang on Termux
                    it.bufferedReader().readText().replace("\r", "")
                }
            }
        }
        val assetReadDurationMs = System.currentTimeMillis() - assetReadStartMs
        AppLog.script(
            "deployScript: assetRead name=$scriptName contentLen=${scriptContent.length} " +
                "assetReadDurationMs=$assetReadDurationMs (Dispatchers.IO)"
        )

        val deployCommand =
            "mkdir -p $SCRIPTS_DIR && cat > $SCRIPTS_DIR/$scriptName && chmod +x $SCRIPTS_DIR/$scriptName"
        AppLog.script("deployScript: name=$scriptName contentLenBytes=${scriptContent.length} deployCommand='$deployCommand'")

        val deployStartMs = System.currentTimeMillis()
        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", deployCommand),
            stdin = scriptContent,
            background = true,
            timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
        )
        val deployDurationMs = System.currentTimeMillis() - deployStartMs

        return if (result.isSuccess) {
            AppLog.script("deployScript: success name=$scriptName durationMs=$deployDurationMs")
            true
        } else {
            AppLog.e(
                "SCRIPT",
                "deployScript: FAILED name=$scriptName durationMs=$deployDurationMs " +
                    "exitCode=${result.exitCode} error=${result.error} " +
                    "stdout=${result.stdout} stderr=${result.stderr}"
            )
            false
        }
    }

    private suspend fun deployConfig(configName: String): Boolean {
        val assetReadStartMs = System.currentTimeMillis()
        val configContent = withContext(ioDispatcher) {
            context.assets.open("configs/$configName").use {
                it.bufferedReader().readText()
            }
        }
        val assetReadDurationMs = System.currentTimeMillis() - assetReadStartMs
        AppLog.script(
            "deployConfig: assetRead name=$configName contentLen=${configContent.length} " +
                "assetReadDurationMs=$assetReadDurationMs (Dispatchers.IO)"
        )

        // Configs are not executable — no chmod +x
        val deployCommand = "mkdir -p $CONFIGS_DIR && cat > $CONFIGS_DIR/$configName"
        AppLog.script("deployConfig: name=$configName contentLenBytes=${configContent.length} deployCommand='$deployCommand'")

        val deployStartMs = System.currentTimeMillis()
        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", deployCommand),
            stdin = configContent,
            background = true,
            timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
        )
        val deployDurationMs = System.currentTimeMillis() - deployStartMs

        return if (result.isSuccess) {
            AppLog.script("deployConfig: success name=$configName durationMs=$deployDurationMs")
            true
        } else {
            AppLog.e(
                "SCRIPT",
                "deployConfig: FAILED name=$configName durationMs=$deployDurationMs " +
                    "exitCode=${result.exitCode} error=${result.error} " +
                    "stdout=${result.stdout} stderr=${result.stderr}"
            )
            false
        }
    }
}
