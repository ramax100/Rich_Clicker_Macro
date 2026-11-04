package com.richclickermacro.smart

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.richclickermacro.MainActivity
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 12 specific checks for the new feature. These verify wiring, capability
 * declaration and mode separation; they are not a substitute for a device test of
 * actual screen sampling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartClickAndroid12Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun accessibilityConfigDeclaresScreenshotCapability() {
        var found = false
        var stillReading = true
        val attrs = context.resources.getXml(com.richclickermacro.R.xml.auto_click_accessibility_service)
        while (stillReading) {
            val event = attrs.next()
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                for (index in 0 until attrs.attributeCount) {
                    if (attrs.getAttributeName(index) == "canTakeScreenshot") {
                        found = attrs.getAttributeValue(index) == "true"
                    }
                }
            }
            stillReading = event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
        }
        attrs.close()
        assertTrue("canTakeScreenshot must be declared for Multi Smart Click", found)
    }

    @Test
    fun windowContentRetrievalRemainsDisabled() {
        val attrs = context.resources.getXml(com.richclickermacro.R.xml.auto_click_accessibility_service)
        var value: String? = null
        while (true) {
            val event = attrs.next()
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                for (index in 0 until attrs.attributeCount) {
                    if (attrs.getAttributeName(index) == "canRetrieveWindowContent") {
                        value = attrs.getAttributeValue(index)
                    }
                }
            }
            if (event == org.xmlpull.v1.XmlPullParser.END_DOCUMENT) break
        }
        attrs.close()
        assertEquals("false", value)
    }

    @Test
    fun samplerReportsMissingCapabilityInsteadOfCrashing() {
        val controller = Robolectric.buildService(AutoClickAccessibilityService::class.java).create()
        val service = controller.get()
        val sampler = ScreenSampler(
            service,
            android.os.Handler(android.os.Looper.getMainLooper()),
            java.util.concurrent.Executor { it.run() }
        )
        // Robolectric does not populate serviceInfo, so the guard must trigger.
        val problem = sampler.capabilityProblem()
        assertNotNull(problem)
        var delivered: String? = null
        sampler.sample(mapOf(1L to (0.5f to 0.5f)), emptyList()) { result, error ->
            assertEquals(null, result)
            delivered = error
        }
        assertNotNull(delivered)
        controller.destroy()
    }

    @Test
    fun samplerReturnsEmptyMapForNoPatchesWithoutRequestingScreenshot() {
        val controller = Robolectric.buildService(AutoClickAccessibilityService::class.java).create()
        val sampler = ScreenSampler(
            controller.get(),
            android.os.Handler(android.os.Looper.getMainLooper()),
            java.util.concurrent.Executor { it.run() }
        )
        // Capability guard runs first, so an empty request still reports the problem
        // rather than silently succeeding.
        var called = false
        sampler.sample(emptyMap(), emptyList()) { _, _ -> called = true }
        assertTrue(called)
        controller.destroy()
    }

    @Test
    fun mainActivityShowsBothModesSeparately() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        val texts = collectTexts(root)
        assertTrue(texts.any { it.contains("MULTI SMART CLICK") })
        assertTrue(texts.any { it.contains("MACRO BIASA") })
        assertTrue(texts.any { it.contains("Buka Editor Smart Click") })
        // Classic controls must still exist untouched.
        assertTrue(texts.any { it.contains("Tampilkan Editor di Atas Aplikasi") })
        assertTrue(texts.any { it.contains("Simpan Pengaturan") })
    }

    @Test
    fun smartEditorButtonIsDisabledWhileServiceIsOff() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val button = findButton(activity.window.decorView, "Buka Editor Smart Click")
        assertNotNull(button)
        assertFalse(button!!.isEnabled)
    }

    @Test
    fun smartProfilesSurviveActivityRecreation() {
        val repository = SmartRepository(context)
        repository.saveProfile(
            repository.getActiveProfile().copy(
                points = listOf(
                    SmartPoint(1L, "T1", 0.4f, 0.5f, targetColor = 0xFFABCDEF.toInt(), colorCaptured = true)
                )
            )
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().resume()
        controller.recreate()
        val reloaded = SmartRepository(context).getProfiles().first()
        assertEquals(1, reloaded.points.size)
        assertTrue(reloaded.points.first().colorCaptured)
    }

    @Test
    fun serviceExposesSmartStateWithoutBreakingClassicState() {
        val controller = Robolectric.buildService(AutoClickAccessibilityService::class.java).create()
        val service = controller.get()
        assertFalse(service.isSmartActive())
        assertFalse(service.isMacroActive())
        assertFalse(service.hasLoadedSmartProfile())
        assertFalse(service.hasLoadedProfile())
        // Must not throw when neither editor has been shown.
        assertFalse(service.toggleSmartFromTile())
        assertFalse(service.toggleFromTile())
        assertEquals(0, service.smartWindowCount())
        controller.destroy()
    }

    @Test
    fun packageAndVersionMatchReleaseContract() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("com.richclickermacro", info.packageName)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        assertEquals(31, versionCode)
        assertEquals("0.9.13", info.versionName)
    }

    private fun collectTexts(view: android.view.View): List<String> {
        val result = mutableListOf<String>()
        when (view) {
            is android.widget.TextView -> result.add(view.text.toString())
            is android.view.ViewGroup -> {
                for (index in 0 until view.childCount) result.addAll(collectTexts(view.getChildAt(index)))
            }
        }
        return result
    }

    private fun findButton(view: android.view.View, label: String): android.widget.Button? {
        if (view is android.widget.Button && view.text.toString() == label) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }
}
