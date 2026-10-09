package com.example.autoclicker.data

enum class PointActionType {
    TAP,
    LONG_PRESS,
    SWIPE
}

data class MacroPoint(
    val id: Long,
    val normalizedX: Float,
    val normalizedY: Float,
    val delayMs: Long,
    val actionType: PointActionType = PointActionType.TAP,
    val actionDurationMs: Long = DEFAULT_ACTION_DURATION_MS,
    val repeatCount: Int = 1,
    val endNormalizedX: Float = 0.65f,
    val endNormalizedY: Float = 0.45f
) {
    companion object {
        const val MIN_ACTION_DURATION_MS = 100L
        const val MIN_LONG_PRESS_DURATION_MS = 600L
        const val MAX_ACTION_DURATION_MS = 60_000L
        const val DEFAULT_ACTION_DURATION_MS = 700L
        const val MIN_REPEAT_COUNT = 1
        const val MAX_REPEAT_COUNT = 1_000
    }
}

data class MacroProfile(
    val name: String,
    val points: List<MacroPoint>,
    val defaultDelayMs: Long = ClickPreferences.DEFAULT_DELAY_MS,
    val loopDelayMs: Long = ClickPreferences.DEFAULT_DELAY_MS,
    val loopLimit: Int = 0,
    val countdownSeconds: Int = 3,
    val keepScreenOn: Boolean = false
) {
    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_LOOP_LIMIT = 1_000_000
        const val MAX_COUNTDOWN_SECONDS = 10
    }
}
