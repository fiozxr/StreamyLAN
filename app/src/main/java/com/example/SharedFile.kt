package com.example

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64

data class SharedFile(
    val uriString: String,
    val name: String,
    val size: Long,
    val mimeType: String
) {
    fun toSerializedString(): String {
        val encUri = Base64.encodeToString(uriString.toByteArray(), Base64.NO_WRAP)
        val encName = Base64.encodeToString(name.toByteArray(), Base64.NO_WRAP)
        val encMime = Base64.encodeToString(mimeType.toByteArray(), Base64.NO_WRAP)
        return "$encUri|$encName|$size|$encMime"
    }

    companion object {
        fun fromSerializedString(str: String): SharedFile? {
            return try {
                val parts = str.split("|")
                if (parts.size < 4) return null
                val uri = String(Base64.decode(parts[0], Base64.NO_WRAP))
                val name = String(Base64.decode(parts[1], Base64.NO_WRAP))
                val size = parts[2].toLong()
                val mime = String(Base64.decode(parts[3], Base64.NO_WRAP))
                SharedFile(uri, name, size, mime)
            } catch (e: Exception) {
                null
            }
        }

        fun fromUri(context: Context, uri: Uri): SharedFile? {
            val contentResolver = context.contentResolver
            var name = "Shared Video"
            var size = 0L
            val mimeType = contentResolver.getType(uri) ?: "video/mp4"

            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex) ?: name
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback for size if query fails
            if (size == 0L) {
                try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        size = afd.length
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return SharedFile(uri.toString(), name, size, mimeType)
        }
    }
}
