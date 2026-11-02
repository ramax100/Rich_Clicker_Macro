package com.richclickermacro.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.richclickermacro.data.MacroProfile
import com.richclickermacro.data.MacroRepository
import com.richclickermacro.data.MacroPoint
import com.richclickermacro.data.PointActionType
import com.richclickermacro.MainActivity
import com.richclickermacro.diagnostics.MacroDiagnostics
import com.richclickermacro.safety.ForegroundWindowClassifier
import com.richclickermacro.safety.GestureSafety
import com.richclickermacro.safety.MemoryPressurePolicy
import com.richclickermacro.overlay.ClickOverlayController
import com.richclickermacro.quicksettings.MacroTileService
import com.richclickermacro.smart.ScreenSampler
import com.richclickermacro.smart.SmartOverlayController
import com.richclickermacro.smart.SmartProfile
import com.richclickermacro.smart.SmartRepository
import java.util.concurrent.Executors

class AutoClickAccessibilityService : AccessibilityService(), ComponentCallbacks2 {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: MacroRepository
    private lateinit var overlayController: ClickOverlayController
    private lateinit var smartRepository: SmartRepository
    private lateinit var smartController: SmartOverlayController
    // Single worker: screenshots are decoded off the main thread and serialized.
    private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smart-sampler").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var isDestroyed = false
    private var actionGeneration = 0L
    @Volatile private var lastForegroundPackage: String? = null
    // StrokeDescription copies this Path; reuse avoids allocation on every segment.
    private val reusablePath = Path()
    private var memorySamplingActive = false

    private val memorySampler = object : Runnable {
        override fun run() {
            if (isDestroyed || !memorySamplingActive) return
            recordMemory("memory_sample")
            mainHandler.postDelayed(this, MEMORY_SAMPLE_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isDestroyed = false
        instance = this
        repository = MacroRepository(this)
        smartRepository = SmartRepository(this)
        MacroDiagnostics.record(this, "service_connected")
        recordMemory("service_memory_baseline")
        overlayController = ClickOverlayController(this) { updatedProfile ->
            runCatching {
                repository.updateProfilePoints(updatedProfile.name, updatedProfile.points)
            }.getOrElse {
                MacroDiagnostics.record(this, "profile_persist_failed", it.javaClass.simpleName)
                null
            }
        }
        val sampler = ScreenSampler(this, mainHandler, screenshotExecutor)
        smartController = SmartOverlayController(this, sampler) { updatedProfile ->
            runCatching {
                smartRepository.updateProfile(updatedProfile)
            }.getOrElse {
                MacroDiagnostics.record(this, "smart_profile_persist_failed", it.javaClass.simpleName)
                null
            }
        }
    }

    /**
     * Only one runner may inject gestures at a time. Starting either mode stops the
     * other, so the two features can never fight over the same screen.
     */
    fun requestExclusiveRun(smart: Boolean) {
        if (smart) {
            if (::overlayController.isInitialized && overlayController.isMacroActive()) {
                overlayController.stopClicking("smart_mode_preempt")
            }
        } else {
            if (::smartController.isInitialized && smartController.isRunning()) {
                smartController.stop("classic_mode_preempt")
            }
        }
    }

    fun showSmartProfile(profile: SmartProfile) {
        mainHandler.post {
            if (!isDestroyed && ::smartController.isInitialized) {
                runCatching {
                    smartRepository.setActiveProfile(profile.name)
                    smartController.show(profile)
                }.onFailure {
                    MacroDiagnostics.record(this, "smart_show_failed", it.javaClass.simpleName)
                }
            }
        }
    }

    fun notifySmartProfileSaved(previousName: String, profile: SmartProfile) {
        mainHandler.post {
            if (!isDestroyed && ::smartController.isInitialized) {
                if (previousName == profile.name) {
                    smartController.updateProfileSettings(profile)
                } else {
                    smartController.closeIfProfile(previousName)
                }
            }
        }
    }

    fun notifySmartProfileDeleted(name: String) {
        mainHandler.post {
            if (!isDestroyed && ::smartController.isInitialized) smartController.closeIfProfile(name)
        }
    }

    fun isSmartActive(): Boolean =
        !isDestroyed && ::smartController.isInitialized && smartController.isRunning()

    fun hasLoadedSmartProfile(): Boolean =
        !isDestroyed && ::smartController.isInitialized && smartController.hasLoadedProfile()

    fun toggleSmartFromTile(): Boolean =
        !isDestroyed && ::smartController.isInitialized && smartController.toggleFromTile()

    fun smartWindowCount(): Int =
        if (::smartController.isInitialized) smartController.overlayWindowCount() else 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handleAccessibilityEvent(event) }.onFailure {
            MacroDiagnostics.record(this, "accessibility_event_error", it.javaClass.simpleName)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        val inputMethodPackage = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it)?.packageName }
        }.getOrNull()
        if (!ForegroundWindowClassifier.isApplicationWindow(
                packageName, className, this.packageName,
                ComponentName(this, MainActivity::class.java).className, inputMethodPackage
            )
        ) return

        val observedPackage = packageName ?: return
        val previousPackage = lastForegroundPackage
        lastForegroundPackage = observedPackage
        if (previousPackage != null && previousPackage != observedPackage) {
            MacroDiagnostics.record(this, "app_window_changed", "from=$previousPackage to=$observedPackage")
            if (::overlayController.isInitialized) {
                overlayController.onApplicationWindowChanged(observedPackage)
            }
            if (::smartController.isInitialized) {
                smartController.onApplicationWindowChanged(observedPackage)
            }
        }
    }

    fun foregroundPackage(): String? = lastForegroundPackage

    override fun onInterrupt() {
        MacroDiagnostics.record(this, "service_interrupt")
        if (::overlayController.isInitialized) overlayController.stopClicking("service_interrupt")
        if (::smartController.isInitialized) smartController.stop("service_interrupt")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching {
            if (::overlayController.isInitialized) overlayController.onConfigurationChanged()
            if (::smartController.isInitialized) smartController.onConfigurationChanged()
        }.onFailure {
            MacroDiagnostics.record(this, "configuration_change_error", it.javaClass.simpleName)
        }
    }

    /**
     * Best-effort yielding on older Android versions. Normal background/LRU trim
     * levels do not stop a macro. Android 14+ does not deliver pressure levels,
     * so periodic measurements are still needed; this is not a crash prevention guarantee.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val description = MemoryPressurePolicy.describe(level)
        MacroDiagnostics.record(this, "trim_memory", description)
        if (MemoryPressurePolicy.shouldStopMacro(level)) {
            recordMemory("memory_pressure")
            if (::overlayController.isInitialized) {
                overlayController.onSystemMemoryPressure(description)
            }
            if (::smartController.isInitialized) {
                smartController.onSystemMemoryPressure(description)
            }
        }
    }

    @Deprecated("Android lawas: onTrimMemory adalah callback pengganti.")
    override fun onLowMemory() {
        super.onLowMemory()
        recordMemory("low_memory")
        if (::overlayController.isInitialized) {
            overlayController.onSystemMemoryPressure("onLowMemory")
        }
        if (::smartController.isInitialized) {
            smartController.onSystemMemoryPressure("onLowMemory")
        }
    }

    fun overlayWindowCount(): Int =
        (if (::overlayController.isInitialized) overlayController.overlayWindowCount() else 0) + smartWindowCount()

    fun recordMemory(event: String) {
        // Only collect the window count on the main thread. Expensive PSS reads
        // and file writes run on the single bounded diagnostics worker.
        MacroDiagnostics.recordMemory(this, event, overlayWindowCount())
    }

    fun startMemorySampling() {
        if (memorySamplingActive) return
        memorySamplingActive = true
        recordMemory("macro_memory_start")
        mainHandler.postDelayed(memorySampler, MEMORY_SAMPLE_INTERVAL_MS)
    }

    fun stopMemorySampling() {
        if (!memorySamplingActive) return
        memorySamplingActive = false
        mainHandler.removeCallbacks(memorySampler)
        recordMemory("macro_memory_stop")
    }

    override fun onDestroy() {
        stopMemorySampling()
        isDestroyed = true
        lastForegroundPackage = null
        MacroDiagnostics.record(this, "service_destroyed")
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlayController.isInitialized) overlayController.destroy()
        if (::smartController.isInitialized) smartController.destroy()
        runCatching { screenshotExecutor.shutdownNow() }
        if (instance === this) instance = null
        notifyTileStateChanged()
        super.onDestroy()
    }

    fun showProfile(profile: MacroProfile) {
        mainHandler.post {
            if (!isDestroyed && ::overlayController.isInitialized) {
                runCatching {
                    repository.setActiveProfile(profile.name)
                    overlayController.show(profile)
                }.onFailure {
                    MacroDiagnostics.record(this, "show_profile_failed", it.javaClass.simpleName)
                }
            }
        }
    }

    fun notifyProfileSaved(previousName: String, profile: MacroProfile) {
        mainHandler.post {
            if (!isDestroyed && ::overlayController.isInitialized) {
                if (previousName == profile.name) {
                    overlayController.updateProfileSettings(profile)
                } else {
                    overlayController.closeIfProfile(previousName)
                }
            }
        }
    }

    fun notifyProfileDeleted(profileName: String) {
        mainHandler.post {
            if (!isDestroyed && ::overlayController.isInitialized) {
                overlayController.closeIfProfile(profileName)
            }
        }
    }

    fun toggleFromTile(): Boolean =
        !isDestroyed && ::overlayController.isInitialized && overlayController.toggleFromTile()

    fun isMacroActive(): Boolean =
        !isDestroyed && ::overlayController.isInitialized && overlayController.isMacroActive()

    fun hasLoadedProfile(): Boolean =
        !isDestroyed && ::overlayController.isInitialized && overlayController.hasLoadedProfile()

    fun notifyTileStateChanged() {
        MacroTileService.requestRefresh(this)
    }

    fun cancelActiveAction() {
        actionGeneration++
    }

    fun performAction(
        actionType: PointActionType,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit
    ): Boolean {
        if (isDestroyed) return false
        if (!GestureSafety.isValidGesture(startX, startY, endX, endY, durationMs)) {
            MacroDiagnostics.record(this, "gesture_invalid_input", "action=$actionType")
            return false
        }
        val totalDuration = when (actionType) {
            PointActionType.TAP -> TAP_DURATION_MS
            PointActionType.LONG_PRESS -> durationMs.coerceIn(
                MacroPoint.MIN_LONG_PRESS_DURATION_MS,
                MacroPoint.MAX_ACTION_DURATION_MS
            )
            PointActionType.SWIPE -> durationMs.coerceIn(
                MacroPoint.MIN_ACTION_DURATION_MS,
                MacroPoint.MAX_ACTION_DURATION_MS
            )
        }
        val generation = ++actionGeneration
        val segmentCount = if (actionType == PointActionType.TAP) {
            1
        } else {
            GestureSafety.segmentCount(totalDuration)
        }

        fun dispatchSegment(index: Int, previousStroke: GestureDescription.StrokeDescription?): Boolean {
            if (generation != actionGeneration || isDestroyed) return false
            var watchdog: Runnable? = null
            return try {
                val segmentStartMs = index * GestureSafety.MAX_SEGMENT_DURATION_MS
                val segmentDuration = minOf(GestureSafety.MAX_SEGMENT_DURATION_MS, totalDuration - segmentStartMs)
                val startFraction = segmentStartMs.toFloat() / totalDuration
                val endFraction = (segmentStartMs + segmentDuration).toFloat() / totalDuration
                val path = reusablePath.apply {
                    reset()
                    val fromX = startX + (endX - startX) * startFraction
                    val fromY = startY + (endY - startY) * startFraction
                    moveTo(fromX, fromY)
                    if (actionType == PointActionType.SWIPE) {
                        lineTo(
                            startX + (endX - startX) * endFraction,
                            startY + (endY - startY) * endFraction
                        )
                    } else {
                        // An explicit stationary contour avoids an empty moveTo-only path.
                        lineTo(fromX, fromY)
                    }
                }
                val hasMore = index + 1 < segmentCount
                val stroke = if (previousStroke == null) {
                    GestureDescription.StrokeDescription(path, 0L, segmentDuration, hasMore)
                } else {
                    previousStroke.continueStroke(path, 0L, segmentDuration, hasMore)
                }
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                var settled = false
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        watchdog?.let(mainHandler::removeCallbacks)
                        if (settled) return
                        settled = true
                        if (generation != actionGeneration || isDestroyed) {
                            onCancelled()
                        } else if (hasMore) {
                            if (!dispatchSegment(index + 1, stroke)) onCancelled()
                        } else {
                            onCompleted()
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        watchdog?.let(mainHandler::removeCallbacks)
                        if (settled) return
                        settled = true
                        onCancelled()
                    }
                }
                watchdog = Runnable {
                    if (!settled && generation == actionGeneration && !isDestroyed) {
                        settled = true
                        MacroDiagnostics.record(
                            this@AutoClickAccessibilityService,
                            "gesture_callback_timeout",
                            "segment=${index + 1}/$segmentCount duration=$segmentDuration"
                        )
                        onCancelled()
                    }
                }
                mainHandler.postDelayed(watchdog!!, GestureSafety.callbackTimeoutMs(segmentDuration))
                val accepted = dispatchGesture(gesture, callback, mainHandler)
                if (!accepted) {
                    mainHandler.removeCallbacks(watchdog!!)
                    settled = true
                    MacroDiagnostics.record(
                        this@AutoClickAccessibilityService,
                        "gesture_dispatch_rejected",
                        "segment=${index + 1}/$segmentCount"
                    )
                }
                accepted
            } catch (error: RuntimeException) {
                watchdog?.let(mainHandler::removeCallbacks)
                MacroDiagnostics.record(
                    this,
                    "gesture_dispatch_exception",
                    "${error.javaClass.simpleName}: ${error.message}"
                )
                false
            }
        }

        return dispatchSegment(0, null)
    }

    companion object {
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, AutoClickAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabledServices
                .split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == expectedComponent }
        }

        private const val TAP_DURATION_MS = 40L
        private const val MEMORY_SAMPLE_INTERVAL_MS = 60_000L
        private const val MAX_SEGMENT_DURATION_MS = 500L
    }
}
