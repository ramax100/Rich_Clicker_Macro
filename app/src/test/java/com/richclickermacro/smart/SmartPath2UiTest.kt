package com.richclickermacro.smart

import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Jalur 2 hanya berguna kalau panelnya bisa dibuka, tombolnya bereaksi, dan titik
 * benar-benar terbentuk. Test ini menekan tombol yang sama seperti pengguna.
 *
 * Pelajaran dari laporan "tidak ada reaksi": titik Jalur 2 dahulu diletakkan di
 * koordinat tetap sehingga sering berada di bawah toolbar sendiri — marker tidak
 * terlihat dan warna tak terbaca. Sekarang titik diletakkan dengan mengetuk layar,
 * sama seperti titik smart biasa, dan test ini menjaga alur itu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h640dp-mdpi")
class SmartPath2UiTest {
    private lateinit var service: AutoClickAccessibilityService
    private lateinit var controller: SmartOverlayController

    @Before
    fun setUp() {
        service = Robolectric.buildService(AutoClickAccessibilityService::class.java).create().get()
        AutoClickAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
        controller = field(service, "smartController") as SmartOverlayController
        val profile = SmartProfile(
            name = "Jalur2",
            points = listOf(
                SmartPoint(
                    id = 1L,
                    label = "T1",
                    watchX = 0.5f,
                    watchY = 0.4f,
                    targetColor = 0xFF00FF00.toInt(),
                    colorCaptured = true
                )
            ),
            countdownSeconds = 0,
            stopOnAppChange = false
        )
        service.showSmartProfile(profile)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun button(name: String): Button = field(controller, name) as Button

    @Suppress("UNCHECKED_CAST")
    private fun path2Markers(): MutableList<Any> =
        field(controller, "path2Markers") as MutableList<Any>

    private fun path2Panel(): LinearLayout = field(controller, "path2Panel") as LinearLayout

    /** Meniru pengguna mengetuk layar saat lapisan penanda aktif. */
    private fun pick(nx: Float, ny: Float) {
        val method = controller.javaClass.getDeclaredMethod(
            "finishPick", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(controller, nx, ny)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun openingThePanelShowsItAndKeepsTheButtonsEnabled() {
        assertEquals(View.GONE, path2Panel().visibility)
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, path2Panel().visibility)
        assertTrue("tombol tambah titik harus bisa ditekan", button("addPath2Button").isEnabled)
    }

    @Test
    fun addPointOpensTheTapToPlaceLayerInsteadOfDoingNothing() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, field(controller, "pickerView"))
        button("addPath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(
            "menekan + Titik J2 harus membuka lapisan ketuk-layar",
            field(controller, "pickerView")
        )
        assertEquals(true, field(controller, "pickForPath2"))
    }

    @Test
    fun tappingTheScreenCreatesAReadyToCapturePath2Point() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button("addPath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pick(0.62f, 0.78f)
        assertEquals("satu ketukan menghasilkan satu titik Jalur 2", 1, path2Markers().size)
        assertEquals(false, field(controller, "pickForPath2"))
        assertEquals("lapisan penanda harus sudah ditutup", null, field(controller, "pickerView"))
        assertTrue("tombol Ambil Warna harus aktif setelah ada titik", button("path2ColorButton").isEnabled)
    }

    @Test
    fun severalTapsCreateSeveralPoints() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        listOf(0.6f to 0.7f, 0.3f to 0.8f, 0.8f to 0.5f).forEach { (x, y) ->
            button("addPath2Button").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            pick(x, y)
        }
        assertEquals(3, path2Markers().size)
    }

    @Test
    fun removingAPointRemovesItsMarker() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        repeat(2) {
            button("addPath2Button").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            pick(0.6f, 0.8f)
        }
        assertEquals(2, path2Markers().size)
        button("removePath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, path2Markers().size)
    }

    @Test
    fun pickedPointIsSavedWithItsPosition() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button("addPath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pick(0.62f, 0.78f)
        val store = com.richclickermacro.data.Path2Store(service)
        val saved = store.load().points
        assertEquals(1, saved.size)
        assertTrue("x=${saved[0].normalizedX}", kotlin.math.abs(saved[0].normalizedX - 0.62f) < 0.05f)
        assertTrue("y=${saved[0].normalizedY}", kotlin.math.abs(saved[0].normalizedY - 0.78f) < 0.05f)
    }

    @Test
    fun panelStepsAsideWhileTappingThenComesBack() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, path2Panel().visibility)
        button("addPath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "panel harus menyingkir supaya ketukan tidak jatuh di bawah toolbar",
            View.GONE,
            path2Panel().visibility
        )
        pick(0.55f, 0.72f)
        assertEquals("panel harus kembali setelah titik jadi", View.VISIBLE, path2Panel().visibility)
    }

    @Test
    fun statusLabelExplainsWhyItIsNotRunning() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val label = field(controller, "path2StatusLabel") as android.widget.TextView
        // Di perangkat nyata (izin baca layar aktif) pesannya menuntun ke tombol
        // + Titik J2; di lingkungan test tanpa izin, pesannya menyebut sebabnya.
        // Yang penting: status tidak pernah cuma "mati" tanpa penjelasan.
        val text = label.text.toString()
        assertTrue("status=${text}", text.contains("Titik J2") || text.contains("izin baca"))
        assertTrue("status=${text}", text.length > 12)
        assertFalse("status=${label.text}", label.text.equals("Jalur 2 mati"))
    }

    @Test
    fun markersComeBackAfterColourCaptureEnds() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button("addPath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pick(0.62f, 0.78f)
        val holder = path2Markers().first()
        val view = holder.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(holder) as View
        val hide = controller.javaClass.getDeclaredMethod("setPath2Visible", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        hide.invoke(controller, false)
        assertEquals(View.INVISIBLE, view.visibility)
        controller.javaClass.getDeclaredMethod("endCapture").apply { isAccessible = true }
            .invoke(controller)
        assertEquals(
            "titik Jalur 2 harus muncul lagi setelah pembacaan warna selesai",
            View.VISIBLE,
            view.visibility
        )
    }

    @Test
    fun statusLabelMentionsJalur2() {
        button("path2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button("addPath2Button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pick(0.62f, 0.78f)
        val label = field(controller, "path2StatusLabel") as android.widget.TextView
        assertNotNull(label.text)
        assertTrue("status=${label.text}", label.text.contains("Jalur 2") || label.text.contains("J2"))
    }
}
