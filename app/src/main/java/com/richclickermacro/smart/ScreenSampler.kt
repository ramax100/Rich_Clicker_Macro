package com.richclickermacro.smart

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.richclickermacro.diagnostics.MacroDiagnostics
import java.util.concurrent.Executor

/**
 * Reads small colour patches from the display using the accessibility screenshot API.
 *
 * Design constraints that matter on a real device:
 *  - API 30+ only, and the service must declare canTakeScreenshot.
 *  - The platform rejects requests spaced closer than 333 ms, so calls are paced.
 *  - One screenshot serves every watched point; we never capture once per point.
 *  - The HardwareBuffer is always closed, and every intermediate Bitmap recycled,
 *    including on the failure paths.
 *  - Only a tiny crop is copied to software memory. A full-frame ARGB copy is a
 *    last-resort fallback, never the normal path.
 *
 * No image is ever written to disk or placed in the diagnostics log.
 *
 * Every API 30 call lives in [Capture30], which is only constructed after an
 * explicit SDK check, so lint can prove the guard instead of trusting a comment.
 */
class ScreenSampler(
    private val service: AccessibilityService,
    private val mainHandler: Handler,
    private val executor: Executor
) {
    private var inFlight = false
    private var lastRequestAtMs = 0L
    private var consecutiveFailures = 0

    /** Size of the most recent successful capture, for frame-agreement checks. */
    var lastCaptureWidth: Int = 0
        private set
    var lastCaptureHeight: Int = 0
        private set

    val isBusy: Boolean get() = inFlight

    /** Returns a user-facing reason when sampling is impossible, or null when ready. */
    fun capabilityProblem(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Multi Smart Click butuh Android 11 atau lebih baru."
        }
        val info = service.serviceInfo
        if (info == null || info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
            return "Izin baca layar belum aktif. Matikan lalu nyalakan ulang layanan aksesibilitas Rich Clicker."
        }
        return null
    }

    fun reset() {
        consecutiveFailures = 0
    }

    fun failureCount(): Int = consecutiveFailures

    /**
     * @param patches point id -> normalized centre of the region to read
     * @param forbidden normalized display rectangles owned by our own overlay; a
     * patch overlapping them would read our marker instead of the game, so it is refused.
     * @param onResult delivered on the main thread; a null map means the capture failed.
     */
    fun sample(
        patches: Map<Long, Pair<Float, Float>>,
        forbidden: List<SmartGeometry.Box>,
        onResult: (Map<Long, Int?>?, String?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null, "Multi Smart Click butuh Android 11 atau lebih baru.")
            return
        }
        val problem = capabilityProblem()
        if (problem != null) {
            onResult(null, problem)
            return
        }
        if (inFlight) {
            onResult(null, "busy")
            return
        }
        if (MIN_REQUEST_GAP_MS - (SystemClock.uptimeMillis() - lastRequestAtMs) > 0) {
            onResult(null, "paced")
            return
        }
        if (patches.isEmpty()) {
            onResult(emptyMap(), null)
            return
        }
        inFlight = true
        lastRequestAtMs = SystemClock.uptimeMillis()
        var settled = false
        val watchdog = Runnable {
            if (!settled) {
                settled = true
                inFlight = false
                consecutiveFailures++
                MacroDiagnostics.record(service, "smart_screenshot_timeout", "")
                onResult(null, "Waktu ambil layar habis.")
            }
        }
        mainHandler.postDelayed(watchdog, SCREENSHOT_TIMEOUT_MS)

        val accepted = runCatching {
            Capture30(service, executor, patches, forbidden) { w, h ->
                lastCaptureWidth = w
                lastCaptureHeight = h
            }.request { result, error ->
                mainHandler.post {
                    if (settled) return@post
                    settled = true
                    mainHandler.removeCallbacks(watchdog)
                    inFlight = false
                    if (result == null) consecutiveFailures++ else consecutiveFailures = 0
                    onResult(result, error)
                }
            }
        }
        if (accepted.isFailure) {
            mainHandler.removeCallbacks(watchdog)
            if (!settled) {
                settled = true
                inFlight = false
                consecutiveFailures++
                MacroDiagnostics.record(
                    service,
                    "smart_screenshot_exception",
                    accepted.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                )
                onResult(null, "Sistem menolak pengambilan layar.")
            }
        }
    }

    /** The only code that touches the screenshot API. */
    @RequiresApi(Build.VERSION_CODES.R)
    private class Capture30(
        private val service: AccessibilityService,
        private val executor: Executor,
        private val patches: Map<Long, Pair<Float, Float>>,
        private val forbidden: List<SmartGeometry.Box>,
        private val onSize: (Int, Int) -> Unit
    ) {
        private var usedFallback = false

        fun request(onResult: (Map<Long, Int?>?, String?) -> Unit) {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val parsed = runCatching { extract(result) }
                        parsed.onFailure {
                            MacroDiagnostics.record(service, "smart_sample_error", it.javaClass.simpleName)
                        }
                        val value = parsed.getOrNull()
                        onResult(value, if (value == null) "Gagal membaca layar." else null)
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = describeError(errorCode)
                        if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            MacroDiagnostics.record(service, "smart_screenshot_failed", reason)
                        }
                        onResult(null, reason)
                    }
                }
            )
        }

        /** Runs on the supplied executor, never on the main thread. */
        private fun extract(result: AccessibilityService.ScreenshotResult): Map<Long, Int?> {
            val buffer = result.hardwareBuffer
            var wrapped: Bitmap? = null
            var fullCopy: Bitmap? = null
            try {
                val width = buffer.width
                val height = buffer.height
                if (width <= 0 || height <= 0) return patches.mapValues { null }
                onSize(width, height)
                val source = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    ?: return patches.mapValues { null }
                wrapped = source
                val output = LinkedHashMap<Long, Int?>(patches.size)
                for ((id, position) in patches) {
                    output[id] = runCatching {
                        readPatch(source, width, height, position) {
                            // Only reached when cropping a HARDWARE bitmap is unsupported.
                            if (fullCopy == null) {
                                fullCopy = source.copy(Bitmap.Config.ARGB_8888, false)
                                if (!usedFallback) {
                                    usedFallback = true
                                    MacroDiagnostics.record(service, "smart_sample_fallback", "full_frame_copy")
                                }
                            }
                            fullCopy ?: throw IllegalStateException("software copy unavailable")
                        }
                    }.getOrNull()
                }
                return output
            } finally {
                runCatching { fullCopy?.recycle() }
                runCatching { wrapped?.recycle() }
                runCatching { buffer.close() }
            }
        }

        private inline fun readPatch(
            source: Bitmap,
            width: Int,
            height: Int,
            position: Pair<Float, Float>,
            fallback: () -> Bitmap
        ): Int? {
            // Must use the SAME mapping as the gesture: normalized * size, then clamp.
            // Using (size - 1) here drifted up to two pixels from the clicked position
            // near the right/bottom edge, so the watched colour was not the clicked spot.
            val centerX = (position.first.coerceIn(0f, 1f) * width).toInt().coerceIn(0, width - 1)
            val centerY = (position.second.coerceIn(0f, 1f) * height).toInt().coerceIn(0, height - 1)
            val half = PATCH_SIZE_PX / 2
            val left = (centerX - half).coerceIn(0, width - 1)
            val top = (centerY - half).coerceIn(0, height - 1)
            val right = (left + PATCH_SIZE_PX).coerceAtMost(width)
            val bottom = (top + PATCH_SIZE_PX).coerceAtMost(height)
            if (right <= left || bottom <= top) return null

            // Our own overlay is part of the screenshot. Refuse rather than sample it.
            val patch = SmartGeometry.Box(
                left.toFloat() / width, top.toFloat() / height,
                right.toFloat() / width, bottom.toFloat() / height
            )
            if (SmartGeometry.isPatchObscured(patch, forbidden)) return null

            var crop: Bitmap? = null
            var software: Bitmap? = null
            try {
                val created = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                crop = created
                var readable = created
                if (readable.config == Bitmap.Config.HARDWARE) {
                    software = readable.copy(Bitmap.Config.ARGB_8888, false)
                    readable = software ?: return null
                }
                val pixels = IntArray(readable.width * readable.height)
                readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
                return ColorMatcher.average(pixels)
            } catch (error: RuntimeException) {
                val copy = fallback()
                val pixels = IntArray((right - left) * (bottom - top))
                copy.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
                return ColorMatcher.average(pixels)
            } finally {
                runCatching { software?.recycle() }
                runCatching { if (crop !== source) crop?.recycle() }
            }
        }

        private fun describeError(code: Int): String = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "paced"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                "Layar diproteksi aplikasi (FLAG_SECURE), tidak bisa dibaca."
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "Layanan aksesibilitas tidak aktif."
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Display tidak valid."
            else -> "Gagal mengambil layar (kode $code)."
        }
    }

    companion object {
        /** Platform minimum is 333 ms; keep a margin. */
        const val MIN_REQUEST_GAP_MS = 360L
        const val PATCH_SIZE_PX = 6
        const val SCREENSHOT_TIMEOUT_MS = 2_500L
        const val MAX_CONSECUTIVE_FAILURES = 8
    }
}
