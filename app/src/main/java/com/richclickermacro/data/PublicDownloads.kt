package com.richclickermacro.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/**
 * Export fallback for devices with no document picker at all.
 *
 * On Android 10+ an app may insert into its own MediaStore Downloads collection
 * without any storage permission. Only files owned by this app are visible to
 * the matching import fallback; arbitrary files still need the system picker.
 */
object PublicDownloads {
    fun saveText(context: Context, fileName: String, mimeType: String, content: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return try {
            val stream = resolver.openOutputStream(uri, "w") ?: error("Stream tidak tersedia")
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, publish, null, null) == 1) {
                "File tidak dapat dipublikasikan"
            }
            downloadLocation(uri, fileName, resolver)
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** MediaStore may add a numeric suffix when the requested name already exists. */
    private fun downloadLocation(
        uri: android.net.Uri,
        requestedName: String,
        resolver: android.content.ContentResolver
    ): String {
        val metadata = runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: requestedName
                val directory = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Download/"
                directory.trimEnd('/') + "/" + name
            }
        }.getOrNull()
        return metadata ?: "Download/$requestedName"
    }
}
