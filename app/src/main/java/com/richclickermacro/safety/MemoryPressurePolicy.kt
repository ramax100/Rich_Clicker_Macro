package com.richclickermacro.safety

/**
 * Legacy Android memory-pressure policy, unit-testable without a device.
 *
 * RUNNING_CRITICAL means the device is extremely low on memory. UI_HIDDEN,
 * BACKGROUND, MODERATE and COMPLETE describe the process's own lifecycle/LRU
 * position, not proof that the foreground game needs to be protected by stopping.
 * Android 14+ no longer delivers these pressure levels; this is best-effort,
 * not a universal low-RAM detector. onLowMemory is handled separately for old OSes.
 */
object MemoryPressurePolicy {
    const val TRIM_RUNNING_MODERATE = 5
    const val TRIM_RUNNING_LOW = 10
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_UI_HIDDEN = 20
    const val TRIM_BACKGROUND = 40
    const val TRIM_MODERATE = 60
    const val TRIM_COMPLETE = 80

    fun shouldStopMacro(trimLevel: Int): Boolean = trimLevel == TRIM_RUNNING_CRITICAL

    fun describe(trimLevel: Int): String = when (trimLevel) {
        TRIM_RUNNING_MODERATE -> "running_moderate"
        TRIM_RUNNING_LOW -> "running_low"
        TRIM_RUNNING_CRITICAL -> "running_critical"
        TRIM_UI_HIDDEN -> "ui_hidden"
        TRIM_BACKGROUND -> "background"
        TRIM_MODERATE -> "moderate"
        TRIM_COMPLETE -> "complete"
        else -> "level_$trimLevel"
    }
}
