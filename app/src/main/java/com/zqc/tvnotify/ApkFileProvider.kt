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

/** Read-only APK delivery for API 23+, restricted to updater-owned cache directories. */
class ApkFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("APK provider is read-only")
        return ParcelFileDescriptor.open(getFileForUri(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = getFileForUri(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")

    private fun getFileForUri(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("Provider unavailable")
        val segments = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != AUTHORITY || segments.size != 2) throw FileNotFoundException("Invalid APK URI")
        val name = segments[1]
        if (name != File(name).name || !name.endsWith(".apk", true)) throw FileNotFoundException("Invalid APK name")
        val root = when (segments[0]) {
            "internal" -> File(ctx.cacheDir, "updates")
            "external" -> ctx.externalCacheDir?.let { File(it, "updates") }
            else -> null
        }?.canonicalFile ?: throw FileNotFoundException("APK cache unavailable")
        val file = File(root, name).canonicalFile
        if (file.parentFile != root || !file.isFile || !file.canRead()) throw FileNotFoundException("APK unavailable")
        return file
    }

    companion object {
        const val AUTHORITY = "com.zqc.tvnotify.apkprovider"

        fun getUriForFile(context: Context, file: File): Uri {
            val canonical = file.canonicalFile
            require(canonical.isFile && canonical.name.endsWith(".apk", true)) { "Invalid APK file" }
            val roots = listOfNotNull(
                "internal" to File(context.cacheDir, "updates").canonicalFile,
                context.externalCacheDir?.let { "external" to File(it, "updates").canonicalFile },
            )
            val root = roots.firstOrNull { canonical.parentFile == it.second }
                ?: throw IllegalArgumentException("APK is outside updater cache")
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(root.first).appendPath(canonical.name).build()
        }
    }
}
