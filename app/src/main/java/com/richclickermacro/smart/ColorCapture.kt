package com.richclickermacro.smart

/**
 * Pure rules for accepting a captured watch colour.
 *
 * Reading a colour from a screenshot is only meaningful if the screen was still
 * while the picture was taken. Our own windows (the toolbar and the settings
 * dialog) are part of that screenshot, and so is any animation in the target app.
 * A single reading cannot tell "this is the button" from "this is the dialog that
 * was fading out" — that is exactly how "Ambil warna ulang" ended up saving a
 * perfectly valid colour of the wrong thing.
 *
 * The fix is to read the same patch twice and require the two readings to agree.
 * A static UI repeats within a couple of levels; a moving one does not.
 */
object ColorCapture {
    /**
     * Largest single-channel difference allowed between two consecutive readings
     * of the same patch before it is treated as "the screen was still changing".
     */
    const val MAX_STABLE_DELTA = 40

    /**
     * True when two readings describe the same colour closely enough to be trusted.
     *
     * The point's own tolerance is used as the slack, so a point that is deliberately
     * set to be insensitive is not forced to be pixel-perfect, but the slack never
     * exceeds [MAX_STABLE_DELTA].
     */
    fun readingsAgree(first: Int, second: Int, tolerance: Int): Boolean {
        val slack = tolerance
            .coerceIn(SmartPoint.MIN_TOLERANCE, SmartPoint.MAX_TOLERANCE)
            .coerceAtMost(MAX_STABLE_DELTA)
        return ColorMatcher.distance(first, second) <= slack
    }

    /**
     * Message for an unstable reading. Names both colours so the user can see the
     * screen really was moving, instead of being told a vague "failed".
     */
    fun unstableMessage(first: Int, second: Int): String =
        "Warna berubah saat dibaca (" +
            ColorMatcher.toHex(first) + " → " + ColorMatcher.toHex(second) + "). " +
            "Layarnya masih bergerak. Arahkan titik ke bagian yang diam, lalu coba lagi."
}
