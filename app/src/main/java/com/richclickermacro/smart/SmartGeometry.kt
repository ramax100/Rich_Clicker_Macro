package com.richclickermacro.smart

import kotlin.math.abs

/**
 * Pure geometry checks for Multi Smart Click. Kept free of Android types so the
 * overlap rules that protect sampling correctness are unit-testable.
 */
object SmartGeometry {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun intersects(other: Box): Boolean =
            left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    fun patchBox(centerX: Float, centerY: Float, radius: Float): Box =
        Box(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

    /**
     * A watched patch must not sit under any of our own windows: the screenshot
     * contains our overlay, so such a sample would read the marker, not the game.
     */
    fun isPatchObscured(patch: Box, obstacles: List<Box>): Boolean =
        obstacles.any { patch.intersects(it) }

    /** Two watched patches must not read the same area, which would double-fire. */
    fun patchesTooClose(ax: Float, ay: Float, bx: Float, by: Float, minDistance: Float): Boolean =
        abs(ax - bx) < minDistance && abs(ay - by) < minDistance

    // ---------------------------------------------------------- canonical mapping
    //
    // Multi Smart Click has exactly ONE coordinate space: normalized [0,1] over the
    // FULL physical display. That is the space the accessibility screenshot uses and
    // the space dispatchGesture expects after de-normalizing.
    //
    // Previously markers were laid out display-locally (ignoring the frame origin)
    // while the picker and the gesture used origin-aware absolute pixels, and the
    // frame came from currentWindowMetrics (which can exclude system insets) while
    // the screenshot always covers the whole display. Those disagreeing mappings are
    // what made a click land somewhere other than the watched colour.

    /** Absolute screen X for a normalized position. */
    fun absoluteX(normalizedX: Float, originX: Int, width: Int): Float =
        originX + normalizedX.coerceIn(0f, 1f) * width

    /** Absolute screen Y for a normalized position. */
    fun absoluteY(normalizedY: Float, originY: Int, height: Int): Float =
        originY + normalizedY.coerceIn(0f, 1f) * height

    /** Window left for a marker centred on the normalized position. */
    fun markerLeft(normalizedX: Float, originX: Int, width: Int, markerSize: Int): Int =
        kotlin.math.round(absoluteX(normalizedX, originX, width) - markerSize / 2f).toInt()

    /** Window top for a marker centred on the normalized position. */
    fun markerTop(normalizedY: Float, originY: Int, height: Int, markerSize: Int): Int =
        kotlin.math.round(absoluteY(normalizedY, originY, height) - markerSize / 2f).toInt()

    /** Exact inverse of [markerLeft]. */
    fun fromMarkerLeft(left: Int, markerSize: Int, originX: Int, width: Int): Float =
        if (width <= 0) 0.5f
        else ((left + markerSize / 2f - originX) / width).coerceIn(0f, 1f)

    /** Exact inverse of [markerTop]. */
    fun fromMarkerTop(top: Int, markerSize: Int, originY: Int, height: Int): Float =
        if (height <= 0) 0.5f
        else ((top + markerSize / 2f - originY) / height).coerceIn(0f, 1f)

    /**
     * True when a screenshot's shape matches the display frame closely enough that a
     * normalized position means the same place in both. A mismatch means the capture
     * is scaled or cropped, so sampling and clicking would no longer agree.
     */
    fun captureMatchesFrame(
        captureWidth: Int,
        captureHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        tolerance: Float = 0.02f
    ): Boolean {
        if (captureWidth <= 0 || captureHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return false
        val captureRatio = captureWidth.toFloat() / captureHeight
        val frameRatio = frameWidth.toFloat() / frameHeight
        return kotlin.math.abs(captureRatio - frameRatio) / frameRatio <= tolerance
    }

    /** Normalized position from a marker's on-screen centre. */
    fun normalize(center: Float, origin: Int, size: Int): Float {
        if (size <= 0) return 0.5f
        return ((center - origin) / size).coerceIn(0f, 1f)
    }

    fun denormalize(normalized: Float, origin: Int, size: Int): Float =
        origin + normalized.coerceIn(0f, 1f) * size

    /**
     * Rejects a run before it starts rather than producing silent no-ops.
     * Returns a user-facing problem, or null when the profile is runnable.
     */
    fun validationProblem(profile: SmartProfile): String? {
        val enabled = profile.points.filter { it.enabled }
        if (enabled.isEmpty()) return "Aktifkan minimal satu titik smart."
        val missingColor = enabled.firstOrNull {
            it.condition == SmartCondition.COLOR_MATCH && !it.colorCaptured
        }
        if (missingColor != null) {
            return "Titik ${missingColor.label} belum punya warna target. Ambil warna dulu."
        }
        return null
    }
}
