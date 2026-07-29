package com.runelitetablet.presentation.hybrid

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.runelitetablet.logging.AppLog
import com.termux.x11.LorieView
import kotlin.math.abs

/**
 * Minimal input bridge for the hybrid host.
 *
 * This intentionally covers the core interactions needed to validate the
 * app-owned surface path before porting the full upstream Termux:X11 input
 * stack: hardware mouse, wheel, buttons, captured pointer, stylus, keyboard,
 * and trackpad-style touchscreen input.
 *
 * Important constraint: touchscreen input must never be forwarded as direct
 * absolute "touch the game world here" injection. Touch acts like a trackpad:
 * relative cursor movement, tap-to-click, and scroll gestures. Real hardware
 * mouse/touchpad devices keep their native pointer semantics.
 */
class HybridInputController(
    private val rootView: View,
    private val lorieView: LorieView
) {
    private val touchSlop = ViewConfiguration.get(rootView.context).scaledTouchSlop.toFloat()

    private var lastButtonState = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartAtMs = 0L
    private var touchMoved = false
    private var lastTwoFingerY = 0f
    private var twoFingerMoved = false

    fun install() {
        rootView.isFocusable = true
        rootView.isFocusableInTouchMode = true
        lorieView.isFocusable = true
        lorieView.isFocusableInTouchMode = true

        bindPointerTarget(rootView)
        lorieView.setOnCapturedPointerListener { _, event ->
            handleCapturedPointerEvent(event)
        }
        lorieView.setOnKeyListener { _, _, event ->
            handleKeyEvent(event)
        }
        rootView.requestFocus()
        lorieView.requestFocus()
    }

    /**
     * Routes pointer events from an additional composited view through the
     * same trackpad/mouse state machine. SurfaceViews are independent input
     * targets, so a direct-canvas SurfaceView must be bound explicitly.
     */
    fun bindPointerTarget(target: View, telemetryLabel: String? = null) {
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        var firstInputLogged = false
        target.setOnTouchListener { v, event ->
            if (!firstInputLogged && telemetryLabel != null) {
                firstInputLogged = true
                AppLog.i(
                    "HYBRID_INPUT",
                    "RLT_DIRECT_INPUT_V1 stage=pointer_dispatch outcome=received " +
                        "target=$telemetryLabel source=${event.source} action=${event.actionMasked}",
                )
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.requestUnbufferedDispatch(event)
            }
            handleTouchEvent(event)
        }
        target.setOnHoverListener { _, event ->
            handleMouseMotionEvent(event)
        }
        target.setOnGenericMotionListener { _, event ->
            handleGenericMotionEvent(event)
        }
        target.setOnCapturedPointerListener { _, event ->
            handleCapturedPointerEvent(event)
        }
    }

    fun onWindowMetricsChanged(surfaceWidth: Int, surfaceHeight: Int, screenWidth: Int, screenHeight: Int) {
        AppLog.step(
            "hybrid_x11",
            "HybridInputController: host=${surfaceWidth}x$surfaceHeight client=${screenWidth}x$screenHeight"
        )
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when {
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                event.isFromSource(InputDevice.SOURCE_TOUCHPAD) -> {
                return handleMouseMotionEvent(event)
            }

            event.isFromSource(InputDevice.SOURCE_STYLUS) -> {
                return handleStylusLikeEvent(event)
            }

            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) -> {
                return handleTouchscreenTrackpadEvent(event)
            }

            else -> {
                return false
            }
        }
    }

    private fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        ) {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                val deltaX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val deltaY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (deltaX != 0f || deltaY != 0f) {
                    lorieView.sendMouseWheelEvent(deltaX, -deltaY)
                    return true
                }
            }
            return handleMouseMotionEvent(event)
        }

        return false
    }

    private fun handleCapturedPointerEvent(event: MotionEvent): Boolean {
        val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx != 0f || dy != 0f) {
            lorieView.sendMouseEvent(dx, dy, 0, false, true)
        }
        syncButtons(event, relative = true)
        return true
    }

    private fun handleMouseMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                lorieView.sendMouseEvent(event.x, event.y, 0, false, false)
                syncButtons(event, relative = false)
                return true
            }

            MotionEvent.ACTION_SCROLL -> {
                val deltaX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val deltaY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                lorieView.sendMouseWheelEvent(deltaX, -deltaY)
                return true
            }
        }

        return false
    }

    private fun handleStylusLikeEvent(event: MotionEvent): Boolean {
        val pressure = (event.getAxisValue(MotionEvent.AXIS_PRESSURE) * 65535f).toInt().coerceAtLeast(0)
        val tiltX = (event.getAxisValue(MotionEvent.AXIS_TILT) * 90f).toInt()
        val tiltY = 0
        val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION).toInt()
        val buttons = event.buttonState
        lorieView.sendStylusEvent(
            event.x,
            event.y,
            pressure,
            tiltX,
            tiltY,
            orientation,
            buttons,
            false,
            true
        )
        syncButtons(event, relative = false)
        return true
    }

    private fun handleTouchscreenTrackpadEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                touchStartAtMs = SystemClock.uptimeMillis()
                touchMoved = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    lastTwoFingerY = averageY(event)
                    twoFingerMoved = false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val currentAverageY = averageY(event)
                    val scrollDeltaY = currentAverageY - lastTwoFingerY
                    if (abs(scrollDeltaY) > 1f) {
                        lorieView.sendMouseWheelEvent(0f, -scrollDeltaY / 24f)
                        twoFingerMoved = true
                    }
                    lastTwoFingerY = currentAverageY
                    return true
                }

                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (abs(event.x - touchStartX) > touchSlop || abs(event.y - touchStartY) > touchSlop) {
                    touchMoved = true
                }
                lastTouchX = event.x
                lastTouchY = event.y
                lorieView.sendMouseEvent(dx, dy, 0, false, true)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && !twoFingerMoved) {
                    lorieView.sendMouseEvent(0f, 0f, 3, true, true)
                    lorieView.sendMouseEvent(0f, 0f, 3, false, true)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!touchMoved && SystemClock.uptimeMillis() - touchStartAtMs < 250L) {
                    lorieView.sendMouseEvent(0f, 0f, 1, true, true)
                    lorieView.sendMouseEvent(0f, 0f, 1, false, true)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchMoved = false
                twoFingerMoved = false
                return true
            }
        }

        return false
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        return lorieView.sendKeyEvent(
            event.scanCode,
            event.keyCode,
            event.action == KeyEvent.ACTION_DOWN,
            0
        )
    }

    private fun syncButtons(event: MotionEvent, relative: Boolean) {
        syncButton(event, MotionEvent.BUTTON_PRIMARY, 1, relative)
        syncButton(event, MotionEvent.BUTTON_TERTIARY, 2, relative)
        syncButton(event, MotionEvent.BUTTON_SECONDARY, 3, relative)
        lastButtonState = event.buttonState
    }

    private fun syncButton(event: MotionEvent, androidMask: Int, x11Button: Int, relative: Boolean) {
        val wasDown = lastButtonState and androidMask != 0
        val isDown = event.buttonState and androidMask != 0
        if (wasDown == isDown) {
            return
        }
        lorieView.sendMouseEvent(event.x, event.y, x11Button, isDown, relative)
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount <= 0) {
            return 0f
        }
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += event.getY(index)
        }
        return total / event.pointerCount.toFloat()
    }
}
