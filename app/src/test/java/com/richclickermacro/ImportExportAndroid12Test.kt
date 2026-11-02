package com.richclickermacro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.richclickermacro.data.MacroRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Regression coverage for the Android 12 force close on Import/Export JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ImportExportAndroid12Test {
    @Test
    fun exportJsonDoesNotCrashOnAndroid12() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Export JSON")
        assertFalse(activity.isFinishing)
        assertFalse(activity.isDestroyed)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        controller.pause().stop().destroy()
    }

    @Test
    fun importJsonUsesBroadMimeFilterOnAndroid12() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Import JSON")
        assertFalse(activity.isDestroyed)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        assertEquals("*/*", started.intent.type)
        assertTrue(started.intent.hasExtra(Intent.EXTRA_MIME_TYPES))
        controller.pause().stop().destroy()
    }

    @Test
    fun exportDiagnosticsDoesNotCrashOnAndroid12() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Export Log Diagnostik")
        assertFalse(activity.isDestroyed)
        controller.pause().stop().destroy()
    }

    @Test
    fun importFromDownloadWithoutFilesShowsMessageInsteadOfCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Import dari Download")
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Tidak ada file profil")
        controller.pause().stop().destroy()
    }

    @Test
    fun manualDownloadExportWithoutProviderShowsFailureInsteadOfCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Export ke Download")
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Semua profil gagal")
        controller.pause().stop().destroy()
    }

    @Test
    fun manualDownloadExportWithProviderSavesFileAndShowsLocation() {
        val providerController = Robolectric.buildContentProvider(
            com.richclickermacro.data.PublicDownloadsTest.FakeDownloadsProvider::class.java
        )
        providerController.create("media")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Export ke Download")
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Semua profil tersimpan")
        val message = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString()
        assertTrue(message.contains("Download/rich-clicker-macro-profiles.json"))
        assertEquals(1, providerController.get().values.size)
        controller.pause().stop().destroy()
        providerController.shutdown()
    }

    @Test
    fun missingPickerOnExportShowsFailureDialogInsteadOfCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.application).checkActivities(true)
        click(activity, "Export JSON")
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Semua profil gagal")
        controller.pause().stop().destroy()
    }

    @Test
    fun missingPickerOnImportShowsRecoveryDialogInsteadOfCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.application).checkActivities(true)
        click(activity, "Import JSON")
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Pemilih file tidak tersedia")
        val message = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message
        assertTrue(message.toString().contains("Import dari Download"))
        controller.pause().stop().destroy()
    }

    @Test
    fun missingPickerOnDiagnosticsShowsFailureDialogInsteadOfCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.application).checkActivities(true)
        click(activity, "Export Log Diagnostik")
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Log diagnostik gagal")
        controller.pause().stop().destroy()
    }

    @Test
    fun cancelledPickerDoesNotCrashOrShowError() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Export JSON")
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(request, Activity.RESULT_CANCELED, null)
        assertFalse(activity.isDestroyed)
        controller.pause().stop().destroy()
    }

    @Test
    fun exportWritesValidJsonToReturnedUri() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val uri = Uri.parse("content://test/profiles.json")
        val output = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(uri, output)
        click(activity, "Export JSON")
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        assertEquals(3, root.getInt("schemaVersion"))
        assertTrue(root.getJSONArray("profiles").length() >= 1)
        controller.pause().stop().destroy()
    }

    @Test
    fun importAcceptsUtf8BomAndKeepsExistingProfiles() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val repository = MacroRepository(activity)
        val before = repository.getProfiles().size
        val json = "\uFEFF" + repository.exportJson()
        val uri = Uri.parse("content://test/import.json")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        click(activity, "Import JSON")
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
        assertEquals(before + 1, repository.getProfiles().size)
        assertFalse(activity.isDestroyed)
        controller.pause().stop().destroy()
    }

    @Test
    fun invalidJsonShowsErrorAndDoesNotCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val repository = MacroRepository(activity)
        val before = repository.getProfiles().size
        val uri = Uri.parse("content://test/broken.json")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream("{broken".toByteArray()))
        click(activity, "Import JSON")
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
        assertFalse(activity.isDestroyed)
        assertEquals(before, repository.getProfiles().size)
        assertDialogTitle("Import gagal")
        controller.pause().stop().destroy()
    }

    @Test
    fun emptyImportShowsErrorAndDoesNotCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val uri = Uri.parse("content://test/empty.json")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(0)))
        click(activity, "Import JSON")
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
        assertFalse(activity.isDestroyed)
        assertDialogTitle("Import gagal")
        controller.pause().stop().destroy()
    }

    @Test
    fun checkRamShowsClickerMeasurementsWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Cek RAM Clicker")
        assertTrue(com.richclickermacro.diagnostics.MacroDiagnostics.flush(10_000L))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(activity.isDestroyed)
        val readout = findText(activity.findViewById(android.R.id.content), "PSS clicker:")
        assertNotNull(readout)
        assertTrue(readout!!.text.toString().contains("bukan RAM game"))
        controller.pause().stop().destroy()
    }

    private fun findText(view: View, prefix: String): android.widget.TextView? {
        if (view is android.widget.TextView && view.text.toString().startsWith(prefix)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findText(view.getChildAt(i), prefix)
                if (found != null) return found
            }
        }
        return null
    }

    private fun click(activity: Activity, text: String) {
        val button = findButton(activity.findViewById(android.R.id.content), text)
        assertNotNull("Button not found: $text", button)
        button!!.performClick()
    }

    private fun assertDialogTitle(expected: String) {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(expected, shadowOf(dialog).title.toString())
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
