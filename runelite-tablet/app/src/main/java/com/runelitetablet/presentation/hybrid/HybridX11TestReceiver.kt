package com.runelitetablet.presentation.hybrid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.auth.LaunchEnvDeployer
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only receiver for exercising the hybrid same-path bridge from adb.
 *
 * This lets adb trigger the test indirectly through our app process, which
 * already has com.termux.permission.RUN_COMMAND.
 */
class HybridX11TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val container = (context.applicationContext as RuneLiteTabletApp).container
        val runner = container.termux.commandRunner
        val overridePackage = intent?.getStringExtra(EXTRA_OVERRIDE_PACKAGE)?.trim().orEmpty()
        val stopExistingX11 = """
            for proc in /proc/[0-9]*; do
                pid=${'$'}{proc#/proc/}
                [ "${'$'}pid" = "${'$'}$" ] && continue
                [ -r "${'$'}proc/cmdline" ] || continue
                cmdline=${'$'}(tr '\0' ' ' < "${'$'}proc/cmdline" 2>/dev/null || true)
                case "${'$'}cmdline" in
                    *"com.termux.x11.Loader"*|*"com.termux.x11.CmdEntryPoint"*|*"termux-x11 :"*)
                        kill "${'$'}pid" 2>/dev/null || true
                        ;;
                esac
            done
        """.trimIndent()

        when (action) {
            ACTION_OPEN_HOST -> {
                val hostIntent = Intent(context, HybridX11HostActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(hostIntent)
                AppLog.step("hybrid_x11", "HybridX11TestReceiver: opened hybrid host activity")
            }

            ACTION_START_TEST -> {
                val pendingResult = goAsync()
                HybridX11Bridge.clear("start_requested")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val overrideSetup =
                            if (overridePackage.isNotEmpty()) {
                                """
                                export TERMUX_X11_OVERRIDE_PACKAGE=$overridePackage
                                echo "[shell] override=${'$'}TERMUX_X11_OVERRIDE_PACKAGE"
                                """.trimIndent()
                            } else {
                                """
                                unset TERMUX_X11_OVERRIDE_PACKAGE
                                echo "[shell] override=<stock>"
                                """.trimIndent()
                            }
                        val result = runner.execute(
                            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                            arguments = arrayOf(
                                "-lc",
                                """
                                echo "[shell] start requested"
                                $stopExistingX11
                                echo "[shell] old x11 killed"
                                sleep 1
                                export PREFIX=${TermuxCommandRunner.TERMUX_BIN_PATH.removeSuffix("/bin")}
                                export TMPDIR="${'$'}PREFIX/tmp"
                                mkdir -p "${'$'}TMPDIR/.X11-unix"
                                $overrideSetup
                                echo "[shell] PREFIX=${'$'}PREFIX TMPDIR=${'$'}TMPDIR"
                                echo "[shell] termux_x11_path=${'$'}(command -v termux-x11 || echo missing)"
                                echo "[shell] package_path=${'$'}(pm path com.termux.x11 2>/dev/null || echo missing)"
                                ${TermuxCommandRunner.TERMUX_BIN_PATH}/termux-x11 :0 > ${TermuxCommandRunner.TERMUX_HOME_PATH}/hybrid-x11-start.log 2>&1 &
                                child=${'$'}!
                                echo "[shell] child_pid=${'$'}child"
                                sleep 3
                                echo "[shell] tmpdir socket listing:"
                                ls -la "${'$'}TMPDIR/.X11-unix" 2>&1 || true
                                echo "[shell] /tmp socket listing:"
                                ls -la /tmp/.X11-unix 2>&1 || true
                                echo "[shell] ps child:"
                                ps -A | grep -F "${'$'}child" || true
                                echo "[shell] ps x11:"
                                ps -A | grep -E 'com.termux.x11.Loader|termux-x11' || true
                                """.trimIndent()
                            ),
                            background = true,
                            timeoutMs = 15_000L
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: start test result exitCode=${result.exitCode} " +
                                "error=${result.error} stdout=${result.stdout ?: "<none>"} stderr=${result.stderr ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: start test failed: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_STOP_TEST -> {
                val launched = runner.launchBackground(
                    commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                    arguments = arrayOf(
                        "-lc",
                        """
                        $stopExistingX11
                        """.trimIndent()
                    )
                )
                HybridX11Bridge.clear("stop_requested")
                AppLog.step("hybrid_x11", "HybridX11TestReceiver: stop test dispatched launched=$launched")
            }

            ACTION_PROBE_FDS -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val service = HybridX11Bridge.currentService()
                        if (service == null) {
                            AppLog.w("HYBRID_X11", "HybridX11TestReceiver: probe requested but no binder is attached")
                            return@launch
                        }

                        val xConnection = service.getXConnection()
                        val logcat = service.getLogcatOutput()
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: probe fds xConnection=${xConnection != null} logcat=${logcat != null}"
                        )
                        xConnection?.close()
                        logcat?.close()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: probe failed: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_RUN_CLIENT -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val mode = intent.getStringExtra(EXTRA_CLIENT_MODE) ?: CLIENT_MODE_INSPECT
                    try {
                        val result = runner.execute(
                            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                            arguments = arrayOf("-lc", buildHybridClientScript(mode)),
                            background = true,
                            timeoutMs = 30_000L
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: run client mode=$mode exitCode=${result.exitCode} " +
                                "error=${result.error} stdout=${result.stdout ?: "<none>"} stderr=${result.stderr ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: run client failed mode=$mode: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_START_AND_RUN_CLIENT -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val mode = intent.getStringExtra(EXTRA_CLIENT_MODE) ?: CLIENT_MODE_INSPECT
                    val probeShape = intent.getStringExtra(EXTRA_PROBE_SHAPE) ?: PROBE_SHAPE_DELAYED
                    val presentationVariant =
                        intent.getStringExtra(EXTRA_PRESENTATION_VARIANT)?.trim().orEmpty()
                            .ifEmpty {
                                if (overridePackage.isNotEmpty()) PRESENTATION_VARIANT_HYBRID else PRESENTATION_VARIANT_STOCK
                            }
                    try {
                        val result = runner.execute(
                            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                            arguments = arrayOf(
                                "-lc",
                                buildCombinedProbeScript(
                                    stopExistingX11 = stopExistingX11,
                                    presentationVariant = presentationVariant,
                                    mode = mode,
                                    probeShape = probeShape
                                )
                            ),
                            background = true,
                            timeoutMs = 60_000L
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: combined probe mode=$mode shape=$probeShape variant=$presentationVariant override=" +
                                "${if (overridePackage.isNotEmpty()) overridePackage else "<stock>"} " +
                                "exitCode=${result.exitCode} error=${result.error} " +
                                "stdout=${result.stdout ?: "<none>"} stderr=${result.stderr ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: combined probe failed mode=$mode: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_RUN_REAL_LAUNCHER -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val presentationVariant =
                            intent.getStringExtra(EXTRA_PRESENTATION_VARIANT)?.trim().orEmpty()
                                .ifEmpty { PRESENTATION_VARIANT_HYBRID }
                        val gpuDisplayResolutionOverride =
                            intent.getStringExtra(EXTRA_GPU_DISPLAY_RESOLUTION)?.trim().orEmpty()
                        val runeliteScaleOverride =
                            intent.getStringExtra(EXTRA_RUNELITE_SCALE)?.trim().orEmpty()
                        val java2dProfileOverride =
                            intent.getStringExtra(EXTRA_JAVA2D_PROFILE)?.trim().orEmpty()
                        val mesaProfileOverride =
                            intent.getStringExtra(EXTRA_MESA_PROFILE)?.trim().orEmpty()
                        val virglServerProfileOverride =
                            intent.getStringExtra(EXTRA_VIRGL_SERVER_PROFILE)?.trim().orEmpty()
                        val runeliteLaunchModeOverride =
                            intent.getStringExtra(EXTRA_RUNELITE_LAUNCH_MODE)?.trim().orEmpty()
                        val scriptManager = container.setup.scriptManager
                        val deployed = scriptManager.deployScripts()
                        if (!deployed) {
                            AppLog.e("HYBRID_X11", "HybridX11TestReceiver: failed to deploy scripts for real launcher test")
                            return@launch
                        }

                        val launchArguments = mutableListOf("--x11-presentation", presentationVariant)
                        if (gpuDisplayResolutionOverride.isNotEmpty()) {
                            launchArguments += listOf("--gpu-display-resolution", gpuDisplayResolutionOverride)
                        }
                        if (runeliteScaleOverride.isNotEmpty()) {
                            launchArguments += listOf("--runelite-scale", runeliteScaleOverride)
                        }
                        if (java2dProfileOverride.isNotEmpty()) {
                            launchArguments += listOf("--java2d-profile", java2dProfileOverride)
                        }
                        if (mesaProfileOverride.isNotEmpty()) {
                            launchArguments += listOf("--mesa-profile", mesaProfileOverride)
                        }
                        if (virglServerProfileOverride.isNotEmpty()) {
                            launchArguments += listOf("--virgl-server-profile", virglServerProfileOverride)
                        }
                        if (runeliteLaunchModeOverride.isNotEmpty()) {
                            launchArguments += listOf("--runelite-launch-mode", runeliteLaunchModeOverride)
                        }
                        val envFilePath = LaunchEnvDeployer.deployToTermuxHome(
                            credentialStore = container.auth.credentialStore,
                            commandRunner = runner
                        )
                        if (envFilePath != null) {
                            launchArguments += envFilePath
                        }
                        val launched = runner.launchBackground(
                            commandPath = scriptManager.getScriptPath("launch-runelite.sh"),
                            arguments = launchArguments.toTypedArray()
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: real launcher dispatched launched=$launched variant=$presentationVariant " +
                                "gpuDisplayResolutionOverride=${if (gpuDisplayResolutionOverride.isNotEmpty()) gpuDisplayResolutionOverride else "<default>"} " +
                                "runeliteScaleOverride=${if (runeliteScaleOverride.isNotEmpty()) runeliteScaleOverride else "<default>"} " +
                                "java2dProfile=${if (java2dProfileOverride.isNotEmpty()) java2dProfileOverride else "<default>"} " +
                                "mesaProfile=${if (mesaProfileOverride.isNotEmpty()) mesaProfileOverride else "<default>"} " +
                                "virglServerProfile=${if (virglServerProfileOverride.isNotEmpty()) virglServerProfileOverride else "<default>"} " +
                                "runeliteLaunchMode=${if (runeliteLaunchModeOverride.isNotEmpty()) runeliteLaunchModeOverride else "<default>"} " +
                                "envFile=${envFilePath ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: real launcher failed: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_DUMP_REAL_LAUNCH_STATE -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = runner.execute(
                            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                            arguments = arrayOf(
                                "-lc",
                                """
                                CURRENT_FILE="${'$'}PREFIX/tmp/rlt-session/current"
                                echo "=== runelite-launch.log ==="
                                tail -n 200 "${'$'}HOME/runelite-launch.log" 2>&1 || true
                                echo
                                echo "=== internal-x11-start.log ==="
                                tail -n 200 "${'$'}HOME/internal-x11-start.log" 2>&1 || true
                                echo
                                echo "=== session current ==="
                                cat "${'$'}CURRENT_FILE" 2>/dev/null || true
                                echo
                                if [ -f "${'$'}CURRENT_FILE" ]; then
                                    SESSION_DIR="$(cat "${'$'}CURRENT_FILE" 2>/dev/null)"
                                    if [ -n "${'$'}SESSION_DIR" ] && [ -d "${'$'}SESSION_DIR" ]; then
                                        echo "=== session dir ${'$'}SESSION_DIR ==="
                                        ls -la "${'$'}SESSION_DIR" 2>&1 || true
                                        for key in state x11.variant x11.server x11.override x11.pid x11.internal.log virgl.pid launcher.pid; do
                                            if [ -f "${'$'}SESSION_DIR/${'$'}key" ]; then
                                                echo "--- ${'$'}key ---"
                                                cat "${'$'}SESSION_DIR/${'$'}key" 2>/dev/null || true
                                            fi
                                        done
                                    fi
                                fi
                                echo
                                echo "=== process snapshot ==="
                                toybox ps -A -o PID,NAME,ARGS 2>/dev/null || ps -A 2>/dev/null || true
                                """.trimIndent()
                            ),
                            background = true,
                            timeoutMs = 30_000L
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: dump launch state exitCode=${result.exitCode} " +
                                "error=${result.error} stdout=${result.stdout ?: "<none>"} stderr=${result.stderr ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: dump launch state failed: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_SHUTDOWN_REAL_LAUNCHER -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val scriptManager = container.setup.scriptManager
                        val deployed = scriptManager.deployScripts()
                        if (!deployed) {
                            AppLog.e("HYBRID_X11", "HybridX11TestReceiver: failed to deploy scripts for shutdown test")
                            return@launch
                        }

                        val result = runner.execute(
                            commandPath = scriptManager.getScriptPath("shutdown-session.sh"),
                            background = true,
                            timeoutMs = 20_000L
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: shutdown launcher exitCode=${result.exitCode} " +
                                "error=${result.error} stdout=${result.stdout ?: "<none>"} stderr=${result.stderr ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: shutdown launcher failed: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun buildCombinedProbeScript(
        stopExistingX11: String,
        presentationVariant: String,
        mode: String,
        probeShape: String
    ): String {
        val safePresentationVariant =
            when (presentationVariant) {
                PRESENTATION_VARIANT_HYBRID,
                PRESENTATION_VARIANT_INTERNAL_HYBRID,
                PRESENTATION_VARIANT_STOCK -> presentationVariant
                else -> PRESENTATION_VARIANT_STOCK
            }
        val overrideSetup =
            if (safePresentationVariant == PRESENTATION_VARIANT_HYBRID) {
                """
                export TERMUX_X11_OVERRIDE_PACKAGE=com.runelitetablet
                echo "[shell] override=${'$'}TERMUX_X11_OVERRIDE_PACKAGE"
                """.trimIndent()
            } else {
                """
                unset TERMUX_X11_OVERRIDE_PACKAGE
                echo "[shell] override=<stock>"
                """.trimIndent()
            }
        val activityComponent =
            if (safePresentationVariant == PRESENTATION_VARIANT_STOCK) {
                "com.termux.x11/.MainActivity"
            } else {
                "com.runelitetablet/.presentation.hybrid.HybridX11HostActivity"
            }
        val x11LaunchBlock =
            if (safePresentationVariant == PRESENTATION_VARIANT_INTERNAL_HYBRID) {
                """
                APP_APK_PATH="${'$'}(pm path com.runelitetablet 2>/dev/null | head -n 1 | sed 's/^package://')"
                if [ -z "${'$'}APP_APK_PATH" ]; then
                    echo "[shell] failed to resolve com.runelitetablet base.apk"
                    exit 1
                fi
                : > ${TermuxCommandRunner.TERMUX_HOME_PATH}/hybrid-x11-start.log
                env CLASSPATH="${'$'}APP_APK_PATH" /system/bin/app_process -Xnoimage-dex2oat / \
                    --nice-name="termux-x11 com.runelitetablet :0" com.termux.x11.CmdEntryPoint :0 \
                    > ${TermuxCommandRunner.TERMUX_HOME_PATH}/hybrid-x11-start.log 2>&1 &
                """.trimIndent()
            } else {
                "${TermuxCommandRunner.TERMUX_BIN_PATH}/termux-x11 :0 > ${TermuxCommandRunner.TERMUX_HOME_PATH}/hybrid-x11-start.log 2>&1 &"
            }
        val activityLaunch =
            """
            echo "[shell] launching activity $activityComponent"
            am start --activity-single-top --activity-clear-top -n "$activityComponent" >/dev/null 2>&1 || true
            """.trimIndent()
        val shape = if (probeShape == PROBE_SHAPE_LAUNCHER_FAITHFUL) PROBE_SHAPE_LAUNCHER_FAITHFUL else PROBE_SHAPE_DELAYED
        val preClientBlock =
            if (shape == PROBE_SHAPE_LAUNCHER_FAITHFUL) {
                """
                SOCKET_READY=0
                for i in $(seq 1 60); do
                    if [ -e "${'$'}TMPDIR/.X11-unix/X0" ]; then
                        SOCKET_READY=1
                        echo "[shell] socket-ready attempt=${'$'}i"
                        ls -la "${'$'}TMPDIR/.X11-unix" 2>&1 || true
                        break
                    fi
                    sleep 0.2
                done
                if [ "${'$'}SOCKET_READY" -ne 1 ]; then
                    echo "[shell] socket never appeared"
                    ls -la "${'$'}TMPDIR/.X11-unix" 2>&1 || true
                    exit 1
                fi
                $activityLaunch
                ${buildHybridClientScript(mode)}
                """.trimIndent()
            } else {
                """
                sleep 3
                $activityLaunch
                sleep 6
                ${buildHybridClientScript(mode)}
                """.trimIndent()
            }

        return """
            echo "[shell] combined probe start"
            echo "[shell] probe_shape=$shape"
            $stopExistingX11
            sleep 1
            export PREFIX=${TermuxCommandRunner.TERMUX_BIN_PATH.removeSuffix("/bin")}
            export TMPDIR="${'$'}PREFIX/tmp"
            mkdir -p "${'$'}TMPDIR/.X11-unix"
            echo "[shell] presentation_variant=$safePresentationVariant"
            $overrideSetup
            $x11LaunchBlock
            X11_PID=${'$'}!
            echo "[shell] child_pid=${'$'}X11_PID"
            $preClientBlock
        """.trimIndent()
    }

    private fun buildHybridClientScript(mode: String): String {
        val safeMode = when (mode) {
            CLIENT_MODE_INSPECT,
            CLIENT_MODE_XEV,
            CLIENT_MODE_GLXGEARS_WINDOWED,
            CLIENT_MODE_GLXGEARS_FULLSCREEN,
            CLIENT_MODE_GLXGEARS_VIRGL_WINDOWED,
            CLIENT_MODE_GLXGEARS_VIRGL_FULLSCREEN -> mode
            else -> CLIENT_MODE_INSPECT
        }

        val clientBlock = when (safeMode) {
            CLIENT_MODE_GLXGEARS_WINDOWED ->
                """
                timeout 8 glxgears -info > /tmp/hybrid-glxgears.log 2>&1 || true
                cat /tmp/hybrid-glxgears.log
                """.trimIndent()

            CLIENT_MODE_GLXGEARS_FULLSCREEN ->
                """
                timeout 8 glxgears -fullscreen -info > /tmp/hybrid-glxgears.log 2>&1 || true
                cat /tmp/hybrid-glxgears.log
                """.trimIndent()

            CLIENT_MODE_GLXGEARS_VIRGL_WINDOWED ->
                """
                export GALLIUM_DRIVER=virpipe
                export VTEST_SOCKET_NAME=/tmp/.virgl_test
                export MESA_GLX_ALPHA_BITS=0
                export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
                export MESA_GLSL_VERSION_OVERRIDE=430
                export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
                timeout 8 glxgears -info > /tmp/hybrid-glxgears.log 2>&1 || true
                cat /tmp/hybrid-glxgears.log
                """.trimIndent()

            CLIENT_MODE_GLXGEARS_VIRGL_FULLSCREEN ->
                """
                export GALLIUM_DRIVER=virpipe
                export VTEST_SOCKET_NAME=/tmp/.virgl_test
                export MESA_GLX_ALPHA_BITS=0
                export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
                export MESA_GLSL_VERSION_OVERRIDE=430
                export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
                timeout 8 glxgears -fullscreen -info > /tmp/hybrid-glxgears.log 2>&1 || true
                cat /tmp/hybrid-glxgears.log
                """.trimIndent()

            CLIENT_MODE_XEV ->
                """
                rm -f /tmp/hybrid-xev.log
                timeout 12 xev -geometry 800x600+0+0 > /tmp/hybrid-xev.log 2>&1 || true
                cat /tmp/hybrid-xev.log
                """.trimIndent()

            else ->
                """
                echo "[proot] available clients:"
                for tool in glxgears glxinfo xdpyinfo xclock xeyes xmessage openbox; do
                    if command -v "${'$'}tool" >/dev/null 2>&1; then
                        echo "  ${'$'}tool=${'$'}(command -v "${'$'}tool")"
                    else
                        echo "  ${'$'}tool=missing"
                    fi
                done
                echo "[proot] display summary:"
                xdpyinfo 2>&1 | head -20 || true
                echo "[proot] gl summary:"
                glxinfo 2>/dev/null | grep -iE "renderer|version|vendor|direct" || true
                """.trimIndent()
        }

        val virglSetup = if (safeMode == CLIENT_MODE_GLXGEARS_VIRGL_WINDOWED || safeMode == CLIENT_MODE_GLXGEARS_VIRGL_FULLSCREEN) {
            """
            pkill -f virgl_test_server_android 2>/dev/null || true
            rm -f "${'$'}PREFIX/tmp/.virgl_test" 2>/dev/null || true
            env -u LD_LIBRARY_PATH virgl_test_server_android > "${'$'}HOME/hybrid-virgl.log" 2>&1 &
            VIRGL_PID=${'$'}!
            for i in ${'$'}(seq 1 30); do
                if kill -0 "${'$'}VIRGL_PID" 2>/dev/null && [ -S "${'$'}PREFIX/tmp/.virgl_test" ]; then
                    echo "[shell] virgl ready pid=${'$'}VIRGL_PID"
                    break
                fi
                sleep 0.2
            done
            ls -la "${'$'}PREFIX/tmp/.virgl_test" 2>&1 || true
            """.trimIndent()
        } else {
            "VIRGL_PID="
        }

        val virglTeardown = if (safeMode == CLIENT_MODE_GLXGEARS_VIRGL_WINDOWED || safeMode == CLIENT_MODE_GLXGEARS_VIRGL_FULLSCREEN) {
            """
            if [ -n "${'$'}VIRGL_PID" ]; then
                kill "${'$'}VIRGL_PID" 2>/dev/null || true
            fi
            rm -f "${'$'}PREFIX/tmp/.virgl_test" 2>/dev/null || true
            """.trimIndent()
        } else {
            ""
        }

        return """
            export PREFIX=${TermuxCommandRunner.TERMUX_BIN_PATH.removeSuffix("/bin")}
            export TMPDIR="${'$'}PREFIX/tmp"
            mkdir -p "${'$'}TMPDIR/.X11-unix"
            MARKER="${'$'}TMPDIR/hybrid-marker-${'$'}$"
            echo "host-visible" > "${'$'}MARKER"
            X11_PID=""
            for proc in /proc/[0-9]*; do
                pid="${'$'}{proc#/proc/}"
                [ -r "${'$'}proc/cmdline" ] || continue
                cmdline="${'$'}(tr '\0' ' ' < "${'$'}proc/cmdline" 2>/dev/null || true)"
                case "${'$'}cmdline" in
                    *"com.termux.x11.Loader"*|*"termux-x11 :"*)
                        X11_PID="${'$'}pid"
                        break
                        ;;
                esac
            done
            LOG="${'$'}HOME/hybrid-x11-client.log"
            : > "${'$'}LOG"
            echo "[shell] mode=$safeMode" | tee -a "${'$'}LOG"
            echo "[shell] PREFIX=${'$'}PREFIX TMPDIR=${'$'}TMPDIR" | tee -a "${'$'}LOG"
            echo "[shell] termux-x11 path=${'$'}(command -v termux-x11 || echo missing)" | tee -a "${'$'}LOG"
            echo "[shell] proot-distro path=${'$'}(command -v proot-distro || echo missing)" | tee -a "${'$'}LOG"
            echo "[shell] x11_pid=${'$'}X11_PID" | tee -a "${'$'}LOG"
            echo "[shell] marker=${'$'}MARKER" | tee -a "${'$'}LOG"
            echo "[shell] tmpdir socket listing:" | tee -a "${'$'}LOG"
            ls -la "${'$'}TMPDIR/.X11-unix" 2>&1 | tee -a "${'$'}LOG" || true
            echo "[shell] tmpdir marker listing:" | tee -a "${'$'}LOG"
            ls -la "${'$'}TMPDIR"/hybrid-marker-* 2>&1 | tee -a "${'$'}LOG" || true
            if [ -n "${'$'}X11_PID" ]; then
                echo "[shell] x11 fd listing:" | tee -a "${'$'}LOG"
                ls -l "/proc/${'$'}X11_PID/fd" 2>&1 | tee -a "${'$'}LOG" || true
            fi
            echo "[shell] hybrid-x11-start.log tail:" | tee -a "${'$'}LOG"
            tail -n 80 "${TermuxCommandRunner.TERMUX_HOME_PATH}/hybrid-x11-start.log" 2>&1 | tee -a "${'$'}LOG" || true
            echo "[shell] host display summary:" | tee -a "${'$'}LOG"
            if command -v xdpyinfo >/dev/null 2>&1; then
                DISPLAY=:0 xdpyinfo 2>&1 | head -20 | tee -a "${'$'}LOG" || true
            else
                echo "xdpyinfo unavailable in Termux host env" | tee -a "${'$'}LOG"
            fi
            $virglSetup
            proot-distro login ubuntu --shared-tmp -- env DISPLAY=:0 bash -lc '
                echo "[proot] DISPLAY=${'$'}DISPLAY"
                echo "[proot] socket listing:"
                ls -la /tmp/.X11-unix 2>&1 || true
                echo "[proot] termux tmp socket listing:"
                ls -la /data/data/com.termux/files/usr/tmp/.X11-unix 2>&1 || true
                echo "[proot] marker listing:"
                ls -la /tmp/hybrid-marker-* /data/data/com.termux/files/usr/tmp/hybrid-marker-* 2>&1 || true
                echo "[proot] marker contents:"
                cat /tmp/hybrid-marker-* 2>&1 || true
                echo "[proot] tmp path details:"
                ls -ld /tmp /tmp/.X11-unix /data/data/com.termux/files/usr/tmp /data/data/com.termux/files/usr/tmp/.X11-unix 2>&1 || true
                echo "[proot] waiting for DISPLAY=:0"
                DISPLAY_READY=0
                for i in $(seq 1 75); do
                    if xdpyinfo >/tmp/hybrid-xdpyinfo.log 2>&1; then
                        DISPLAY_READY=1
                        break
                    fi
                    sleep 0.2
                done
                if [ "${'$'}DISPLAY_READY" -ne 1 ]; then
                    echo "[proot] DISPLAY never became ready"
                    cat /tmp/hybrid-xdpyinfo.log 2>&1 || true
                    exit 1
                fi
                echo "[proot] DISPLAY ready"
                $clientBlock
            ' < /dev/null 2>&1 | tee -a "${'$'}LOG"
            $virglTeardown
            rm -f "${'$'}MARKER" 2>/dev/null || true
            cat "${'$'}LOG"
        """.trimIndent()
    }

    companion object {
        const val ACTION_OPEN_HOST = "com.runelitetablet.action.OPEN_HYBRID_X11_HOST"
        const val ACTION_START_TEST = "com.runelitetablet.action.START_HYBRID_X11_TEST"
        const val ACTION_STOP_TEST = "com.runelitetablet.action.STOP_HYBRID_X11_TEST"
        const val ACTION_PROBE_FDS = "com.runelitetablet.action.PROBE_HYBRID_X11_FDS"
        const val ACTION_RUN_CLIENT = "com.runelitetablet.action.RUN_HYBRID_X11_CLIENT"
        const val ACTION_START_AND_RUN_CLIENT = "com.runelitetablet.action.START_AND_RUN_HYBRID_X11_CLIENT"
        const val ACTION_RUN_REAL_LAUNCHER = "com.runelitetablet.action.RUN_REAL_HYBRID_LAUNCHER"
        const val ACTION_DUMP_REAL_LAUNCH_STATE = "com.runelitetablet.action.DUMP_REAL_HYBRID_LAUNCH_STATE"
        const val ACTION_SHUTDOWN_REAL_LAUNCHER = "com.runelitetablet.action.SHUTDOWN_REAL_HYBRID_LAUNCHER"
        const val EXTRA_CLIENT_MODE = "client_mode"
        const val EXTRA_OVERRIDE_PACKAGE = "override_package"
        const val EXTRA_GPU_DISPLAY_RESOLUTION = "gpu_display_resolution"
        const val EXTRA_PRESENTATION_VARIANT = "presentation_variant"
        const val EXTRA_PROBE_SHAPE = "probe_shape"
        const val EXTRA_RUNELITE_SCALE = "runelite_scale"
        const val EXTRA_JAVA2D_PROFILE = "java2d_profile"
        const val EXTRA_MESA_PROFILE = "mesa_profile"
        const val EXTRA_VIRGL_SERVER_PROFILE = "virgl_server_profile"
        const val EXTRA_RUNELITE_LAUNCH_MODE = "runelite_launch_mode"
        const val PRESENTATION_VARIANT_HYBRID = "hybrid"
        const val PRESENTATION_VARIANT_INTERNAL_HYBRID = "internal-hybrid"
        const val PRESENTATION_VARIANT_STOCK = "stock"

        const val CLIENT_MODE_INSPECT = "inspect"
        const val CLIENT_MODE_XEV = "xev"
        const val CLIENT_MODE_GLXGEARS_WINDOWED = "glxgears-windowed"
        const val CLIENT_MODE_GLXGEARS_FULLSCREEN = "glxgears-fullscreen"
        const val CLIENT_MODE_GLXGEARS_VIRGL_WINDOWED = "glxgears-virgl-windowed"
        const val CLIENT_MODE_GLXGEARS_VIRGL_FULLSCREEN = "glxgears-virgl-fullscreen"

        const val PROBE_SHAPE_DELAYED = "delayed"
        const val PROBE_SHAPE_LAUNCHER_FAITHFUL = "launcher-faithful"
    }
}
