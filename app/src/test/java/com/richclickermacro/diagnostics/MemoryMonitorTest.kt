package com.richclickermacro.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MemoryMonitorTest {
    @Test
    fun formatsPreciseValuesWithoutClaimingSafeThresholds() {
        val snapshot = MemoryMonitor.Snapshot(
            pssKb = 100 * 1024,
            javaUsedKb = 20 * 1024,
            javaMaxKb = 256 * 1024,
            nativeHeapKb = 8 * 1024,
            overlayWindows = 3
        )
        val text = MemoryMonitor.format(snapshot)
        assertTrue(text.contains("pss=100.0MiB"))
        assertTrue(text.contains("java=20.0/256.0MiB"))
        assertTrue(text.contains("overlayWindows=3"))
        assertTrue(text.contains("pssKb=102400"))
        assertFalse(text.contains("ringan"))
        assertFalse(text.contains("aman"))
    }

    @Test
    fun unavailablePssIsNotReportedAsZeroUsage() {
        val snapshot = MemoryMonitor.Snapshot(0, 1024, 262144, 0, 0)
        assertTrue(MemoryMonitor.format(snapshot).contains("pss=unavailable"))
        assertTrue(MemoryMonitor.display(snapshot).contains("PSS clicker: tidak terbaca"))
        assertTrue(MemoryMonitor.display(snapshot).contains("bukan RAM game"))
    }

    @Test
    fun captureOnAndroid12HasConsistentNonNegativeValues() {
        val snapshot = MemoryMonitor.capture(2)
        assertTrue(snapshot.pssKb >= 0)
        assertTrue(snapshot.javaUsedKb >= 0)
        assertTrue(snapshot.javaMaxKb > 0)
        assertTrue(snapshot.nativeHeapKb >= 0)
        assertEquals(2, snapshot.overlayWindows)
    }

    @Test
    fun displaySeparatesHeapLimitFromActualUsage() {
        val text = MemoryMonitor.display(MemoryMonitor.Snapshot(81920, 20480, 262144, 4096, 4))
        assertTrue(text.contains("PSS clicker: 80.0 MiB"))
        assertTrue(text.contains("Java terpakai: 20.0 MiB"))
        assertTrue(text.contains("Batas heap Java: 256.0 MiB"))
        assertTrue(text.contains("Window overlay dikelola: 4"))
        assertTrue(text.contains("Batas heap bukan jumlah RAM yang sedang dipakai"))
    }
}
