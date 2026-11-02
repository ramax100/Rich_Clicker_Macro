package com.richclickermacro.data

import android.content.Intent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DocumentIntentsTest {
    @Test
    fun importPickerDoesNotHideJsonBehindStrictMimeFilter() {
        val intent = DocumentIntents.openJsonDocument()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
        assertArrayEquals(
            DocumentIntents.JSON_MIME_TYPES,
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        )
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun importPickerRequestsReadPermission() {
        val intent = DocumentIntents.openJsonDocument()
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun createDocumentKeepsJsonTypeAndTitle() {
        val intent = DocumentIntents.createJsonDocument("rich-clicker-macro-profiles.json")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals(
            "rich-clicker-macro-profiles.json",
            intent.getStringExtra(Intent.EXTRA_TITLE)
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test
    fun fallbackPickersUseGetContent() {
        assertEquals(Intent.ACTION_GET_CONTENT, DocumentIntents.getJsonContent().action)
        assertEquals(Intent.ACTION_GET_CONTENT, DocumentIntents.getAnyContent().action)
        assertEquals("*/*", DocumentIntents.getAnyContent().type)
    }

    @Test
    fun stripsUtf8BomThatBreaksJsonParsing() {
        val withBom = "\uFEFF{\"schemaVersion\":3}"
        assertEquals("{\"schemaVersion\":3}", DocumentIntents.stripByteOrderMark(withBom))
    }

    @Test
    fun stripsBomAndLeadingWhitespace() {
        assertEquals("{}", DocumentIntents.stripByteOrderMark("\uFEFF  \n {}"))
        assertEquals("{}", DocumentIntents.stripByteOrderMark("{}"))
    }
}
