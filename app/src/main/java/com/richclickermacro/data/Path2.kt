package com.richclickermacro.data

import android.content.Context

/**
 * Satu titik Jalur 2 pada mode Multi Smart Click.
 *
 * Cara kerjanya sama dengan titik smart biasa: warna pixel di titik itulah yang
 * menentukan kapan klik dikirim, bukan waktu. Yang membedakan hanya kapan
 * rangkaian ini dijalankan: setelah smart click (Jalur 1) berjalan beberapa menit.
 */
data class Path2Point(
    val id: Long,
    val normalizedX: Float,
    val normalizedY: Float,
    /** Warna acuan hasil "Ambil Warna"; null berarti titik belum siap dipakai. */
    val targetColor: Int? = null,
    val tolerance: Int = DEFAULT_TOLERANCE,
    /** Ketuk singkat atau tahan. */
    val isHold: Boolean = false,
    val holdMs: Long = DEFAULT_HOLD_MS,
    /** Berapa kali diklik setiap kali warnanya cocok. */
    val repeatCount: Int = 1,
    /** Jeda setelah aksi sebelum lanjut ke titik Jalur 2 berikutnya. */
    val delayMs: Long = DEFAULT_DELAY_MS
) {
    val isReady: Boolean get() = targetColor != null

    companion object {
        const val MIN_TOLERANCE = 1
        const val MAX_TOLERANCE = 120
        const val DEFAULT_TOLERANCE = 24
        const val MIN_HOLD_MS = 600L
        const val MAX_HOLD_MS = 10_000L
        const val DEFAULT_HOLD_MS = 700L
        const val MIN_REPEAT_COUNT = 1
        const val MAX_REPEAT_COUNT = 1_000
        const val MIN_DELAY_MS = 100L
        const val MAX_DELAY_MS = 60_000L
        const val DEFAULT_DELAY_MS = 1_000L

        fun sanitize(point: Path2Point): Path2Point = point.copy(
            normalizedX = point.normalizedX.coerceIn(0f, 1f),
            normalizedY = point.normalizedY.coerceIn(0f, 1f),
            tolerance = point.tolerance.coerceIn(MIN_TOLERANCE, MAX_TOLERANCE),
            holdMs = point.holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS),
            repeatCount = point.repeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT),
            delayMs = point.delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        )
    }
}

/**
 * Jalur 2: rangkaian titik kedua pada mode Multi Smart Click. Dipicu waktu, lalu
 * tiap titiknya menunggu warna pixel yang cocok sebelum diklik.
 */
data class Path2Config(
    val enabled: Boolean = false,
    /**
     * Jalur 2 dipicu oleh JUMLAH PUTARAN Jalur 1, bukan waktu. Setelah Jalur 1
     * menyelesaikan sebanyak ini putaran, Jalur 2 berjalan (titik 1 → terakhir),
     * lalu kembali ke Jalur 1, dan seterusnya.
     */
    val everyLoops: Int = DEFAULT_EVERY_LOOPS,
    val points: List<Path2Point> = emptyList()
) {
    companion object {
        const val MIN_EVERY_LOOPS = 1
        const val MAX_EVERY_LOOPS = 100_000
        const val DEFAULT_EVERY_LOOPS = 10

        /**
         * Jumlah titik Jalur 2 tidak dibatasi. Android sendiri yang menjadi batas
         * praktisnya: setiap titik adalah satu jendela overlay, dan bila sistem
         * menolak menambah jendela, aplikasi memberi tahu lewat pesan, bukan diam.
         */
        const val MANY_POINTS_WARNING = 20

        fun sanitizeLoops(value: Int): Int =
            value.coerceIn(MIN_EVERY_LOOPS, MAX_EVERY_LOOPS)
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
    /**
     * Satu titik per baris, tanpa garis miring agar mudah diperiksa manusia.
     * Kolom baru (warna dan seterusnya) ditambahkan di belakang sehingga baris
     * lama dari versi sebelumnya tetap terbaca: warnanya null, titik tetap ada.
     */
    fun encode(points: List<Path2Point>): String = points.joinToString(separator = "\n") { point ->
        listOf(
            point.id.toString(),
            point.normalizedX.toString(),
            point.normalizedY.toString(),
            point.delayMs.toString(),
            point.targetColor?.toString() ?: "-",
            point.tolerance.toString(),
            if (point.isHold) "1" else "0",
            point.holdMs.toString(),
            point.repeatCount.toString()
        ).joinToString(separator = "|")
    }

    fun decode(raw: String?): List<Path2Point> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line -> decodeLine(line) }
            .filter { it.normalizedX in 0f..1f && it.normalizedY in 0f..1f }
    }

    private fun decodeLine(line: String): Path2Point? {
        val parts = line.trim().split('|')
        if (parts.size < 4) return null
        val id = parts[0].toLongOrNull() ?: return null
        val x = parts[1].toFloatOrNull() ?: return null
        val y = parts[2].toFloatOrNull() ?: return null
        // Tolak lebih dulu, jangan di-clamp: sanitize akan "menyelamatkan" koordinat
        // rusak menjadi titik yang seolah valid di tepi layar.
        if (x !in 0f..1f || y !in 0f..1f) return null
        val delay = parts[3].toLongOrNull() ?: Path2Point.DEFAULT_DELAY_MS
        if (parts.size < 9) {
            // Baris versi lama: titik tersimpan tanpa warna, jadi belum siap pakai.
            return Path2Point.sanitize(
                Path2Point(id = id, normalizedX = x, normalizedY = y, delayMs = delay)
            )
        }
        val color = parts[4].toIntOrNull()
        return Path2Point.sanitize(
            Path2Point(
                id = id,
                normalizedX = x,
                normalizedY = y,
                delayMs = delay,
                targetColor = color,
                tolerance = parts[5].toIntOrNull() ?: Path2Point.DEFAULT_TOLERANCE,
                isHold = parts[6] == "1",
                holdMs = parts[7].toLongOrNull() ?: Path2Point.DEFAULT_HOLD_MS,
                repeatCount = parts[8].toIntOrNull() ?: 1
            )
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

/** Penyimpanan preferensi Jalur 2; terpisah dari skema profil smart. */
class Path2Store(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Path2Config {
        val loops = Path2Config.sanitizeLoops(
            preferences.getInt(KEY_EVERY_LOOPS, Path2Config.DEFAULT_EVERY_LOOPS)
        )
        return Path2Config(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            everyLoops = loops,
            points = Path2Format.decode(preferences.getString(KEY_POINTS, null))
        )
    }

    fun save(config: Path2Config) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_EVERY_LOOPS, Path2Config.sanitizeLoops(config.everyLoops))
            .putString(KEY_POINTS, Path2Format.encode(config.points))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "jalur_2_preferences"
        const val KEY_ENABLED = "enabled"
        const val KEY_EVERY_LOOPS = "every_loops"
        const val KEY_POINTS = "points"
    }
}
