package com.richclickermacro.smart

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure colour maths. No Android types so it can be unit-tested without a device.
 * Colours are 0xAARRGGBB integers; alpha is always ignored because a screenshot
 * region may be reported with a non-opaque alpha even when the pixel is solid.
 */
object ColorMatcher {
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    /** Chebyshev distance: the largest single-channel difference, 0..255. */
    fun distance(a: Int, b: Int): Int = max(
        abs(red(a) - red(b)),
        max(abs(green(a) - green(b)), abs(blue(a) - blue(b)))
    )

    fun isMatch(sample: Int, target: Int, tolerance: Int): Boolean =
        distance(sample, target) <= tolerance.coerceIn(0, 255)

    fun isChanged(sample: Int, baseline: Int, tolerance: Int): Boolean =
        distance(sample, baseline) > tolerance.coerceIn(0, 255)

    /**
     * Mean colour of a small patch. Averaging suppresses single-pixel noise from
     * animation, scaling and colour dithering. Returns null for an empty patch so
     * the caller can treat it as a failed sample instead of a black pixel.
     */
    fun average(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        var r = 0L
        var g = 0L
        var b = 0L
        for (pixel in pixels) {
            r += red(pixel)
            g += green(pixel)
            b += blue(pixel)
        }
        val count = pixels.size
        return (0xFF shl 24) or
            (((r + count / 2) / count).toInt().coerceIn(0, 255) shl 16) or
            (((g + count / 2) / count).toInt().coerceIn(0, 255) shl 8) or
            ((b + count / 2) / count).toInt().coerceIn(0, 255)
    }

    fun toHex(color: Int): String =
        "#" + ((color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0'))

    /** Accepts #RGB, #RRGGBB and #AARRGGBB; alpha is forced opaque. */
    fun parseHex(value: String): Int? {
        val text = value.trim().removePrefix("#")
        val expanded = when (text.length) {
            3 -> text.map { "$it$it" }.joinToString("")
            6, 8 -> text
            else -> return null
        }
        val parsed = expanded.toLongOrNull(16) ?: return null
        return (0xFF000000.toInt()) or (parsed.toInt() and 0xFFFFFF)
    }
}
