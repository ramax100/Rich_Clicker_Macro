package com.example.autoclicker.data

import android.content.Context

class ClickPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var defaultDelayMs: Long
        get() = sanitizeDelay(preferences.getLong(KEY_INTERVAL_MS, DEFAULT_DELAY_MS))
        set(value) {
            preferences.edit().putLong(KEY_INTERVAL_MS, sanitizeDelay(value)).apply()
        }

    companion object {
        const val MIN_DELAY_MS = 100L
        const val MAX_DELAY_MS = 3_600_000L
        const val DEFAULT_DELAY_MS = 1_000L
        const val DELAY_STEP_MS = 100L
        const val LONG_DELAY_STEP_MS = 1_000L

        fun sanitizeDelay(value: Long): Long {
            val clamped = value.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            return (((clamped + DELAY_STEP_MS / 2) / DELAY_STEP_MS) * DELAY_STEP_MS)
                .coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        }

        fun quantizeToStep(value: Long, minimum: Long, maximum: Long): Long {
            val clamped = value.coerceIn(minimum, maximum)
            return (((clamped + DELAY_STEP_MS / 2) / DELAY_STEP_MS) * DELAY_STEP_MS)
                .coerceIn(minimum, maximum)
        }

        fun parseDelaySeconds(rawValue: String): Long? {
            val normalized = rawValue.trim().replace(',', '.')
            if (!normalized.matches(Regex("\\d+(\\.\\d)?"))) return null

            val parts = normalized.split('.')
            val seconds = parts[0].toLongOrNull() ?: return null
            val tenths = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if (seconds > 3_600L) return null

            val value = seconds * 1_000L + tenths * 100L
            return value.takeIf { it in MIN_DELAY_MS..MAX_DELAY_MS }
        }

        fun formatDelaySeconds(delayMs: Long): String {
            val sanitized = sanitizeDelay(delayMs)
            val seconds = sanitized / 1_000L
            val tenths = (sanitized % 1_000L) / 100L
            return "$seconds.$tenths"
        }

        private const val PREFERENCES_NAME = "auto_clicker_preferences"
        private const val KEY_INTERVAL_MS = "interval_ms"
    }
}
