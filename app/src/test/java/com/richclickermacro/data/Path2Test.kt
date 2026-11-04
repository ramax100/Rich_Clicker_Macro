package com.richclickermacro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Path2Test {
    private fun samplePoint(index: Int) = Path2Point(
        id = 1000L + index,
        normalizedX = 0.2f + index * 0.02f,
        normalizedY = 0.3f + index * 0.02f,
        targetColor = 0xFF3366CC.toInt(),
        tolerance = 30,
        isHold = index % 2 == 0,
        holdMs = 900L,
        repeatCount = index,
        delayMs = 1_000L + index * 100L
    )

    @Test
    fun clampsIntervalMinutes() {
        assertEquals(1, Path2Config.sanitizeMinutes(0))
        assertEquals(1, Path2Config.sanitizeMinutes(-30))
        assertEquals(10, Path2Config.sanitizeMinutes(10))
        assertEquals(720, Path2Config.sanitizeMinutes(5_000))
    }

    @Test
    fun convertsMinutesToMillis() {
        assertEquals(600_000L, Path2Config(intervalMinutes = 10).intervalMs)
        assertEquals(60_000L, Path2Config(intervalMinutes = 1).intervalMs)
    }

    @Test
    fun defaultsToTenMinutesDisabled() {
        val config = Path2Config()
        assertFalse(config.enabled)
        assertEquals(10, config.intervalMinutes)
        assertTrue(config.points.isEmpty())
    }

    @Test
    fun pointWithoutColorIsNotReady() {
        val point = Path2Point(id = 1L, normalizedX = 0.5f, normalizedY = 0.5f)
        assertFalse(point.isReady)
        assertNull(point.targetColor)
        assertTrue(point.copy(targetColor = 0xFFFFFF).isReady)
    }

    @Test
    fun sanitizeClampsEveryField() {
        val point = Path2Point.sanitize(
            Path2Point(
                id = 1L,
                normalizedX = 5f,
                normalizedY = -2f,
                tolerance = 9_999,
                holdMs = 1L,
                repeatCount = 0,
                delayMs = 5L
            )
        )
        assertEquals(1f, point.normalizedX, 0.0001f)
        assertEquals(0f, point.normalizedY, 0.0001f)
        assertEquals(Path2Point.MAX_TOLERANCE, point.tolerance)
        assertEquals(Path2Point.MIN_HOLD_MS, point.holdMs)
        assertEquals(Path2Point.MIN_REPEAT_COUNT, point.repeatCount)
        assertEquals(Path2Point.MIN_DELAY_MS, point.delayMs)
    }

    @Test
    fun keepsEveryPointWithoutCap() {
        // Jumlah titik Jalur 2 sengaja tidak dibatasi.
        val many = (1..75).map { index ->
            Path2Point(
                id = 5000L + index,
                normalizedX = (index % 10) * 0.09f + 0.05f,
                normalizedY = (index % 7) * 0.12f + 0.05f,
                targetColor = 0xFF00FF00.toInt()
            )
        }
        val roundTrip = Path2Format.decode(Path2Format.encode(many))
        assertEquals(many.size, roundTrip.size)
        assertEquals(many.map { it.id }, roundTrip.map { it.id })
    }

    @Test
    fun encodeDecodeRoundTripKeepsColorAndSettings() {
        val points = (1..3).map { samplePoint(it) }
        val decoded = Path2Format.decode(Path2Format.encode(points))
        assertEquals(points.size, decoded.size)
        points.forEachIndexed { index, point ->
            val restored = decoded[index]
            assertEquals(point.id, restored.id)
            assertEquals(point.normalizedX, restored.normalizedX, 0.0001f)
            assertEquals(point.normalizedY, restored.normalizedY, 0.0001f)
            assertEquals(point.targetColor, restored.targetColor)
            assertEquals(point.tolerance, restored.tolerance)
            assertEquals(point.isHold, restored.isHold)
            assertEquals(point.holdMs, restored.holdMs)
            assertEquals(point.repeatCount, restored.repeatCount)
            assertEquals(point.delayMs, restored.delayMs)
        }
    }

    @Test
    fun decodeRejectsGarbage() {
        assertTrue(Path2Format.decode(null).isEmpty())
        assertTrue(Path2Format.decode("").isEmpty())
        assertTrue(Path2Format.decode("bukan-titik").isEmpty())
        assertTrue(Path2Format.decode("1|0.5").isEmpty())
        assertTrue("koordinat di luar layar dibuang", Path2Format.decode("1|2.5|0.5|1000").isEmpty())
    }

    @Test
    fun decodeKeepsValidLinesAndSkipsBrokenOnes() {
        val good = Path2Format.encode(listOf(samplePoint(1)))
        val raw = "rusak\n$good\n1|abc|0.5|1000"
        val decoded = Path2Format.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(samplePoint(1).id, decoded[0].id)
    }

    @Test
    fun decodeReadsLegacyLineWithoutColor() {
        // Baris dari versi sebelumnya (tanpa kolom warna) tetap terbaca, tetapi
        // titiknya belum siap dipakai sampai warnanya diambil ulang.
        val legacy = "77|0.25|0.75|1500"
        val decoded = Path2Format.decode(legacy)
        assertEquals(1, decoded.size)
        assertEquals(77L, decoded[0].id)
        assertEquals(0.25f, decoded[0].normalizedX, 0.0001f)
        assertNull(decoded[0].targetColor)
        assertFalse(decoded[0].isReady)
    }

    @Test
    fun decodeKeepsEveryValidPoint() {
        val raw = (1..40).joinToString("\n") { index ->
            "${index}|0.1|0.2|1000"
        }
        assertEquals(40, Path2Format.decode(raw).size)
    }

    @Test
    fun scheduleCountsOnlyWhileRunning() {
        val schedule = Path2Schedule()
        schedule.resume(1_000L)
        assertEquals(0L, schedule.elapsedMs(1_000L))
        assertEquals(30_000L, schedule.elapsedMs(31_000L))
        schedule.pause(31_000L)
        assertEquals(30_000L, schedule.elapsedMs(90_000L))
        schedule.resume(100_000L)
        assertEquals(60_000L, schedule.elapsedMs(130_000L))
    }

    @Test
    fun scheduleIgnoresDuplicateResume() {
        val schedule = Path2Schedule()
        schedule.resume(1_000L)
        schedule.resume(5_000L)
        assertEquals(4_000L, schedule.elapsedMs(5_000L))
    }

    @Test
    fun scheduleIgnoresDuplicatePause() {
        val schedule = Path2Schedule()
        schedule.resume(1_000L)
        schedule.pause(3_000L)
        schedule.pause(9_000L)
        assertEquals(2_000L, schedule.elapsedMs(9_000L))
    }

    @Test
    fun scheduleNeverGoesBackwards() {
        val schedule = Path2Schedule()
        schedule.resume(10_000L)
        assertEquals(0L, schedule.elapsedMs(5_000L))
    }

    @Test
    fun scheduleFiresAtInterval() {
        val schedule = Path2Schedule()
        val interval = 10 * 60_000L
        schedule.resume(0L)
        assertFalse(schedule.isDue(9 * 60_000L, interval))
        assertEquals(60_000L, schedule.remainingMs(9 * 60_000L, interval))
        assertTrue(schedule.isDue(10 * 60_000L, interval))
        assertEquals(0L, schedule.remainingMs(12 * 60_000L, interval))
    }

    @Test
    fun scheduleNeverFiresWithoutInterval() {
        val schedule = Path2Schedule()
        schedule.resume(0L)
        assertFalse(schedule.isDue(1_000_000L, 0L))
    }

    @Test
    fun resetRestartsCounting() {
        val schedule = Path2Schedule()
        schedule.resume(0L)
        assertEquals(60_000L, schedule.elapsedMs(60_000L))
        schedule.reset()
        assertEquals(0L, schedule.elapsedMs(60_000L))
        assertFalse(schedule.isRunning)
        schedule.resume(60_000L)
        assertEquals(0L, schedule.elapsedMs(60_000L))
    }

    @Test
    fun pausesAfterRunDoNotCountTowardsNextTrigger() {
        val schedule = Path2Schedule()
        val interval = 10 * 60_000L
        schedule.resume(0L)
        assertTrue(schedule.isDue(interval, interval))
        schedule.pause(interval)
        schedule.reset()
        schedule.resume(interval + 5 * 60_000L)
        assertFalse(schedule.isDue(interval + 5 * 60_000L, interval))
        assertTrue(schedule.isDue(interval + 5 * 60_000L + interval, interval))
    }

    @Test
    fun formatsRemainingTime() {
        assertEquals("10m 0d", Path2Format.formatRemaining(600_000L))
        assertEquals("9m 58d", Path2Format.formatRemaining(598_000L))
        assertEquals("59d", Path2Format.formatRemaining(59_000L))
        assertEquals("1d", Path2Format.formatRemaining(500L))
        assertEquals("0d", Path2Format.formatRemaining(0L))
        assertEquals("0d", Path2Format.formatRemaining(-5_000L))
    }
}
