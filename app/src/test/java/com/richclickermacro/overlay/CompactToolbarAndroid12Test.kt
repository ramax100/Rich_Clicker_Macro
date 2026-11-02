package com.richclickermacro.overlay

import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import com.richclickermacro.data.MacroPoint
import com.richclickermacro.data.MacroProfile
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
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h640dp-mdpi")
class CompactToolbarAndroid12Test {
    private lateinit var service: AutoClickAccessibilityService
    private lateinit var controller: ClickOverlayController

    @Before
    fun setUp() {
        service = Robolectric.buildService(AutoClickAccessibilityService::class.java).create().get()
        AutoClickAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
        controller = field(service, "overlayController") as ClickOverlayController
        service.showProfile(MacroProfile("Default", listOf(MacroPoint(1, 0.5f, 0.2f, 1000)), countdownSeconds = 0, stopOnAppChange = false))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() { service.onDestroy() }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun toolbar(): LinearLayout = field(controller, "controlsView") as LinearLayout
    private fun advanced(): LinearLayout = field(controller, "advancedContent") as LinearLayout
    private fun button(name: String): Button = field(controller, name) as Button

    @Test
    fun defaultToolbarIsNarrowAndAdvancedSettingsAreHidden() {
        val density = service.resources.displayMetrics.density
        val params = field(controller, "controlsParams") as WindowManager.LayoutParams
        assertTrue("width=${params.width}", params.width <= (220 * density).toInt())
        assertEquals(View.GONE, advanced().visibility)
        assertEquals(View.VISIBLE, button("playButton").visibility)
        assertEquals(View.VISIBLE, button("stopButton").visibility)
    }

    @Test
    fun advancedToggleRevealsAndHidesAllPointSettings() {
        button("advancedButton").performClick()
        assertEquals(View.VISIBLE, advanced().visibility)
        assertEquals(View.VISIBLE, button("actionButton").visibility)
        assertEquals(View.VISIBLE, button("durationButton").visibility)
        assertEquals(View.VISIBLE, button("repeatButton").visibility)
        button("advancedButton").performClick()
        assertEquals(View.GONE, advanced().visibility)
    }

    @Test
    fun collapsedToolbarKeepsStopInsideMenuWhileRunning() {
        assertTrue(service.toggleFromTile())
        assertTrue(service.isMacroActive())
        val handle = field(controller, "toolbarHandle") as View
        handle.performClick()
        assertEquals(View.GONE, (field(controller, "editorContent") as View).visibility)
        assertEquals(View.VISIBLE, button("compactStopButton").visibility)
        button("compactStopButton").performClick()
        assertFalse(service.isMacroActive())
    }

    @Test
    fun startClosesAdvancedPanelBeforeRunning() {
        button("advancedButton").performClick()
        assertEquals(View.VISIBLE, advanced().visibility)
        assertTrue(service.toggleFromTile())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(View.GONE, advanced().visibility)
        button("compactStopButton").performClick()
        assertFalse(service.isMacroActive())
    }

    @Test
    fun closeEditorRemovesToolbarAndMarkers() {
        val close = findButton(toolbar(), "Tutup editor macro")!!
        close.performClick()
        assertFalse(service.hasLoadedProfile())
        assertEquals(0, service.overlayWindowCount())
    }

    private fun findButton(view: View, description: String): Button? {
        if (view is Button && view.contentDescription?.toString() == description) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findButton(view.getChildAt(i), description)
                if (found != null) return found
            }
        }
        return null
    }
}
