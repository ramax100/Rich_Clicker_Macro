package com.richclickermacro.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PublicDownloadsTest {
    @Test
    fun savesUtf8TextAndPublishesDownloadWithoutStoragePermission() {
        val provider = registerProvider()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val location = PublicDownloads.saveText(context, "profiles.json", "application/json", "{\"ok\":true}")
        assertEquals("Download/profiles.json", location)
        assertEquals(0, provider.values.getValue(1L).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals("{\"ok\":true}", provider.file(1L).readText(Charsets.UTF_8))
    }

    @Test
    fun reportsActualNameWhenProviderAddsSuffix() {
        val provider = registerProvider()
        provider.suffix = " (1)"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val location = PublicDownloads.saveText(context, "profiles.json", "application/json", "{}")
        assertEquals("Download/profiles (1).json", location)
    }

    @Test
    fun insertFailureReturnsNullInsteadOfThrowing() {
        val provider = registerProvider()
        provider.failInsert = true
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(PublicDownloads.saveText(context, "profiles.json", "application/json", "{}"))
    }

    @Test
    fun writeFailureDeletesPendingEntryAndReturnsNull() {
        val provider = registerProvider()
        provider.failWrite = true
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(PublicDownloads.saveText(context, "profiles.json", "application/json", "{}"))
        assertTrue(provider.deleted.contains(1L))
    }

    @Test
    @Config(sdk = [28])
    fun unsupportedAndroidVersionReturnsNullInsteadOfCrashing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(PublicDownloads.saveText(context, "profiles.json", "application/json", "{}"))
    }

    private fun registerProvider(): FakeDownloadsProvider {
        val controller: ContentProviderController<FakeDownloadsProvider> =
            Robolectric.buildContentProvider(FakeDownloadsProvider::class.java)
        controller.create("media")
        return controller.get()
    }

    class FakeDownloadsProvider : ContentProvider() {
        val values = mutableMapOf<Long, ContentValues>()
        val deleted = mutableListOf<Long>()
        var failInsert = false
        var failWrite = false
        var suffix = ""

        fun file(id: Long): File = File(context!!.cacheDir, "download-$id.json")

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, initialValues: ContentValues?): Uri? {
            if (failInsert) return null
            val id = values.size + 1L
            val stored = ContentValues(initialValues)
            val requested = stored.getAsString(MediaStore.Downloads.DISPLAY_NAME) ?: "file.json"
            if (suffix.isNotEmpty()) {
                stored.put(MediaStore.Downloads.DISPLAY_NAME, requested.removeSuffix(".json") + suffix + ".json")
            }
            stored.put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            values[id] = stored
            return Uri.withAppendedPath(uri, id.toString())
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (failWrite) throw FileNotFoundException("simulated write failure")
            val id = uri.lastPathSegment!!.toLong()
            return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }

        override fun update(uri: Uri, newValues: ContentValues?, selection: String?, args: Array<out String>?): Int {
            val id = uri.lastPathSegment!!.toLong()
            val stored = values[id] ?: return 0
            newValues?.let(stored::putAll)
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val id = uri.lastPathSegment!!.toLong()
            val columns = projection ?: arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH)
            val cursor = MatrixCursor(columns)
            val stored = values[id]
            if (stored != null) {
                cursor.addRow(columns.map { column ->
                    when (column) {
                        MediaStore.Downloads._ID -> id
                        else -> stored.getAsString(column)
                    }
                })
            }
            return cursor
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val id = uri.lastPathSegment!!.toLong()
            deleted.add(id)
            values.remove(id)
            file(id).delete()
            return 1
        }

        override fun getType(uri: Uri): String = "application/json"
    }
}
