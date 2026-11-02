package com.richclickermacro.data

import android.content.Intent

/**
 * Builders for Storage Access Framework pickers.
 *
 * Some Android 12 devices/ROMs ship without a usable DocumentsUI provider, and many
 * providers label `.json` files as `text/plain` or `application/octet-stream`.
 * Filtering strictly on `application/json` hides valid files, so the picker accepts
 * a broad base type and advertises preferred MIME types instead. Content is always validated
 * after reading, never trusted from the MIME type.
 */
object DocumentIntents {
    val JSON_MIME_TYPES: Array<String> = arrayOf(
        "application/json",
        "text/plain",
        "application/octet-stream"
    )

    val TEXT_MIME_TYPES: Array<String> = arrayOf(
        "text/plain",
        "text/*",
        "application/octet-stream"
    )

    fun createJsonDocument(fileName: String): Intent =
        createDocument(fileName, "application/json")

    fun createTextDocument(fileName: String): Intent =
        createDocument(fileName, "text/plain")

    private fun createDocument(fileName: String, mimeType: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    /** Primary import picker: SAF, broad filter so JSON is selectable everywhere. */
    fun openJsonDocument(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, JSON_MIME_TYPES)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Fallback import picker for devices without a SAF documents provider. */
    fun getJsonContent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, JSON_MIME_TYPES)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Last-resort import picker: no MIME hint at all. */
    fun getAnyContent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Removes a UTF-8 BOM. Files exported from Windows editors frequently start
     * with U+FEFF, which makes org.json fail with "Value ... of type java.lang.String
     * cannot be converted to JSONObject".
     */
    fun stripByteOrderMark(value: String): String =
        value.removePrefix("\uFEFF").trimStart()
}
