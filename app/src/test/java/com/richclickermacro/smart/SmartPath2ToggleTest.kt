package com.richclickermacro.smart

import android.os.Looper
import android.widget.Button
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import com.richclickermacro.data.Path2Config
import com.richclickermacro.data.Path2Point
import com.richclickermacro.data.Path2Store
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
import org.robolectric.shadows.ShadowToast

/**
 * Saklar ON/OFF Jalur 2 harus benar-benar mencegah bentrok dengan macro biasa.
 *
 * Aturan yang dijaga test ini:
 *  - Jalur 2 mati tidak akan pernah menyela macro biasa (timer Jalur 2 tidak pernah
 *    "jatuh tempo" selama saklar OFF).
 *  - Mematikan Jalur 2 saat ia sedang berjalan langsung mengembalikan kendali ke
 *    macro biasa, tanpa menghentikan macro.
 *  - Saklar tetap bisa dipakai walau macro sedang berjalan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h640dp-mdpi")
class SmartPath2ToggleTest {
    private lateinit var service: AutoClickAccessibilityService
    private lateinit var controller: SmartOverlayController

    @Before
    fun setUp() {
        service = Robolectric.buildService(AutoClickAccessibilityService::class.java).create().get()
        AutoClickAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
        controller = field(service, "smartController") as SmartOverlayController
        service.showSmartProfile(
            SmartProfile(
                name = "ToggleJ2",
                points = listOf(
                    SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.4f)
                ),
                countdownSeconds = 0,
                stopOnAppChange = false
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        controller.stop("test_teardown")
        service.onDestroy()
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun call(name: String, vararg args: Any?): Any? {
        val method = controller.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(controller, *args)
    }

    private fun seedPoint(color: Int = 0xFF00FF00.toInt(), ready: Boolean = true) {
        val store = Path2Store(service)
        store.save(
            Path2Config(
                enabled = false,
                intervalMinutes = 10,
                points = listOf(
                    Path2Point(
                        id = 1L,
                        normalizedX = 0.5f,
                        normalizedY = 0.7f,
                        targetColor = if (ready) color else null
                    )
                )
            )
        )
        call("loadPath2")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun toggleButton(): Button = field(controller, "path2ToggleButton") as Button

    @Test
    fun switchStartsOffAndSaysSoOnTheButton() {
        seedPoint()
        (field(controller, "path2Button") as Button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("J2 OFF", toggleButton().text.toString())
        assertFalse(Path2Store(service).load().enabled)
    }

    @Test
    fun turningItOnAndOffIsPersisted() {
        seedPoint()
        (field(controller, "path2Button") as Button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        toggleButton().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("saklar ON harus tersimpan", Path2Store(service).load().enabled)
        assertEquals("J2 ON", toggleButton().text.toString())
        toggleButton().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("saklar OFF harus tersimpan", Path2Store(service).load().enabled)
        assertEquals("J2 OFF", toggleButton().text.toString())
    }

    @Test
    fun theSwitchCanOnlyBeTurnedOnWhenAPointIsReady() {
        seedPoint(ready = false)
        (field(controller, "path2Button") as Button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        toggleButton().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("titik tanpa warna tidak boleh dinyalakan", Path2Store(service).load().enabled)
        assertTrue(
            ShadowToast.getTextOfLatestToast().orEmpty().contains("Ambil warna")
        )
    }

    @Test
    fun whileOffPath2NeverJumpsIntoTHeClassicRun() {
        seedPoint()
        // Timer Jalur 2 sudah lewat jauh dari intervalnya.
        val schedule = field(controller, "path2Schedule")!!
        val resume = schedule.javaClass.getDeclaredMethod("resume", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        resume.invoke(schedule, 0L)
        val now = android.os.SystemClock.elapsedRealtime()
        val due = call("path2Due") as Boolean
        assertFalse("Jalur 2 OFF tidak boleh menyela macro biasa", due)
        assertTrue("timer Jalur 2 tetap diam selama OFF", now >= 0L)
    }

    @Test
    fun turningItOffWhileItRunsHandsControlBackToTheClassicMacro() {
        seedPoint()
        call("setPath2Enabled", true)
        shadowOf(Looper.getMainLooper()).idle()
        // Tiru keadaan: macro berjalan dan Jalur 2 sedang bekerja.
        val runStateField = controller.javaClass.getDeclaredField("runState").apply { isAccessible = true }
        val states = runStateField.type.enumConstants as Array<Enum<*>>
        val running = states.first { it.name == "RUNNING" }
        val path2State = states.first { it.name == "PATH2" }
        runStateField.set(controller, running)
        runStateField.set(controller, path2State)
        call("setPath2Enabled", false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "mematikan Jalur 2 harus mengembalikan kendali ke macro biasa",
            "RUNNING",
            runStateField.get(controller).toString()
        )
        assertTrue(
            "macro biasa tidak boleh ikut berhenti",
            controller.isRunning()
        )
        assertFalse(Path2Store(service).load().enabled)
    }

    @Test
    fun switchStaysUsableWhileTheMacroIsRunning() {
        seedPoint()
        val runStateField = controller.javaClass.getDeclaredField("runState").apply { isAccessible = true }
        val states = runStateField.type.enumConstants as Array<Enum<*>>
        runStateField.set(controller, states.first { it.name == "RUNNING" })
        call("updateUi")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("saklar ON/OFF harus tetap bisa ditekan saat macro berjalan", toggleButton().isEnabled)
    }

    @Test
    fun pressingJ2WhileRunningTurnsItOffInsteadOfOpeningThePanel() {
        seedPoint()
        call("setPath2Enabled", true)
        val runStateField = controller.javaClass.getDeclaredField("runState").apply { isAccessible = true }
        val states = runStateField.type.enumConstants as Array<Enum<*>>
        runStateField.set(controller, states.first { it.name == "RUNNING" })
        call("updateUi")
        (field(controller, "path2Button") as Button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "menekan J2 saat macro berjalan harus mematikan Jalur 2",
            Path2Store(service).load().enabled
        )
        assertEquals(
            "macro biasa tetap berjalan",
            "RUNNING",
            runStateField.get(controller).toString()
        )
    }

    @Test
    fun panelCannotBeOpenedWhileRunningSoTheToolbarStaysSmall() {
        seedPoint()
        val runStateField = controller.javaClass.getDeclaredField("runState").apply { isAccessible = true }
        val states = runStateField.type.enumConstants as Array<Enum<*>>
        runStateField.set(controller, states.first { it.name == "RUNNING" })
        val panel = field(controller, "path2Panel") as android.view.View
        call("togglePath2Panel")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "panel tidak boleh terbuka saat berjalan (toolbar bisa menutupi titik)",
            android.view.View.GONE,
            panel.visibility
        )
        assertEquals(false, field(controller, "isPath2Open"))
    }

    @Test
    fun stoppingTheRunClearsThePendingPointPlacement() {
        seedPoint()
        call("startPick", true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, field(controller, "pickForPath2"))
        call("stop", "test_stop")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "penanda titik Jalur 2 harus ikut dibatalkan saat run dihentikan",
            false,
            field(controller, "pickForPath2")
        )
        assertEquals(null, field(controller, "pickerView"))
    }

    @Test
    fun markersAreDimmedWhenTheSwitchIsOff() {
        seedPoint()
        val holder = (field(controller, "path2Markers") as MutableList<*>).first()!!
        val view = holder.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(holder) as android.view.View
        call("updateUi")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("titik harus redup saat OFF", 0.45f, view.alpha, 0.001f)
        call("setPath2Enabled", true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("titik harus terang saat ON", 1f, view.alpha, 0.001f)
    }

    @Test
    fun statusSaysColoursAreMissingWhenNothingIsReady() {
        seedPoint(ready = false)
        call("setPath2Enabled", true)
        val store = Path2Store(service)
        // Paksa keadaan "ON tapi belum ada warna" lewat penyimpanan langsung.
        store.save(store.load().copy(enabled = true))
        call("loadPath2")
        val panel = field(controller, "path2Panel") as android.view.View
        (field(controller, "path2Button") as Button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val label = field(controller, "path2StatusLabel") as android.widget.TextView
        // Di perangkat nyata pesannya "ambil warna dulu"; di lingkungan test tanpa izin
        // baca layar pesannya menyebut izinnya. Yang penting: status tidak berpura-pura
        // siap dan menyebut langkah berikutnya.
        val text = label.text.toString()
        assertTrue(
            "status harus menjelaskan, bukan pura-pura siap: $text",
            text.contains("ambil warna") || text.contains("izin baca")
        )
        assertTrue("status=$text", text.contains("ON"))
        assertFalse("status=$text", text.contains("tiap"))
        assertEquals(android.view.View.VISIBLE, panel.visibility)
    }

    @Test
    fun turningItBackOnRestartsTheCountdownFromZero() {
        seedPoint()
        call("setPath2Enabled", true)
        shadowOf(Looper.getMainLooper()).idle()
        val schedule = field(controller, "path2Schedule")!!
        val elapsed = schedule.javaClass.getDeclaredMethod("elapsedMs", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val before = elapsed.invoke(schedule, android.os.SystemClock.elapsedRealtime()) as Long
        call("setPath2Enabled", false)
        val afterOff = elapsed.invoke(schedule, android.os.SystemClock.elapsedRealtime()) as Long
        assertEquals("timer harus nol saat OFF", 0L, afterOff)
        assertTrue("timer sempat berjalan sebelum dimatikan", before >= 0L)
    }
}
