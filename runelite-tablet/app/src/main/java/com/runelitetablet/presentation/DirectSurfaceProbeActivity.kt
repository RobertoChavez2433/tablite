package com.runelitetablet.presentation

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.runelitetablet.directsurface.HardwareBufferPatternWriter
import com.runelitetablet.logging.AppLog
import com.runelitetablet.telemetry.CaptureSessionRegistry
import com.runelitetablet.telemetry.FrameEventType
import com.runelitetablet.telemetry.FrameOutcome
import com.runelitetablet.telemetry.FrameStage
import com.runelitetablet.telemetry.RendererBackend
import com.runelitetablet.telemetry.TelemetryExporter
import com.runelitetablet.ui.LaunchPreferences
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Gate-A direct-presentation primitive.
 *
 * This does not render RuneLite. It proves that a public-API SurfaceControl
 * child can accept native-resolution HardwareBuffers, carry acquire/release
 * fences, expose transaction latch/present timing, and recycle a bounded
 * triple buffer without an intermediate fullscreen GLES consumer blit.
 */
class DirectSurfaceProbeActivity : ComponentActivity() {
    private lateinit var probeView: SurfaceControlBufferProbeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        run {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = TARGET_REFRESH_HZ
            }
        }

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA10151A.toInt())
            textSize = 18f
            setPadding(24, 24, 24, 24)
            text = "SurfaceControl/AHardwareBuffer gate starting"
        }
        probeView = SurfaceControlBufferProbeView(this) { status.text = it }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                probeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        })

        val preferences = LaunchPreferences(this)
        val session = CaptureSessionRegistry.start(
            backend = RendererBackend.DIRECT_VIRGL_AHB,
            telemetryMode = preferences.telemetryMode,
            targetHz = preferences.targetHz
        )
        TelemetryExporter.start(applicationContext, session)
        AppLog.state(
            "CAPTURE-START runId=${session.manifest.runId} backend=${session.manifest.backend} " +
                "mode=${session.manifest.telemetryMode} targetHz=${session.manifest.targetHz} " +
                "source=surface-control-ahb-probe"
        )
        AppLog.lifecycle("DirectSurfaceProbeActivity.onCreate api=${Build.VERSION.SDK_INT}")
    }

    override fun onResume() {
        super.onResume()
        probeView.startProbe()
        AppLog.lifecycle("DirectSurfaceProbeActivity.onResume")
    }

    override fun onPause() {
        probeView.stopProbe("activity-pause")
        AppLog.lifecycle("DirectSurfaceProbeActivity.onPause")
        super.onPause()
    }

    override fun onDestroy() {
        probeView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TARGET_REFRESH_HZ = 120f
    }
}

private class SurfaceControlBufferProbeView(
    private val activity: DirectSurfaceProbeActivity,
    private val onStatus: (String) -> Unit
) : SurfaceView(activity), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private data class BufferSlot(
        val index: Int,
        val buffer: HardwareBuffer,
        var inFlight: Boolean = false,
        var lastReleaseFence: SyncFence? = null,
        var closed: Boolean = false
    )

    private val fenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rlt-surface-fence").apply { isDaemon = true }
    }
    private val targetHz = LaunchPreferences(activity).targetHz
    private var surfaceReady = false
    private var running = false
    private var destroyed = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var generation = 0
    private var child: SurfaceControl? = null
    private var slots = emptyList<BufferSlot>()
    private var nextSlot = 0
    private var nextFrameSeq = 1L
    private var callbackCount = 0L
    private var submittedCount = 0L
    private var latchedCount = 0L
    private var presentedCount = 0L
    private var releasedCount = 0L
    private var starvedCount = 0L
    @Volatile
    private var timedOutFenceCount = 0L
    private var acquireFenceSubmitCount = 0L
    private var lastReportNs = 0L
    private var reportSubmitted = 0L
    private var reportPresented = 0L
    private var reportReleased = 0L
    private var fdBaseline = -1
    private var maxInFlight = 0

    init {
        holder.addCallback(this)
        // A SurfaceView's default media layer sits behind the app window. The
        // SurfaceControl child inherits that ordering, so use the documented
        // on-top SurfaceView mode for this visual pixel-correctness probe.
        setZOrderOnTop(true)
        setBackgroundColor(Color.BLACK)
    }

    fun startProbe() {
        if (destroyed || running) return
        running = true
        if (surfaceReady) {
            ensureResources()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stopProbe(reason: String) {
        if (!running && child == null) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        teardownResources(reason)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stopProbe("destroy")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        requestSurfaceRefreshRate(holder.surface)
        val parentValid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && surfaceControl.isValid
        AppLog.lifecycle("SurfaceControlProbe.surfaceCreated parent=$parentValid")
        if (running) {
            ensureResources()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val changed = width != surfaceWidth || height != surfaceHeight
        surfaceWidth = width
        surfaceHeight = height
        AppLog.lifecycle("SurfaceControlProbe.surfaceChanged ${width}x$height format=$format changed=$changed")
        if (running && changed) {
            teardownResources("resize")
            ensureResources()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        Choreographer.getInstance().removeFrameCallback(this)
        teardownResources("surface-destroyed")
        AppLog.lifecycle("SurfaceControlProbe.surfaceDestroyed")
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !surfaceReady || destroyed) return
        callbackCount++
        val slot = takeAvailableSlot()
        if (slot == null) {
            starvedCount++
            recordEvent(
                frameSeq = nextFrameSeq,
                stage = FrameStage.SURFACE_CONTROL_SUBMIT,
                outcome = FrameOutcome.STARVED,
                beginNs = frameTimeNanos,
                endNs = frameTimeNanos
            )
        } else {
            submit(slot, frameTimeNanos)
        }
        reportIfDue(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun ensureResources() {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            child != null ||
            !surfaceReady ||
            surfaceWidth <= 0 ||
            surfaceHeight <= 0
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onStatus("SurfaceControl.setBuffer requires Android 13+")
                AppLog.e("SurfaceControlProbe", "unsupported api=${Build.VERSION.SDK_INT}")
            }
            return
        }

        generation++
        val usage = HardwareBuffer.USAGE_COMPOSER_OVERLAY or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            HardwareBuffer.USAGE_CPU_WRITE_RARELY
        if (!HardwareBuffer.isSupported(
                surfaceWidth,
                surfaceHeight,
                HardwareBuffer.RGBA_8888,
                1,
                usage
            )
        ) {
            AppLog.e(
                "SurfaceControlProbe",
                "AHB unsupported ${surfaceWidth}x$surfaceHeight format=RGBA_8888 usage=0x${usage.toString(16)}"
            )
            onStatus("Required HardwareBuffer usage combination is unsupported")
            return
        }

        fdBaseline = countOpenFds()
        val newSlots = ArrayList<BufferSlot>(POOL_SIZE)
        try {
            repeat(POOL_SIZE) { index ->
                val buffer = HardwareBuffer.create(
                    surfaceWidth,
                    surfaceHeight,
                    HardwareBuffer.RGBA_8888,
                    1,
                    usage
                )
                check(HardwareBufferPatternWriter.fill(buffer, index, generation)) {
                    "native HardwareBuffer pattern fill failed for slot $index"
                }
                newSlots += BufferSlot(index, buffer)
            }
            val newChild = SurfaceControl.Builder()
                .setName("rlt-surface-control-probe-g$generation")
                .setParent(surfaceControl)
                .setBufferSize(surfaceWidth, surfaceHeight)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(true)
                .setHidden(false)
                .build()
            SurfaceControl.Transaction().use { transaction ->
                transaction
                    .setLayer(newChild, 1)
                    .setCrop(newChild, Rect(0, 0, surfaceWidth, surfaceHeight))
                    .setPosition(newChild, 0f, 0f)
                    .setFrameRate(
                        newChild,
                        targetHz.toFloat(),
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    )
                    .setVisibility(newChild, true)
                    .apply()
            }
            slots = newSlots
            child = newChild
            nextSlot = 0
            lastReportNs = 0
            reportSubmitted = submittedCount
            reportPresented = presentedCount
            reportReleased = releasedCount
            AppLog.i(
                "SurfaceControlProbe",
                "POOL-CREATED generation=$generation size=${surfaceWidth}x$surfaceHeight slots=$POOL_SIZE " +
                    "usage=0x${usage.toString(16)} fdBaseline=$fdBaseline fdNow=${countOpenFds()}"
            )
            onStatus(
                "SurfaceControl/AHB gate\n${surfaceWidth}x$surfaceHeight @ ${targetHz} Hz\n" +
                    "generation=$generation pool=$POOL_SIZE"
            )
        } catch (error: Throwable) {
            newSlots.forEach { closeSlot(it, "create-failure") }
            AppLog.e("SurfaceControlProbe", "resource creation failed", error)
            onStatus("SurfaceControl/AHB resource creation failed: ${error.message}")
        }
    }

    private fun submit(slot: BufferSlot, frameTimeNanos: Long) {
        val target = child ?: return
        val frameSeq = nextFrameSeq++
        val submitBeginNs = System.nanoTime()
        val acquireFence = slot.lastReleaseFence?.let { prior ->
            SyncFence(prior).also { acquireFenceSubmitCount++ }
        }
        slot.lastReleaseFence?.close()
        slot.lastReleaseFence = null
        slot.inFlight = true
        maxInFlight = max(maxInFlight, slots.count { it.inFlight })
        val poolGeneration = generation

        try {
            SurfaceControl.Transaction().use { transaction ->
                transaction.setBuffer(target, slot.buffer, acquireFence) { releaseFence ->
                    handleRelease(slot, frameSeq, poolGeneration, releaseFence)
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    transaction
                        .setDesiredPresentTimeNanos(frameTimeNanos)
                        .addTransactionCompletedListener(activity.mainExecutor) { stats ->
                            handleTransactionComplete(
                                frameSeq = frameSeq,
                                slotIndex = slot.index,
                                poolGeneration = poolGeneration,
                                submitBeginNs = submitBeginNs,
                                latchNs = stats.latchTimeNanos,
                                presentFence = stats.presentFence
                            )
                        }
                }
                transaction.apply()
            }
            submittedCount++
            recordEvent(
                frameSeq = frameSeq,
                stage = FrameStage.SURFACE_CONTROL_SUBMIT,
                outcome = FrameOutcome.SUBMITTED,
                beginNs = submitBeginNs,
                endNs = System.nanoTime(),
                slot = slot,
                eventFlags = if (acquireFence != null) FLAG_ACQUIRE_FENCE else 0
            )
        } catch (error: Throwable) {
            slot.inFlight = false
            AppLog.e("SurfaceControlProbe", "submit failed frame=$frameSeq slot=${slot.index}", error)
            recordEvent(
                frameSeq = frameSeq,
                stage = FrameStage.SURFACE_CONTROL_SUBMIT,
                outcome = FrameOutcome.ERROR,
                beginNs = submitBeginNs,
                endNs = System.nanoTime(),
                slot = slot
            )
        } finally {
            acquireFence?.close()
        }
    }

    private fun handleTransactionComplete(
        frameSeq: Long,
        slotIndex: Int,
        poolGeneration: Int,
        submitBeginNs: Long,
        latchNs: Long,
        presentFence: SyncFence
    ) {
        val hadPresentFence = presentFence.isValid
        val effectiveLatchNs = if (latchNs >= 0) latchNs else System.nanoTime()
        if (poolGeneration != generation) {
            presentFence.close()
            return
        }
        latchedCount++
        recordEvent(
            frameSeq = frameSeq,
            stage = FrameStage.SURFACE_CONTROL_LATCH,
            outcome = if (latchNs >= 0) FrameOutcome.ACCEPTED else FrameOutcome.UNSUPPORTED,
            beginNs = submitBeginNs,
            endNs = effectiveLatchNs,
            slotIndex = slotIndex,
            poolGeneration = poolGeneration
        )
        awaitFence(
            fence = presentFence,
            label = "present",
            frameSeq = frameSeq,
            slotIndex = slotIndex,
            poolGeneration = poolGeneration
        ) { signalNs, outcome ->
            activity.mainExecutor.execute {
                if (poolGeneration == generation && outcome == FrameOutcome.PRESENTED) {
                    presentedCount++
                }
                recordEvent(
                    frameSeq = frameSeq,
                    stage = FrameStage.DISPLAY_PRESENT,
                    outcome = outcome,
                    beginNs = effectiveLatchNs,
                    endNs = signalNs,
                    slotIndex = slotIndex,
                    poolGeneration = poolGeneration,
                    eventFlags = if (hadPresentFence) FLAG_PRESENT_FENCE else 0
                )
            }
        }
    }

    private fun handleRelease(
        slot: BufferSlot,
        frameSeq: Long,
        poolGeneration: Int,
        releaseFence: SyncFence
    ) {
        val releaseCallbackNs = System.nanoTime()
        val hadReleaseFence = releaseFence.isValid
        awaitFence(
            fence = releaseFence,
            label = "release",
            frameSeq = frameSeq,
            slotIndex = slot.index,
            poolGeneration = poolGeneration,
            retainSignaledFence = true
        ) { signalNs, outcome ->
            activity.mainExecutor.execute {
                if (slot.closed) return@execute
                if (outcome == FrameOutcome.ACCEPTED && poolGeneration == generation && !destroyed) {
                    slot.lastReleaseFence?.close()
                    if (releaseFence.isValid) {
                        slot.lastReleaseFence = releaseFence
                    } else {
                        releaseFence.close()
                        slot.lastReleaseFence = null
                    }
                    slot.inFlight = false
                    releasedCount++
                } else {
                    releaseFence.close()
                    if (poolGeneration != generation || destroyed || !running) {
                        closeSlot(slot, "released-after-teardown")
                    }
                }
                recordEvent(
                    frameSeq = frameSeq,
                    stage = FrameStage.BUFFER_RELEASE,
                    outcome = outcome,
                    beginNs = releaseCallbackNs,
                    endNs = signalNs,
                    slot = slot,
                    poolGeneration = poolGeneration,
                    eventFlags = if (hadReleaseFence) FLAG_RELEASE_FENCE else 0
                )
            }
        }
    }

    private fun awaitFence(
        fence: SyncFence,
        label: String,
        frameSeq: Long,
        slotIndex: Int,
        poolGeneration: Int,
        retainSignaledFence: Boolean = false,
        completion: (Long, FrameOutcome) -> Unit
    ) {
        if (!fence.isValid) {
            completion(System.nanoTime(), if (label == "present") FrameOutcome.UNSUPPORTED else FrameOutcome.ACCEPTED)
            if (!retainSignaledFence) fence.close()
            return
        }
        fenceExecutor.execute {
            val signaled = fence.await(FENCE_TIMEOUT)
            val signalNs = fence.signalTime.let { if (it >= 0) it else System.nanoTime() }
            val outcome = if (signaled) {
                if (label == "present") FrameOutcome.PRESENTED else FrameOutcome.ACCEPTED
            } else {
                timedOutFenceCount++
                AppLog.w(
                    "SurfaceControlProbe",
                    "FENCE-TIMEOUT type=$label frame=$frameSeq slot=$slotIndex generation=$poolGeneration"
                )
                FrameOutcome.TIMED_OUT
            }
            completion(signalNs, outcome)
            if (!retainSignaledFence || !signaled) fence.close()
        }
    }

    private fun takeAvailableSlot(): BufferSlot? {
        if (slots.isEmpty()) return null
        repeat(slots.size) {
            val candidate = slots[nextSlot]
            nextSlot = (nextSlot + 1) % slots.size
            if (!candidate.inFlight && !candidate.closed) return candidate
        }
        return null
    }

    private fun reportIfDue(frameTimeNanos: Long) {
        if (lastReportNs == 0L) {
            lastReportNs = frameTimeNanos
            reportSubmitted = submittedCount
            reportPresented = presentedCount
            reportReleased = releasedCount
            return
        }
        val elapsed = frameTimeNanos - lastReportNs
        if (elapsed < REPORT_INTERVAL_NS) return
        val submittedFps = (submittedCount - reportSubmitted) * 1_000_000_000.0 / elapsed
        val presentedFps = (presentedCount - reportPresented) * 1_000_000_000.0 / elapsed
        val releasedFps = (releasedCount - reportReleased) * 1_000_000_000.0 / elapsed
        val inFlight = slots.count { it.inFlight }
        val fdNow = countOpenFds()
        AppLog.perf(
            "SURFACECONTROL-GATE generation=$generation callbacks=$callbackCount submitted=$submittedCount " +
                "latched=$latchedCount presented=$presentedCount released=$releasedCount starved=$starvedCount " +
                "submitted_fps=${String.format(Locale.US, "%.1f", submittedFps)} " +
                "presented_fps=${String.format(Locale.US, "%.1f", presentedFps)} " +
                "released_fps=${String.format(Locale.US, "%.1f", releasedFps)} " +
                "inFlight=$inFlight maxInFlight=$maxInFlight acquireFenceSubmits=$acquireFenceSubmitCount " +
                "fenceTimeouts=$timedOutFenceCount fdBaseline=$fdBaseline fdNow=$fdNow"
        )
        onStatus(
            "SurfaceControl/AHB gate\n${surfaceWidth}x$surfaceHeight @ ${targetHz} Hz\n" +
                "submitted ${String.format(Locale.US, "%.1f", submittedFps)} fps · " +
                "presented ${String.format(Locale.US, "%.1f", presentedFps)} fps\n" +
                "frames=$presentedCount starved=$starvedCount fenceTimeouts=$timedOutFenceCount"
        )
        lastReportNs = frameTimeNanos
        reportSubmitted = submittedCount
        reportPresented = presentedCount
        reportReleased = releasedCount
    }

    private fun teardownResources(reason: String) {
        val oldChild = child ?: run {
            slots.forEach { closeSlot(it, reason) }
            slots = emptyList()
            return
        }
        child = null
        val oldSlots = slots
        slots = emptyList()
        val oldGeneration = generation
        val fdBefore = countOpenFds()
        try {
            SurfaceControl.Transaction().use { transaction ->
                transaction.setBuffer(oldChild, null)
                transaction.setVisibility(oldChild, false)
                if (Build.VERSION.SDK_INT >= 35) {
                    transaction.addTransactionCompletedListener(activity.mainExecutor) {
                        oldChild.release()
                        closeReleasedSlots(oldSlots, "transaction-complete")
                        AppLog.i(
                            "SurfaceControlProbe",
                            "POOL-DESTROYED generation=$oldGeneration reason=$reason " +
                                "fdBaseline=$fdBaseline fdBefore=$fdBefore fdAfter=${countOpenFds()}"
                        )
                    }
                }
                transaction.apply()
            }
            if (Build.VERSION.SDK_INT < 35) {
                oldChild.release()
                closeReleasedSlots(oldSlots, "legacy-teardown")
            }
        } catch (error: Throwable) {
            oldChild.release()
            closeReleasedSlots(oldSlots, "teardown-error")
            AppLog.e("SurfaceControlProbe", "teardown failed generation=$oldGeneration reason=$reason", error)
        }
    }

    private fun closeReleasedSlots(targets: List<BufferSlot>, reason: String) {
        targets.filter { !it.inFlight }.forEach { closeSlot(it, reason) }
        val pending = targets.count { it.inFlight && !it.closed }
        if (pending > 0) {
            AppLog.i(
                "SurfaceControlProbe",
                "teardown awaiting $pending release callbacks generation=$generation reason=$reason"
            )
        }
    }

    private fun closeSlot(slot: BufferSlot, reason: String) {
        if (slot.closed) return
        slot.lastReleaseFence?.close()
        slot.lastReleaseFence = null
        slot.buffer.close()
        slot.closed = true
        AppLog.d("SurfaceControlProbe", "buffer-close slot=${slot.index} reason=$reason")
    }

    private fun recordEvent(
        frameSeq: Long,
        stage: FrameStage,
        outcome: FrameOutcome,
        beginNs: Long,
        endNs: Long,
        slot: BufferSlot? = null,
        slotIndex: Int = slot?.index ?: -1,
        poolGeneration: Int = generation,
        eventFlags: Long = 0
    ) {
        CaptureSessionRegistry.active?.recorder?.record(
            threadId = Process.myTid(),
            frameSeq = frameSeq,
            stage = stage,
            eventType = FrameEventType.SPAN,
            outcome = outcome,
            beginMonotonicNs = beginNs,
            endMonotonicNs = endNs,
            threadCpuTimeNs = Debug.threadCpuTimeNanos(),
            bufferIndex = slotIndex,
            poolGeneration = poolGeneration,
            width = surfaceWidth,
            height = surfaceHeight,
            strideBytes = surfaceWidth * 4,
            damagedPixelCount = surfaceWidth.toLong() * surfaceHeight,
            transferredBytes = surfaceWidth.toLong() * surfaceHeight * 4,
            queueDepth = slots.count { it.inFlight },
            flags = eventFlags
        )
    }

    private fun requestSurfaceRefreshRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
                targetHz.toFloat(),
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface.setFrameRate(targetHz.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    private fun countOpenFds(): Int = File("/proc/self/fd").list()?.size ?: -1

    private companion object {
        const val POOL_SIZE = 3
        const val REPORT_INTERVAL_NS = 1_000_000_000L
        const val FLAG_ACQUIRE_FENCE = 1L
        const val FLAG_RELEASE_FENCE = 1L shl 1
        const val FLAG_PRESENT_FENCE = 1L shl 2
        val FENCE_TIMEOUT: Duration = Duration.ofMillis(100)
    }
}
