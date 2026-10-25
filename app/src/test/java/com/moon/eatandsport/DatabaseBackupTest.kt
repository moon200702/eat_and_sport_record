package com.moon.eatandsport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class DatabaseBackupTest {
    private val today = LocalDate.of(2026, 10, 26)
    @Before fun reset() = TestSupport.reset()
    private fun food(name: String = "午餐", note: String = "蔬菜與雞肉") = Record(date = today, type = RecordType.FOOD,
        name = name, calories = 600, time = LocalTime.of(12, 30), note = note)
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    @Test fun migratesLegacyPreferencesExactlyOnceWithoutInventingTime() {
        val record = food()
        val legacy = JSONArray().put(JSONObject().apply {
            put("id", record.id); put("date", record.date); put("type", "FOOD"); put("name", record.name)
            put("calories", 600); put("minutes", 0)
        }).toString()
        TestSupport.context.getSharedPreferences("records", Context.MODE_PRIVATE).edit().putString("entries", legacy).commit()
        RecordStore(TestSupport.context).use { store ->
            val migrated = store.load().single()
            assertEquals(record.id, migrated.id); assertEquals(600, migrated.calories); assertFalse(migrated.timeKnown)
            assertTrue(store.search(time = LocalTime.NOON).isEmpty())
            store.upsert(migrated.copy(name = "已編輯"))
        }
        RecordStore(TestSupport.context).use { assertEquals("已編輯", it.load().single().name) }
        assertEquals(legacy, TestSupport.context.getSharedPreferences("records", Context.MODE_PRIVATE).getString("entries", null))
        assertEquals("SQLite format 3", TestSupport.context.getDatabasePath(RecordStore.DATABASE_NAME).inputStream().use { String(it.readNBytes(15)) })
    }

    @Test fun corruptLegacyDataIsNotOverwritten() {
        val prefs = TestSupport.context.getSharedPreferences("records", Context.MODE_PRIVATE)
        prefs.edit().putString("entries", "broken-json").commit()
        RecordStore(TestSupport.context).use { store -> assertThrows(Exception::class.java) { store.load() } }
        assertEquals("broken-json", prefs.getString("entries", null))
    }

    @Test fun searchesChineseBodyAndLiteralWildcardWithDateTimeAndType() {
        RecordStore(TestSupport.context).use { store ->
            val exercise = Record(date = today, type = RecordType.EXERCISE, name = "深蹲", calories = 0,
                time = LocalTime.of(9, 15), note = "改善姿勢 100% _slow_ ' OR 1=1 --")
            store.save(listOf(food(), exercise, food("昨天").copy(date = today.minusDays(1))))
            assertEquals(listOf(exercise), store.search("姿勢"))
            assertEquals(listOf(exercise), store.search("%"))
            assertEquals(listOf(exercise), store.search("_slow_"))
            assertEquals(listOf(exercise), store.search("' OR 1=1 --"))
            assertEquals(listOf(exercise), store.search("深蹲", RecordType.EXERCISE, today, LocalTime.of(9, 15)))
            assertTrue(store.search("深蹲", RecordType.FOOD).isEmpty())
            assertTrue(store.search("", date = today, time = LocalTime.of(9, 16)).isEmpty())
        }
    }

    @Test fun failedUpdateRollsBackRecordAndItsAttachment() {
        NoteRepository(TestSupport.context).use { repo ->
            val first = withMedia(repo, food(), "first".toByteArray(), "image/png")
            val second = food("另一餐")
            repo.store.save(listOf(first, second))
            val conflicting = second.copy(attachments = listOf(first.attachments.single().copy(fileName = Attachment.newFileName("image/png"))))
            assertThrows(Exception::class.java) { repo.store.upsert(conflicting) }
            assertEquals(second, repo.store.find(second.id))
            assertEquals(first, repo.store.find(first.id))
        }
    }

    @Test fun fullBackupRestoresSQLiteTextImagesAndVideoBytes() {
        NoteRepository(TestSupport.context).use { repo ->
            val photo = "image bytes".toByteArray(); val video = "video bytes".toByteArray()
            val record = withMedia(repo, withMedia(repo, food(), photo, "image/png"), video, "video/mp4")
            repo.store.upsert(record)
            val manager = BackupManager(TestSupport.context, repo)
            val bytes = ByteArrayOutputStream().also { manager.export(it) }.toByteArray()
            val entryNames = mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val entry = z.nextEntry ?: break; entryNames += entry.name; z.readBytes() } }
            assertTrue(entryNames.contains("records.sqlite")); assertEquals(3, entryNames.size)
            repo.delete(record.id)
            assertTrue(repo.store.load().isEmpty()); assertTrue(repo.mediaDir.listFiles().orEmpty().isEmpty())
            manager.prepare(ByteArrayInputStream(bytes)).use { prepared ->
                assertEquals(record, prepared.records.single())
                assertEquals(ImportResult(1, 0), manager.apply(prepared, ImportMode.REPLACE))
            }
            val restored = repo.store.load().single()
            assertEquals(record.copy(attachments = emptyList()), restored.copy(attachments = emptyList()))
            assertArrayEquals(photo, File(repo.mediaDir, restored.attachments[0].fileName).readBytes())
            assertArrayEquals(video, File(repo.mediaDir, restored.attachments[1].fileName).readBytes())
        }
    }

    @Test fun mergingIsRepeatableAndKeepsExistingEditsAndMedia() {
        NoteRepository(TestSupport.context).use { repo ->
            val original = withMedia(repo, food(), "source".toByteArray(), "image/png")
            repo.store.upsert(original)
            val manager = BackupManager(TestSupport.context, repo)
            val bytes = ByteArrayOutputStream().also { manager.export(it) }.toByteArray()
            repo.store.upsert(original.copy(note = "手機上較新的修改"))
            repeat(2) {
                manager.prepare(ByteArrayInputStream(bytes)).use { prepared -> assertEquals(ImportResult(0, 1), manager.apply(prepared, ImportMode.MERGE)) }
            }
            assertEquals("手機上較新的修改", repo.store.load().single().note)
            assertEquals(1, repo.mediaDir.listFiles().orEmpty().size)
            assertEquals("source", File(repo.mediaDir, original.attachments.single().fileName).readText())
        }
    }

    @Test fun mergeRenamesIncomingMediaToAvoidFileNameCollisions() {
        NoteRepository(TestSupport.context).use { repo ->
            val original = withMedia(repo, food(), "original".toByteArray(), "image/png")
            repo.store.upsert(original)
            val manager = BackupManager(TestSupport.context, repo)
            val bytes = ByteArrayOutputStream().also { manager.export(it) }.toByteArray()
            // Same file name with different bytes already present on the destination device.
            val existing = original.copy(id = UUID.randomUUID().toString(), name = "本機紀錄")
            repo.store.save(listOf(existing))
            File(repo.mediaDir, existing.attachments.single().fileName).writeText("existing")
            manager.prepare(ByteArrayInputStream(bytes)).use { assertEquals(ImportResult(1, 0), manager.apply(it, ImportMode.MERGE)) }
            assertEquals("existing", File(repo.mediaDir, existing.attachments.single().fileName).readText())
            val imported = repo.store.find(original.id)!!
            assertNotEquals(existing.attachments.single().fileName, imported.attachments.single().fileName)
            assertEquals("original", File(repo.mediaDir, imported.attachments.single().fileName).readText())
        }
    }

    @Test fun invalidBackupsDoNotChangeExistingRecordsOrFiles() {
        NoteRepository(TestSupport.context).use { repo ->
            val record = withMedia(repo, food(), "keep".toByteArray(), "video/mp4")
            repo.store.upsert(record)
            val manager = BackupManager(TestSupport.context, repo)
            listOf(
                zip(mapOf("../outside" to "bad".toByteArray())),
                zip(mapOf("/absolute" to "bad".toByteArray())),
                zip(mapOf("records.sqlite" to "not sqlite".toByteArray())),
                zip(mapOf("other.txt" to byteArrayOf(1))),
            ).forEach { invalid -> assertThrows(Exception::class.java) { manager.prepare(ByteArrayInputStream(invalid)) } }
            val db = File(TestSupport.context.cacheDir, "missing-media.sqlite")
            repo.store.exportDatabase(db)
            assertThrows(Exception::class.java) { manager.prepare(ByteArrayInputStream(zip(mapOf("records.sqlite" to db.readBytes())))) }
            assertEquals(listOf(record), repo.store.load())
            assertEquals("keep", File(repo.mediaDir, record.attachments.single().fileName).readText())
        }
    }

    @Test fun rejectsUnsupportedDatabaseVersionAndInvalidRecords() {
        NoteRepository(TestSupport.context).use { repo ->
            val db = File(TestSupport.context.cacheDir, "future.sqlite")
            repo.store.exportDatabase(db, listOf(food()))
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
            assertThrows(IllegalArgumentException::class.java) { RecordStore.readBackup(db) }
        }
    }

    @Test fun boundedStreamingRejectsOversizedInputWithoutAllocatingWholeFile() {
        assertThrows(IllegalArgumentException::class.java) {
            NoteRepository.copyLimited(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Attachment(UUID.randomUUID().toString(), "../escape.png", "image/png", "escape", 1)
        }
    }

    private fun withMedia(repo: NoteRepository, record: Record, bytes: ByteArray, mime: String): Record {
        val fileName = Attachment.newFileName(mime)
        File(repo.mediaDir, fileName).writeBytes(bytes)
        return record.copy(attachments = record.attachments + Attachment(UUID.randomUUID().toString(), fileName, mime, "附件-${record.attachments.size}", bytes.size.toLong()))
    }
}
