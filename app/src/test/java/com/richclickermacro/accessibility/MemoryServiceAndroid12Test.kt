package com.richclickermacro.accessibility

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.richclickermacro.data.MacroPoint
import com.richclickermacro.data.MacroProfile
import com.richclickermacro.diagnostics.MacroDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MemoryServiceAndroid12Test {
    private lateinit var controller: ServiceController<AutoClickAccessibilityService>
    private lateinit var service: AutoClickAccessibilityService

    @Before
    fun setUp() {
        assertTrue(MacroDiagnostics.flush())
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "macro-diagnostics.log").delete()
        File(context.filesDir, "macro-diagnostics.previous.log").delete()
        controller = Robolectric.buildService(AutoClickAccessibilityService::class.java).create()
        service = controller.get()
        // Robolectric creates the Service but does not bind it through system_server.
        AutoClickAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
    }

    @After
    fun tearDown() {
        service.onDestroy()
        assertTrue(MacroDiagnostics.flush())
    }

    private fun export(): String {
        assertTrue(MacroDiagnostics.flush())
        shadowOf(Looper.getMainLooper()).idle()
        val output = ByteArrayOutputStream()
        MacroDiagnostics.export(service, output)
        return output.toString(Charsets.UTF_8.name())
    }

    @Test
    fun samplerRunsOncePerMinuteAndDuplicateStartDoesNotDuplicateIt() {
        service.startMemorySampling()
        service.startMemorySampling()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertEquals(1, Regex("memory_sample").findAll(export()).count())
    }

    @Test
    fun stopAndDestroyRemovePendingSamples() {
        service.startMemorySampling()
        service.stopMemorySampling()
        service.stopMemorySampling()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
        val stopped = export()
        assertEquals(0, Regex("memory_sample").findAll(stopped).count())
        assertEquals(1, Regex("macro_memory_stop").findAll(stopped).count())
        service.startMemorySampling()
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
        assertEquals(0, Regex("memory_sample").findAll(export()).count())
        assertFalse(service.isMacroActive())
    }

    @Test
    fun criticalPressureStopsMacroButBackgroundTransitionDoesNot() {
        service.showProfile(
            MacroProfile(
                name = "Default",
                points = listOf(MacroPoint(id = 1L, normalizedX = 0.5f, normalizedY = 0.3f, delayMs = 1000L)),
                countdownSeconds = 0,
                stopOnAppChange = false
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.hasLoadedProfile())
        assertTrue(service.toggleFromTile())
        assertTrue(service.isMacroActive())
        service.onTrimMemory(40)
        assertTrue(service.isMacroActive())
        service.onTrimMemory(15)
        assertFalse(service.isMacroActive())
        assertTrue(export().contains("memory_pressure_stop"))
    }

    @Test
    fun reusingPathDoesNotChangeAnAlreadyCreatedStroke() {
        val path = android.graphics.Path().apply {
            moveTo(10f, 10f)
            lineTo(10f, 10f)
        }
        val first = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 40L, true)
        path.reset()
        path.moveTo(20f, 20f)
        path.lineTo(20f, 20f)
        // If StrokeDescription retained the original Path, its endpoint would now
        // be (20,20), and this continuation from (10,10) would be rejected.
        val second = first.continueStroke(path, 0L, 40L, false)
        assertEquals(40L, first.duration)
        assertEquals(40L, second.duration)
        assertFalse(second.willContinue())
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyLowMemoryCallbackStopsSafelyWithoutAnEditor() {
        service.onLowMemory()
        service.onTrimMemory(80)
        assertFalse(service.isMacroActive())
        val text = export()
        assertTrue(text.contains("low_memory"))
        assertFalse(text.contains("memory_pressure_stop"))
    }
}
