package com.richclickermacro.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressurePolicyTest {
    @Test
    fun onlyCriticalRunningLevelStopsMacro() {
        assertTrue(MemoryPressurePolicy.shouldStopMacro(MemoryPressurePolicy.TRIM_RUNNING_CRITICAL))
        assertFalse(MemoryPressurePolicy.shouldStopMacro(MemoryPressurePolicy.TRIM_RUNNING_MODERATE))
        assertFalse(MemoryPressurePolicy.shouldStopMacro(MemoryPressurePolicy.TRIM_RUNNING_LOW))
    }

    @Test
    fun backgroundAndLruTransitionsDoNotStopMacro() {
        listOf(
            MemoryPressurePolicy.TRIM_UI_HIDDEN,
            MemoryPressurePolicy.TRIM_BACKGROUND,
            MemoryPressurePolicy.TRIM_MODERATE,
            MemoryPressurePolicy.TRIM_COMPLETE
        ).forEach { assertFalse("level=$it", MemoryPressurePolicy.shouldStopMacro(it)) }
    }

    @Test
    fun unknownLevelsDoNotStopMacro() {
        listOf(Int.MIN_VALUE, -1, 0, 16, 200, Int.MAX_VALUE).forEach {
            assertFalse(MemoryPressurePolicy.shouldStopMacro(it))
        }
    }

    @Test
    fun describesKnownAndUnknownLevels() {
        assertEquals("running_critical", MemoryPressurePolicy.describe(15))
        assertEquals("ui_hidden", MemoryPressurePolicy.describe(20))
        assertEquals("background", MemoryPressurePolicy.describe(40))
        assertEquals("complete", MemoryPressurePolicy.describe(80))
        assertEquals("level_99", MemoryPressurePolicy.describe(99))
    }
}
