package com.moon.eatandsport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object AppData { val lock = Any() }

class NoteRepository(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    val store = RecordStore(app)
    val mediaDir = File(app.filesDir, "media").apply { mkdirs() }

    fun importAttachment(uri: Uri): Attachment {
        val resolver = app.contentResolver
        val mime = resolver.getType(uri)?.lowercase() ?: error("無法識別附件格式")
        require(mime in Attachment.extensions) { "不支援的圖片或影片格式" }
        val fileName = Attachment.newFileName(mime)
        var displayName = fileName
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) displayName = c.getString(0).take(255).ifBlank { fileName }
        }
        val target = File(mediaDir, fileName)
        try {
            val size = requireNotNull(resolver.openInputStream(uri)).use { input ->
                target.outputStream().use { output -> copyLimited(input, output, Attachment.MAX_FILE_SIZE) }
            }
            return Attachment(UUID.randomUUID().toString(), fileName, mime, displayName, size)
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun delete(id: String) = synchronized(AppData.lock) {
        val old = store.find(id) ?: return@synchronized
        store.delete(id)
        old.attachments.forEach { File(mediaDir, it.fileName).delete() }
    }
    fun save(record: Record, pendingUris: List<Uri>): Record = synchronized(AppData.lock) {
        val created = mutableListOf<Attachment>()
        try {
            pendingUris.forEach { created += importAttachment(it) }
            val old = store.find(record.id)
            val saved = record.copy(attachments = record.attachments + created)
            store.upsert(saved)
            old?.attachments?.filter { a -> saved.attachments.none { it.fileName == a.fileName } }
                ?.forEach { File(mediaDir, it.fileName).delete() }
            saved
        } catch (e: Exception) { created.forEach { File(mediaDir, it.fileName).delete() }; throw e }
    }
    override fun close() { store.close() }
    companion object {
        fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "檔案超過大小限制" }
                output.write(buffer, 0, count)
            }
            return total
        }
    }
}
