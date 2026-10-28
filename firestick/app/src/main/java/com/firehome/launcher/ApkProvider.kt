package com.firehome.launcher

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
 * Serves downloaded APKs from cache/apk/ to the system package installer as content:// URIs (what
 * AndroidX's FileProvider does, without the library). Not exported: the installer only gets
 * access through the read grant on the intent (DreamUpdate's self-update).
 */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!name.endsWith(".apk") || '/' in name || name.startsWith(".")) throw FileNotFoundException(name)
        val f = File(dir(ctx), name)
        if (!f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = MIME

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val MIME = "application/vnd.android.package-archive"
        fun dir(ctx: Context) = File(ctx.cacheDir, "apk").apply { mkdirs() }
        fun uri(ctx: Context, f: File): Uri = Uri.parse("content://${ctx.packageName}.apk/${f.name}")
    }
}
