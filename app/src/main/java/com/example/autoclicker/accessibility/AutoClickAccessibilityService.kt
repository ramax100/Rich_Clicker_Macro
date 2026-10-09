package com.example.autoclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isDestroyed = false
        instance = this
        repository = MacroRepository(this)
        overlayController = ClickOverlayController(this) { updatedProfile ->
            repository.updateProfilePoints(updatedProfile.name, updatedProfile.points)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (::overlayController.isInitialized) overlayController.stopClicking()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlayController.isInitialized) overlayController.onConfigurationChanged()
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlayController.isInitialized) overlayController.destroy()
        if (instance === this) instance = null
        notifyTileStateChanged()
        super.onDestroy()
    }

    fun showProfile(profile: MacroProfile) {
        mainHandler.post {
            if (!isDestroyed && ::overlayController.isInitialized) {
                repository.setActiveProfile(profile.name)
                overlayController.show(profile)
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
            if (generation != actionGeneration || isDestroyed) return false
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
    }
}
