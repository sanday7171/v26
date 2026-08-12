package com.lingoffline.automation.v26

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LingAutoGesture"
        private const val SKILL2_X_NORM = 0.7585f
        private const val SKILL2_Y_NORM = 0.7019f
        private const val SKILL2_DRAG_RADIUS_FACTOR = 0.150f
        private const val SKILL2_GESTURE_MS = 68L

        var instance: GestureAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        AutomationState.statusText = "accessibility aktif"
        Log.d(TAG, "SERVICE_CONNECTED V2.6")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tapNormalized(nx: Float, ny: Float): Boolean {
        val metrics = displayMetrics()

        val x = nx.coerceIn(0f, 1f) * metrics.widthPixels
        val y = ny.coerceIn(0f, 1f) * metrics.heightPixels

        val path = Path().apply { moveTo(x, y) }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    45
                )
            )
            .build()

        return dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun dragSkill2Toward(
        targetX: Float,
        targetY: Float,
        heroX: Float,
        heroY: Float,
        frameWidth: Int,
        frameHeight: Int,
        onFinished: ((Boolean) -> Unit)? = null
    ): Boolean {
        val dx = targetX - heroX
        val dy = targetY - heroY
        val length = hypot(dx, dy)

        if (length < 1f) return false

        val ux = dx / length
        val uy = dy / length

        val metrics = displayMetrics()

        val startX = metrics.widthPixels * SKILL2_X_NORM
        val startY = metrics.heightPixels * SKILL2_Y_NORM

        val radius = min(
            metrics.widthPixels,
            metrics.heightPixels
        ) * SKILL2_DRAG_RADIUS_FACTOR

        val endX = (
            startX + ux * radius
        ).coerceIn(0f, metrics.widthPixels.toFloat())

        val endY = (
            startY + uy * radius
        ).coerceIn(0f, metrics.heightPixels.toFloat())

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            SKILL2_GESTURE_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    Log.d(
                        TAG,
                        "SKILL2_COMPLETED " +
                            "end=(${endX.roundToInt()},${endY.roundToInt()})"
                    )
                    onFinished?.invoke(true)
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    Log.w(TAG, "SKILL2_CANCELLED")
                    onFinished?.invoke(false)
                }
            },
            null
        )

        Log.d(
            TAG,
            "SKILL2_VECTOR " +
                "hero=(${heroX.roundToInt()},${heroY.roundToInt()}) " +
                "target=(${targetX.roundToInt()},${targetY.roundToInt()}) " +
                "start=(${startX.roundToInt()},${startY.roundToInt()}) " +
                "end=(${endX.roundToInt()},${endY.roundToInt()}) " +
                "duration=${SKILL2_GESTURE_MS}ms " +
                "accepted=$accepted"
        )

        return accepted
    }

    private fun displayMetrics(): DisplayMetrics {
        val wm =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        return metrics
    }
}
