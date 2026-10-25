package com.moon.eatandsport

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

object TestSupport {
    val context: Context get() = RuntimeEnvironment.getApplication()
    fun reset() {
        context.deleteDatabase(RecordStore.DATABASE_NAME)
        context.getSharedPreferences("records", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "media").deleteRecursively()
        TestDocuments.files.clear()
        val provider = TestDocuments()
        provider.attachInfo(context, ProviderInfo().apply { authority = "notes.test" })
        ShadowContentResolver.registerProviderInternal("notes.test", provider)
    }
    fun await(message: String = "Background work did not complete", condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(15)
        }
        assertTrue(message, condition())
    }
    fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    fun texts(view: View) = views(view).filterIsInstance<TextView>().map { it.text.toString() }
    fun source(bytes: ByteArray, mime: String, name: String): Pair<Uri, File> {
        val file = File(context.cacheDir, UUID.randomUUID().toString()).apply { writeBytes(bytes) }
        val uri = Uri.parse("content://notes.test/${UUID.randomUUID()}/$name")
        TestDocuments.files[uri] = TestDocuments.Document(file, mime, name)
        return uri to file
    }
}

class TestDocuments : ContentProvider() {
    data class Document(val file: File, val mime: String, val name: String)
    companion object { val files = mutableMapOf<Uri, Document>() }
    override fun onCreate() = true
    override fun getType(uri: Uri) = files.getValue(uri).mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val document = files.getValue(uri)
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply { addRow(columns.map {
            when (it) { OpenableColumns.DISPLAY_NAME -> document.name; OpenableColumns.SIZE -> document.file.length(); else -> null }
        }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val flags = if ('w' in mode) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(files.getValue(uri).file, flags)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
