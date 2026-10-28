package com.example.autoclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.example.autoclicker.MainActivity
import com.example.autoclicker.data.MacroProfile
import com.example.autoclicker.data.MacroRepository
import com.example.autoclicker.data.MacroPoint
import com.example.autoclicker.data.PointActionType
import com.example.autoclicker.overlay.ClickOverlayController
import com.example.autoclicker.quicksettings.MacroTileService

class AutoClickAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: MacroRepository
    private lateinit var overlayController: ClickOverlayController
    private var isDestroyed = false
    private var actionGeneration = 0L
    private var foregroundPackage: String? = null
    private var macroTargetPackage: String? = null
    private var pendingTileStart = false
    private var pendingTileStartRequestedAt = 0L
    private var pendingTileTargetPackage: String? = null
    private var stoppedForSystemUi = false
    private var systemUiVisible = false
    private var screenReceiverRegistered = false
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            foregroundPackage = null
            pendingTileStart = false
            pendingTileStartRequestedAt = 0L
            pendingTileTargetPackage = null
            stoppedForSystemUi = false
            systemUiVisible = false
            if (::overlayController.isInitialized && overlayController.isMacroActive()) {
                overlayController.stopForScreenOff()
            }
            notifyTileStateChanged()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isDestroyed = false
        foregroundPackage = null
        macroTargetPackage = null
        pendingTileStart = false
        pendingTileStartRequestedAt = 0L
        pendingTileTargetPackage = null
        stoppedForSystemUi = false
        systemUiVisible = false
        instance = this
        registerScreenOffReceiver()
        if (::overlayController.isInitialized) {
            overlayController.stopClicking()
            notifyTileStateChanged()
            return
        }
        repository = MacroRepository(this)
        overlayController = ClickOverlayController(this) { updatedProfile ->
            repository.updateProfilePoints(updatedProfile.name, updatedProfile.points)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        if (currentEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val observedPackage = currentEvent.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val ownActivityOpened = observedPackage == packageName &&
            currentEvent.className?.toString() == MainActivity::class.java.name
        val eligibleTarget = observedPackage != packageName && observedPackage != SYSTEM_UI_PACKAGE

        when {
            ownActivityOpened -> {
                foregroundPackage = null
                systemUiVisible = false
                cancelPendingTileStart()
                if (::overlayController.isInitialized && overlayController.isMacroActive()) {
                    overlayController.stopForForegroundChange()
                }
            }
            observedPackage == SYSTEM_UI_PACKAGE -> {
                systemUiVisible = true
                if (::overlayController.isInitialized && overlayController.isMacroActive()) {
                    stoppedForSystemUi = true
                    overlayController.stopForForegroundChange()
                }
            }
            eligibleTarget -> {
                foregroundPackage = observedPackage
                systemUiVisible = false
                if (!pendingTileStart) stoppedForSystemUi = false
                if (pendingTileStart) {
                    val matchesRequestedTarget = observedPackage == pendingTileTargetPackage &&
                        currentEvent.eventTime > pendingTileStartRequestedAt
                    if (matchesRequestedTarget && canDispatchGestures()) {
                        clearPendingTileStart()
                        overlayController.toggleFromTile()
                        notifyTileStateChanged()
                    } else if (currentEvent.eventTime > pendingTileStartRequestedAt &&
                        observedPackage != pendingTileTargetPackage
                    ) {
                        clearPendingTileStart()
                        notifyTileStateChanged()
                    }
                    return
                }
                val targetPackage = macroTargetPackage
                if (targetPackage != null && observedPackage != targetPackage &&
                    ::overlayController.isInitialized && overlayController.isMacroActive()
                ) {
                    overlayController.stopForForegroundChange()
                }
            }
        }
    }

    override fun onInterrupt() {
        if (::overlayController.isInitialized) overlayController.stopClicking()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlayController.isInitialized) overlayController.onConfigurationChanged()
    }

    override fun onDestroy() {
        isDestroyed = true
        foregroundPackage = null
        macroTargetPackage = null
        pendingTileStart = false
        pendingTileStartRequestedAt = 0L
        pendingTileTargetPackage = null
        stoppedForSystemUi = false
        systemUiVisible = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlayController.isInitialized) overlayController.destroy()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenReceiverRegistered = false
        }
        if (instance === this) instance = null
        notifyTileStateChanged()
        super.onDestroy()
    }

    private fun registerScreenOffReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOffReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun clearPendingTileStart() {
        pendingTileStart = false
        pendingTileStartRequestedAt = 0L
        pendingTileTargetPackage = null
    }

    private fun cancelPendingTileStart() {
        if (!pendingTileStart) return
        clearPendingTileStart()
        notifyTileStateChanged()
    }

    fun showProfile(profile: MacroProfile, onResult: (Boolean) -> Unit = {}) {
        mainHandler.post {
            val shown = !isDestroyed && ::overlayController.isInitialized && overlayController.show(profile)
            if (shown) repository.setActiveProfile(profile.name)
            onResult(shown)
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

    fun toggleFromTile(): Boolean {
        if (isDestroyed || !::overlayController.isInitialized) return false
        if (pendingTileStart) {
            clearPendingTileStart()
            notifyTileStateChanged()
            return true
        }
        if (overlayController.isMacroActive()) return overlayController.toggleFromTile()
        if (stoppedForSystemUi) {
            stoppedForSystemUi = false
            notifyTileStateChanged()
            return true
        }
        if (!overlayController.hasLoadedProfile()) return false
        val requestedTarget = foregroundPackage ?: return false
        macroTargetPackage = null
        pendingTileTargetPackage = requestedTarget
        pendingTileStartRequestedAt = SystemClock.uptimeMillis()
        pendingTileStart = true
        val requestTime = pendingTileStartRequestedAt
        mainHandler.postDelayed({
            if (pendingTileStart && pendingTileStartRequestedAt == requestTime) {
                clearPendingTileStart()
                notifyTileStateChanged()
            }
        }, TILE_START_TIMEOUT_MS)
        notifyTileStateChanged()
        return true
    }

    fun isMacroActive(): Boolean =
        !isDestroyed && (pendingTileStart ||
            (::overlayController.isInitialized && overlayController.isMacroActive()))

    fun isMacroStartPending(): Boolean = !isDestroyed && pendingTileStart

    fun wasStoppedForSystemUi(): Boolean = !isDestroyed && stoppedForSystemUi

    fun hasLoadedProfile(): Boolean =
        !isDestroyed && ::overlayController.isInitialized && overlayController.hasLoadedProfile()

    fun notifyTileStateChanged() {
        MacroTileService.requestRefresh(this)
    }

    fun beginMacroRun(): Boolean {
        if (!canDispatchGestures() || systemUiVisible) return false
        val targetPackage = foregroundPackage ?: return false
        macroTargetPackage = targetPackage
        stoppedForSystemUi = false
        return true
    }

    fun endMacroRun() {
        macroTargetPackage = null
    }

    fun canDispatchGestures(): Boolean =
        !isDestroyed && powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    fun isMacroTargetForeground(): Boolean =
        macroTargetPackage != null && foregroundPackage == macroTargetPackage && !systemUiVisible

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
            ((totalDuration + MAX_SEGMENT_DURATION_MS - 1) / MAX_SEGMENT_DURATION_MS).toInt()
        }

        fun dispatchSegment(index: Int, previousStroke: GestureDescription.StrokeDescription?): Boolean {
            if (generation != actionGeneration || !canDispatchGestures() || !isMacroTargetForeground()) return false
            val segmentStartMs = index * MAX_SEGMENT_DURATION_MS
            val segmentDuration = minOf(MAX_SEGMENT_DURATION_MS, totalDuration - segmentStartMs)
            val startFraction = segmentStartMs.toFloat() / totalDuration
            val endFraction = (segmentStartMs + segmentDuration).toFloat() / totalDuration
            val path = Path().apply {
                val fromX = startX + (endX - startX) * startFraction
                val fromY = startY + (endY - startY) * startFraction
                moveTo(fromX, fromY)
                if (actionType == PointActionType.SWIPE) {
                    lineTo(
                        startX + (endX - startX) * endFraction,
                        startY + (endY - startY) * endFraction
                    )
                }
            }
            val hasMore = index + 1 < segmentCount
            val stroke = if (previousStroke == null) {
                GestureDescription.StrokeDescription(path, 0L, segmentDuration, hasMore)
            } else {
                previousStroke.continueStroke(path, 0L, segmentDuration, hasMore)
            }
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (generation != actionGeneration || isDestroyed) {
                        onCancelled()
                    } else if (hasMore) {
                        if (!dispatchSegment(index + 1, stroke)) onCancelled()
                    } else {
                        onCompleted()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onCancelled()
                }
            }
            return dispatchGesture(gesture, callback, mainHandler)
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
        private const val MAX_SEGMENT_DURATION_MS = 500L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val TILE_START_TIMEOUT_MS = 10_000L
    }
}
