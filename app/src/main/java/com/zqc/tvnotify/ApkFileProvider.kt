package com.zqc.tvnotify

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 零 AndroidX 依赖的原生 ContentProvider：
 * 专为 Android 7.0+ (API 24+) 系统安装器提供安全安全的 content:// URI 句柄访问能力。
 */
class ApkFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = getFileForUri(uri)
        if (file == null || !file.exists()) {
            throw FileNotFoundException("APK file not found for: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = getFileForUri(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        if (file != null && file.exists()) {
            val row = arrayOfNulls<Any>(cols.size)
            for (i in cols.indices) {
                when (cols[i]) {
                    OpenableColumns.DISPLAY_NAME -> row[i] = file.name
                    OpenableColumns.SIZE -> row[i] = file.length()
                }
            }
            cursor.addRow(row)
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun getFileForUri(uri: Uri): File? {
        val ctx = context ?: return null
        val path = uri.path ?: return null
        val fileName = File(path).name
        val candidates = listOf(
            File(ctx.cacheDir, "updates/$fileName"),
            File(ctx.cacheDir, fileName),
            File(ctx.externalCacheDir, "updates/$fileName"),
            File(ctx.externalCacheDir, fileName)
        )
        return candidates.firstOrNull { it.exists() } ?: File(ctx.cacheDir, "updates/$fileName")
    }

    companion object {
        const val AUTHORITY = "com.zqc.tvnotify.apkprovider"

        fun getUriForFile(context: Context, file: File): Uri {
            return Uri.parse("content://$AUTHORITY/${file.name}")
        }
    }
}
