package com.brenyd.audiread.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.brenyd.audiread.data.Book
import java.io.File
import java.io.FileNotFoundException

/** Read-only access to cover thumbnails, so Android Auto and the system media controls can show artwork. */
class CoverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!name.matches(Regex("[A-Za-z0-9_-]+\\.jpg"))) throw FileNotFoundException()
        val f = File(context!!.filesDir, "covers/$name")
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun uriFor(ctx: Context, book: Book): Uri? =
            book.coverPath?.let { Uri.parse("content://${ctx.packageName}.covers/${File(it).name}") }
    }
}
