package com.richclickermacro.data

import android.content.Context

/**
 * Jalur 2: rangkaian titik kedua yang dijalankan otomatis setelah makro pertama
 * (Jalur 1) berjalan selama waktu yang ditentukan. Murni berbasis waktu; tidak
 * membaca warna layar dan tidak menyentuh data makro klasik.
 */
data class Path2Config(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val points: List<MacroPoint> = emptyList()
) {
    val intervalMs: Long get() = intervalMinutes.toLong() * 60_000L

    companion object {
        const val MIN_INTERVAL_MINUTES = 1
        const val MAX_INTERVAL_MINUTES = 720
        const val DEFAULT_INTERVAL_MINUTES = 10

        /**
         * Jumlah titik Jalur 2 tidak dibatasi. Android sendiri yang menjadi batas
         * praktisnya: setiap titik adalah satu jendela overlay, dan bila sistem
         * menolak menambah jendela, aplikasi memberi tahu lewat pesan, bukan diam.
         */
        const val MANY_POINTS_WARNING = 20

        fun sanitizeMinutes(value: Int): Int =
            value.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
    }
}

/**
 * Penghitung lama Jalur 1 berjalan. Waktu hanya bertambah selama Jalur 1
 * benar-benar berjalan, sehingga jeda, pause, dan waktu Jalur 2 tidak ikut
 * terhitung. Semua parameter waktu diberikan pemanggil agar dapat diuji tanpa
 * jam sistem.
 */
class Path2Schedule {
    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    val isRunning: Boolean get() = runningSince != null

    fun resume(nowMs: Long) {
        if (runningSince == null) runningSince = nowMs
    }

    fun pause(nowMs: Long) {
        val since = runningSince ?: return
        accumulatedMs += (nowMs - since).coerceAtLeast(0L)
        runningSince = null
    }

    fun reset() {
        accumulatedMs = 0L
        runningSince = null
    }

    fun elapsedMs(nowMs: Long): Long {
        val since = runningSince ?: return accumulatedMs
        return accumulatedMs + (nowMs - since).coerceAtLeast(0L)
    }

    fun isDue(nowMs: Long, intervalMs: Long): Boolean =
        intervalMs > 0L && elapsedMs(nowMs) >= intervalMs

    fun remainingMs(nowMs: Long, intervalMs: Long): Long =
        (intervalMs - elapsedMs(nowMs)).coerceAtLeast(0L)
}

object Path2Format {
    /** Satu titik per baris, tanpa garis miring agar mudah diperiksa manusia. */
    fun encode(points: List<MacroPoint>): String = points.joinToString(separator = "\n") { point ->
        listOf(
            point.id.toString(),
            point.normalizedX.toString(),
            point.normalizedY.toString(),
            point.delayMs.toString(),
            point.actionType.name,
            point.actionDurationMs.toString(),
            point.repeatCount.toString()
        ).joinToString(separator = "|")
    }

    fun decode(raw: String?): List<MacroPoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line -> decodeLine(line) }
            .filter { it.normalizedX in 0f..1f && it.normalizedY in 0f..1f }
    }

    private fun decodeLine(line: String): MacroPoint? {
        val parts = line.trim().split('|')
        if (parts.size < 4) return null
        val id = parts[0].toLongOrNull() ?: return null
        val x = parts[1].toFloatOrNull() ?: return null
        val y = parts[2].toFloatOrNull() ?: return null
        val delay = parts[3].toLongOrNull() ?: ClickPreferences.DEFAULT_DELAY_MS
        val actionType = parts.getOrNull(4)?.let { name ->
            runCatching { PointActionType.valueOf(name) }.getOrNull()
        } ?: PointActionType.TAP
        val duration = parts.getOrNull(5)?.toLongOrNull() ?: MacroPoint.DEFAULT_ACTION_DURATION_MS
        val repeat = parts.getOrNull(6)?.toIntOrNull() ?: 1
        return MacroPoint(
            id = id,
            normalizedX = x,
            normalizedY = y,
            delayMs = ClickPreferences.sanitizeDelay(delay),
            actionType = actionType,
            actionDurationMs = ClickPreferences.quantizeToStep(
                duration,
                MacroPoint.MIN_ACTION_DURATION_MS,
                MacroPoint.MAX_ACTION_DURATION_MS
            ),
            repeatCount = repeat.coerceIn(MacroPoint.MIN_REPEAT_COUNT, MacroPoint.MAX_REPEAT_COUNT)
        )
    }

    /** "9m 58s" / "58s" — dipakai label sisa waktu di panel Jalur 2. */
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}d" else "${seconds}d"
    }
}

/** Penyimpanan preferensi Jalur 2; terpisah dari skema profil makro klasik. */
class Path2Store(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Path2Config {
        val minutes = Path2Config.sanitizeMinutes(
            preferences.getInt(KEY_INTERVAL_MINUTES, Path2Config.DEFAULT_INTERVAL_MINUTES)
        )
        return Path2Config(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            intervalMinutes = minutes,
            points = Path2Format.decode(preferences.getString(KEY_POINTS, null))
        )
    }

    fun save(config: Path2Config) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_INTERVAL_MINUTES, Path2Config.sanitizeMinutes(config.intervalMinutes))
            .putString(KEY_POINTS, Path2Format.encode(config.points))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "jalur_2_preferences"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"
        const val KEY_POINTS = "points"
    }
}
