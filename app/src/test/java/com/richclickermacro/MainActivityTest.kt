package com.richclickermacro

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityTest {
    @Test
    fun launchesWithNewPackageAndDisabledEditorWhenAccessibilityOff() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("com.richclickermacro", activity.packageName)
        val root = activity.findViewById<View>(android.R.id.content)
        assertNotNull(root)
        val texts = collectTexts(root)
        assertTrue(texts.any { it.contains("Rich Clicker Macro") })
        assertTrue(texts.any { it.contains("STOP di menu") })
        val editorButton = findButton(root, "Tampilkan Editor di Atas Aplikasi")
        assertNotNull(editorButton)
        assertFalse(editorButton!!.isEnabled)
        controller.pause().stop().destroy()
    }

    private fun collectTexts(view: View): List<String> {
        val result = mutableListOf<String>()
        if (view is TextView) result.add(view.text.toString())
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) result.addAll(collectTexts(view.getChildAt(i)))
        }
        return result
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findButton(view.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }
}
