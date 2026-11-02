package com.richclickermacro.safety

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display-local layout values and absolute screen coordinates are intentionally kept separate.
 * On API 30+ WindowMetrics bounds do not necessarily start at (0, 0).
 */
data class ScreenFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val rotation: Int
) {
    init {
        require(width > 0 && height > 0) { "Display dimensions must be positive" }
        require(rotation in 0..3) { "Invalid display rotation" }
    }

    fun centerX(normalizedX: Float): Float = left + normalizedX.coerceIn(0f, 1f) * width

    fun centerY(normalizedY: Float): Float = top + normalizedY.coerceIn(0f, 1f) * height
}

object MarkerCoordinates {
    /** Allows sub-pixel rounding, but rejects status-bar/cutout-sized offsets. */
    const val ALIGNMENT_TOLERANCE_PX = 2f

    fun localLeft(normalizedX: Float, frame: ScreenFrame, markerSize: Int): Int =
        (normalizedX.coerceIn(0f, 1f) * frame.width - markerSize / 2f)
            .roundToInt()
            .coerceIn(-markerSize / 2, frame.width - markerSize / 2)

    fun localTop(normalizedY: Float, frame: ScreenFrame, markerSize: Int): Int =
        (normalizedY.coerceIn(0f, 1f) * frame.height - markerSize / 2f)
            .roundToInt()
            .coerceIn(-markerSize / 2, frame.height - markerSize / 2)

    fun normalizedX(localCenterX: Float, frame: ScreenFrame): Float =
        (localCenterX / frame.width).coerceIn(0f, 1f)

    fun normalizedY(localCenterY: Float, frame: ScreenFrame): Float =
        (localCenterY / frame.height).coerceIn(0f, 1f)

    fun isAligned(
        frame: ScreenFrame,
        normalizedX: Float,
        normalizedY: Float,
        actualLeft: Int,
        actualTop: Int,
        markerWidth: Int,
        markerHeight: Int
    ): Boolean {
        if (markerWidth <= 0 || markerHeight <= 0) return false
        val actualCenterX = actualLeft + markerWidth / 2f
        val actualCenterY = actualTop + markerHeight / 2f
        return abs(actualCenterX - frame.centerX(normalizedX)) <= ALIGNMENT_TOLERANCE_PX &&
            abs(actualCenterY - frame.centerY(normalizedY)) <= ALIGNMENT_TOLERANCE_PX
    }
}

/**
 * Uses only window-transition metadata already delivered by AccessibilityService.
 * It never requests window content, screenshots, or the list of installed packages.
 */
object ForegroundWindowClassifier {
    private val TRANSIENT_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.credentialmanager",
        "com.google.android.credentialmanager",
        "com.android.biometric",
        "com.android.internal.app",
        "com.android.server.am"
    )

    private val TRANSIENT_CLASS_PREFIXES = listOf(
        "android.inputmethodservice.",
        "android.widget.Toast",
        "android.app.Dialog",
        "android.widget.PopupWindow",
        "android.service.chooser.ChooserActivity",
        "com.android.internal.app.ChooserActivity",
        "com.android.internal.app.ResolverActivity"
    )

    fun isApplicationWindow(
        packageName: String?,
        className: String?,
        ownPackage: String,
        mainActivityName: String,
        inputMethodPackage: String?
    ): Boolean {
        if (packageName.isNullOrBlank() || className.isNullOrBlank()) return false

        // Our overlay dialogs are not an application switch; opening our main activity is.
        if (packageName == ownPackage) return className == mainActivityName

        // Android 12 permission/biometric/installer dialogs and the shade/IME are
        // transient system windows, not a real switch away from the target game.
        if (packageName in TRANSIENT_SYSTEM_PACKAGES ||
            packageName.endsWith(".systemui") ||
            packageName == inputMethodPackage
        ) {
            return false
        }
        if (TRANSIENT_CLASS_PREFIXES.any { className.startsWith(it) }) return false
        return true
    }
}
