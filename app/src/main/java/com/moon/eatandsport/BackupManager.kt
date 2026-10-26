package com.moon.eatandsport

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ImportMode { MERGE, REPLACE }
data class ImportResult(val added: Int, val skipped: Int, val plansAdded: Int = 0, val plansSkipped: Int = 0, val itemsAdded: Int = 0)
class PreparedBackup(val directory: File, val snapshot: NoteSnapshot) : AutoCloseable {
    val records get() = snapshot.dailyRecords
    val plans get() = snapshot.plans
    override fun close() { directory.deleteRecursively() }
}

/** Standalone SQLite snapshot + the actual media bytes, never transient picker URI grants. */
class BackupManager(context: Context, private val repository: NoteRepository) {
    private val cacheDir = context.cacheDir

    fun export(output: OutputStream) = synchronized(AppData.lock) {
        val temporary = File(cacheDir, "export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.store.snapshot()
            val database = File(temporary, "records.sqlite")
            repository.store.exportSnapshot(database, snapshot)
            require(database.length() <= MAX_DATABASE_SIZE) { "資料庫超過 128 MB" }
            val attachments = snapshot.records.flatMap { it.attachments }
            require(attachments.size < MAX_ENTRIES) { "附件數量超過備份限制" }
            require(database.length() + attachments.sumOf { it.size } <= MAX_TOTAL_SIZE) { "備份超過 4 GB" }
            ZipOutputStream(output).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                fun entry(name: String, file: File) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                entry("records.sqlite", database)
                attachments.forEach { a ->
                    val file = File(repository.mediaDir, a.fileName)
                    require(file.isFile && file.length() == a.size) { "附件遺失或損壞：${a.displayName}" }
                    entry("media/${a.fileName}", file)
                }
            }
        } finally { temporary.deleteRecursively() }
    }

    fun prepare(input: InputStream): PreparedBackup {
        val stage = File(cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(names.add(name) && names.size <= MAX_ENTRIES + 1) { "備份包含重複項目或過多附件" }
                    if (entry.isDirectory) {
                        require(name == "media/") { "備份包含無效路徑" }
                        zip.closeEntry()
                        continue
                    }
                    val database = name == "records.sqlite"
                    require(database || (name.startsWith("media/") && Attachment.validFileName(name.removePrefix("media/")))) {
                        "備份包含不支援的檔案路徑"
                    }
                    val file = File(stage, name)
                    file.parentFile?.mkdirs()
                    val limit = minOf(if (database) MAX_DATABASE_SIZE else Attachment.MAX_FILE_SIZE, MAX_TOTAL_SIZE - total)
                    total += file.outputStream().use { NoteRepository.copyLimited(zip, it, limit) }
                    zip.closeEntry()
                }
            }
            require("records.sqlite" in names) { "備份中找不到 records.sqlite" }
            val snapshot = RecordStore.readBackupSnapshot(File(stage, "records.sqlite"))
            val media = snapshot.records.flatMap { it.attachments }
            require(names - "media/" == setOf("records.sqlite") + media.map { "media/${it.fileName}" }) { "備份的附件清單不完整" }
            media.forEach { a -> require(File(stage, "media/${a.fileName}").length() == a.size) { "附件大小不符：${a.displayName}" } }
            return PreparedBackup(stage, snapshot)
        } catch (e: Exception) { stage.deleteRecursively(); throw e }
    }

    /** Stage new, unique media names first; a SQLite transaction is the commit point. */
    fun apply(prepared: PreparedBackup, mode: ImportMode): ImportResult = synchronized(AppData.lock) {
        val old = repository.store.snapshot()
        val ids = old.records.map { it.id }.toSet()
        val planIds = old.plans.map { it.id }.toSet()
        val plans = if (mode == ImportMode.MERGE) prepared.plans.filter { it.id !in planIds } else prepared.plans
        val newPlanIds = plans.map { it.id }.toSet()
        val incoming = if (mode == ImportMode.MERGE) prepared.snapshot.records.filter {
            if (it.planId == null) it.id !in ids else it.planId in newPlanIds
        } else prepared.snapshot.records
        require(mode != ImportMode.MERGE || incoming.none { it.planId != null && it.id in ids }) { "菜單動作識別碼與既有紀錄衝突，請改用其他備份或取代全部。" }
        val created = mutableListOf<File>()
        try {
            val copied = incoming.map { record ->
                record.copy(attachments = record.attachments.map { a ->
                    val name = Attachment.newFileName(a.mime)
                    val target = File(repository.mediaDir, name)
                    created += target
                    File(prepared.directory, "media/${a.fileName}").inputStream().use { input ->
                        target.outputStream().use { output -> NoteRepository.copyLimited(input, output, a.size) }
                    }
                    require(target.length() == a.size) { "附件複製失敗" }
                    a.copy(id = UUID.randomUUID().toString(), fileName = name)
                })
            }
            val result = if (mode == ImportMode.MERGE) NoteSnapshot(old.records + copied, old.plans + plans) else NoteSnapshot(copied, plans)
            repository.store.saveSnapshot(result)
            if (mode == ImportMode.REPLACE) old.records.flatMap { it.attachments }.forEach { File(repository.mediaDir, it.fileName).delete() }
            val added = copied.count { it.planId == null }
            ImportResult(added, if (mode == ImportMode.MERGE) prepared.records.size - added else 0,
                plans.size, if (mode == ImportMode.MERGE) prepared.plans.size - plans.size else 0, copied.count { it.planId != null })
        } catch (e: Exception) { created.forEach { it.delete() }; throw e }
    }

    companion object {
        const val MAX_DATABASE_SIZE = 128L * 1024 * 1024
        const val MAX_TOTAL_SIZE = 4L * 1024 * 1024 * 1024
        const val MAX_ENTRIES = 100_001
    }
}
