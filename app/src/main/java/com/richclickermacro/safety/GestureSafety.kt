package com.richclickermacro.safety

import kotlin.math.ceil

/**
 * Pure helpers for gesture validation. Kept separate from AccessibilityService so
 * they can be unit-tested without a device.
 */
object GestureSafety {
    const val MAX_SEGMENT_DURATION_MS = 500L
    const val CALLBACK_TIMEOUT_GRACE_MS = 1_500L

    fun isValidPoint(x: Float, y: Float): Boolean = x.isFinite() && y.isFinite()

    fun isValidGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean =
        isValidPoint(startX, startY) && isValidPoint(endX, endY) && durationMs > 0L

    /**
     * Gesture paths must stay inside the display. Normalized marker coordinates may
     * land exactly on width/height at the far edge; clamp to the last addressable pixel.
     */
    fun clampToFrame(frame: ScreenFrame, x: Float, y: Float): Pair<Float, Float> {
        val minX = frame.left.toFloat()
        val minY = frame.top.toFloat()
        val maxX = (frame.left + frame.width - 1).toFloat()
        val maxY = (frame.top + frame.height - 1).toFloat()
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    /** Liang–Barsky clipping, including touching an edge. Prevents swipes crossing the toolbar. */
    fun segmentIntersectsRect(
        startX: Float, startY: Float, endX: Float, endY: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ): Boolean {
        if (!isValidPoint(startX, startY) || !isValidPoint(endX, endY) ||
            !left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() || left > right || top > bottom) return false
        val dx = endX - startX
        val dy = endY - startY
        var enter = 0f
        var exit = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(startX - left, right - startX, startY - top, bottom - startY)
        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > exit) return false
                    if (t > enter) enter = t
                } else {
                    if (t < enter) return false
                    if (t < exit) exit = t
                }
            }
        }
        return enter <= exit
    }

    fun segmentCount(totalDurationMs: Long, maxSegmentMs: Long = MAX_SEGMENT_DURATION_MS): Int {
        require(totalDurationMs > 0L) { "duration must be positive" }
        require(maxSegmentMs > 0L) { "max segment must be positive" }
        return ceil(totalDurationMs.toDouble() / maxSegmentMs.toDouble()).toInt().coerceAtLeast(1)
    }

    fun callbackTimeoutMs(segmentDurationMs: Long): Long =
        segmentDurationMs.coerceAtLeast(1L) + CALLBACK_TIMEOUT_GRACE_MS
}
