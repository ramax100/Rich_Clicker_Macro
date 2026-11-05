package com.richclickermacro

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home page must show one mode at a time instead of nine stacked cards.
 * These checks guard the tab behaviour and the state restore around rotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HomeLayoutTest {

    private fun texts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView) out.add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) out.addAll(texts(view.getChildAt(i)))
        return out
    }

    private fun findText(view: View, label: String): TextView? {
        if (view is TextView && view.text.toString() == label) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findText(view.getChildAt(i), label)?.let { return it }
        }
        return null
    }

    /** Walks up to check whether any ancestor hides this view. */
    private fun isShown(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    @Test
    fun bothModeTabsExist() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        assertNotNull(findText(root, "MACRO BIASA"))
        assertNotNull(findText(root, "MULTI SMART CLICK"))
    }

    @Test
    fun classicModeIsShownFirstAndSmartCardsAreHidden() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        val classicButton = findText(root, "Tampilkan Editor di Atas Aplikasi")
        val smartButton = findText(root, "Buka Editor Smart Click")
        assertNotNull(classicButton)
        assertNotNull(smartButton)
        assertTrue("classic controls visible by default", isShown(classicButton!!))
        assertTrue("smart controls hidden until its tab is chosen", !isShown(smartButton!!))
    }

    @Test
    fun tappingSmartTabSwapsWhichCardsAreVisible() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        findText(root, "MULTI SMART CLICK")!!.performClick()
        val classicButton = findText(root, "Tampilkan Editor di Atas Aplikasi")!!
        val smartButton = findText(root, "Buka Editor Smart Click")!!
        assertTrue("smart controls shown after tab tap", isShown(smartButton))
        assertTrue("classic controls hidden after tab tap", !isShown(classicButton))
    }

    @Test
    fun tappingClassicTabRestoresClassicCards() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        findText(root, "MULTI SMART CLICK")!!.performClick()
        findText(root, "MACRO BIASA")!!.performClick()
        assertTrue(isShown(findText(root, "Tampilkan Editor di Atas Aplikasi")!!))
        assertTrue(!isShown(findText(root, "Buka Editor Smart Click")!!))
    }

    @Test
    fun toolsSectionStartsCollapsedAndCanExpand() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        val memoryButton = findText(root, "Cek RAM Clicker")!!
        assertTrue("tools collapsed by default to keep the page short", !isShown(memoryButton))
        findText(root, "▸  Alat & Panduan")!!.performClick()
        assertTrue("tools expand on tap", isShown(memoryButton))
    }

    @Test
    fun selectedTabSurvivesRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().resume()
        findText(controller.get().window.decorView, "MULTI SMART CLICK")!!.performClick()
        controller.recreate()
        val root = controller.get().window.decorView
        assertTrue(
            "smart tab must stay selected after rotation",
            isShown(findText(root, "Buka Editor Smart Click")!!)
        )
    }

    @Test
    fun oldNumberedHeadingsAreGone() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val all = texts(activity.window.decorView)
        // Numbering no longer matches the layout once cards are split across tabs.
        assertTrue(all.none { it == "7  MULTI SMART CLICK" })
        assertTrue(all.none { it == "2  PROFIL" })
    }

    @Test
    fun serviceStatusIsAlwaysVisibleRegardlessOfTab() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val root = activity.window.decorView
        val settings = findText(root, "Buka Pengaturan Aksesibilitas")!!
        assertTrue(isShown(settings))
        findText(root, "MULTI SMART CLICK")!!.performClick()
        assertTrue("service control must never be hidden", isShown(settings))
    }

    @Test
    fun versionLabelIsShown() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()
        val all = texts(activity.window.decorView)
        assertTrue(all.any { it.contains("0.9.15") })
    }
}
