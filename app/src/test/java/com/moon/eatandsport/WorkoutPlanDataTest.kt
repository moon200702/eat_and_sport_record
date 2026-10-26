package com.moon.eatandsport

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class WorkoutPlanDataTest {
    @Before fun reset() = TestSupport.reset()
    private fun step(plan: WorkoutPlan, name: String) = Record(date = LocalDate.now(), type = RecordType.EXERCISE,
        name = name, calories = 100, minutes = 10, note = "3 組 × 12 下，休息 60 秒", time = LocalTime.NOON, timeKnown = false, planId = plan.id)
    private fun addMedia(repo: NoteRepository, record: Record, bytes: String, mime: String = "image/png"): Record {
        val filename = Attachment.newFileName(mime)
        File(repo.mediaDir, filename).writeText(bytes)
        return record.copy(attachments = listOf(Attachment(UUID.randomUUID().toString(), filename, mime, "示範", bytes.toByteArray().size.toLong())))
    }
    private fun legacyDatabase(file: File, record: Record) {
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata VALUES ('legacy_migrated', '1')")
            db.execSQL("""CREATE TABLE records (id TEXT PRIMARY KEY NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL,
                type TEXT NOT NULL CHECK(type IN ('FOOD','EXERCISE')), name TEXT NOT NULL,
                calories INTEGER NOT NULL CHECK(calories BETWEEN 0 AND 50000), minutes INTEGER NOT NULL CHECK(minutes BETWEEN 0 AND 1440),
                note TEXT NOT NULL, time_known INTEGER NOT NULL CHECK(time_known IN (0,1)))""")
            db.execSQL("""CREATE TABLE attachments (id TEXT PRIMARY KEY NOT NULL,
                record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE, file_name TEXT UNIQUE NOT NULL,
                mime TEXT NOT NULL, display_name TEXT NOT NULL, size INTEGER NOT NULL)""")
            db.execSQL("INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?)", arrayOf<Any>(record.id, record.date.toString(), record.time.toString(),
                record.type.name, record.name, record.calories, record.minutes, record.note, if (record.timeKnown) 1 else 0))
            record.attachments.forEach { a -> db.execSQL("INSERT INTO attachments VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(a.id, record.id, a.fileName, a.mime, a.displayName, a.size)) }
            db.version = 1
        }
    }
    private fun archive(file: File, records: List<Record>, media: File): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("records.sqlite")); zip.write(file.readBytes()); zip.closeEntry()
            records.flatMap { it.attachments }.forEach { a ->
                zip.putNextEntry(ZipEntry("media/${a.fileName}")); zip.write(File(media, a.fileName).readBytes()); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun upgradesVersionOneWithoutChangingNotesOrAttachments() {
        val plan = WorkoutPlan(name = "腿部日")
        NoteRepository(TestSupport.context).use { repo ->
            val old = addMedia(repo, Record(date = LocalDate.now(), type = RecordType.FOOD, name = "舊午餐", calories = 650), "old photo")
            legacyDatabase(TestSupport.context.getDatabasePath(RecordStore.DATABASE_NAME), old)
            assertEquals(listOf(old), repo.store.load())
            assertTrue(repo.store.snapshot().plans.isEmpty())
            repo.store.savePlan(plan)
            repo.store.upsert(step(plan, "深蹲"))
            assertEquals(listOf(old), repo.store.load())
            assertEquals("old photo", File(repo.mediaDir, old.attachments.single().fileName).readText())
            SQLiteDatabase.openDatabase(TestSupport.context.getDatabasePath(RecordStore.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(2, it.version) }
        }
    }

    @Test fun supportsMultiplePlansAndRenamingKeepsTheirIndependentItems() {
        RecordStore(TestSupport.context).use { store ->
            val legs = WorkoutPlan(name = "腿部日", description = "週一")
            val upper = WorkoutPlan(name = "上肢日")
            store.savePlan(legs); store.savePlan(upper)
            val squat = store.upsert(step(legs, "深蹲")); store.upsert(step(upper, "伏地挺身"))
            store.savePlan(legs.copy(name = "腿部與核心", description = "週一、週四"))
            assertEquals("腿部與核心", store.findPlan(legs.id)!!.name)
            assertEquals(listOf(squat), store.snapshot().items(legs.id))
            assertEquals("伏地挺身", store.snapshot().items(upper.id).single().name)
            assertTrue(store.load().isEmpty()); assertTrue(store.search("深蹲").isEmpty())
            assertEquals(DaySummary(0, 0, 0), summarize(store.snapshot().records, LocalDate.now()))
        }
    }

    @Test fun reorderingAndEditingPersistWithoutLosingSteps() {
        val plan = WorkoutPlan(name = "全身")
        RecordStore(TestSupport.context).use { store ->
            store.savePlan(plan)
            val first = store.upsert(step(plan, "暖身")); val second = store.upsert(step(plan, "深蹲")); val third = store.upsert(step(plan, "伸展"))
            assertEquals(listOf(0, 1, 2), store.snapshot().items(plan.id).map { it.planPosition })
            store.movePlanItem(third.id, -1)
            store.movePlanItem(first.id, -1) // Boundary is a no-op.
            store.upsert(store.find(third.id)!!.copy(note = "更新伸展內容"))
            assertEquals(listOf(first.id, third.id, second.id), store.snapshot().items(plan.id).map { it.id })
        }
        RecordStore(TestSupport.context).use { store ->
            assertEquals(listOf("暖身", "伸展", "深蹲"), store.snapshot().items(plan.id).map { it.name })
            assertEquals("更新伸展內容", store.snapshot().items(plan.id)[1].note)
        }
    }

    @Test fun deletingPlanCascadesOnlyItsItemsAndMedia() {
        NoteRepository(TestSupport.context).use { repo ->
            val first = WorkoutPlan(name = "A"); val second = WorkoutPlan(name = "B")
            repo.store.savePlan(first); repo.store.savePlan(second)
            val removed = repo.store.upsert(addMedia(repo, step(first, "深蹲"), "remove"))
            val retained = repo.store.upsert(addMedia(repo, step(second, "慢跑"), "keep"))
            repo.deletePlan(first.id)
            assertNull(repo.store.findPlan(first.id)); assertNull(repo.store.find(removed.id))
            assertFalse(File(repo.mediaDir, removed.attachments.single().fileName).exists())
            assertEquals(retained, repo.store.find(retained.id))
            assertEquals("keep", File(repo.mediaDir, retained.attachments.single().fileName).readText())
        }
    }

    @Test fun backupRestoresMultipleAndEmptyPlansTheirOrderAndMedia() {
        NoteRepository(TestSupport.context).use { repo ->
            val plan = WorkoutPlan(name = "居家訓練", description = "循序練習"); val empty = WorkoutPlan(name = "新菜單")
            repo.store.savePlan(plan); repo.store.savePlan(empty)
            repo.store.upsert(addMedia(repo, step(plan, "暖身"), "image"))
            val item = repo.store.upsert(addMedia(repo, step(plan, "深蹲"), "video", "video/mp4"))
            repo.store.movePlanItem(item.id, -1)
            val planOrder = repo.store.snapshot().plans
            val manager = BackupManager(TestSupport.context, repo)
            val bytes = ByteArrayOutputStream().also { manager.export(it) }.toByteArray()
            repo.deletePlan(plan.id); repo.deletePlan(empty.id)
            manager.prepare(bytes.inputStream()).use { prepared ->
                assertEquals(2, prepared.plans.size); assertTrue(prepared.records.isEmpty())
                assertEquals(ImportResult(0, 0, 2, 0, 2), manager.apply(prepared, ImportMode.REPLACE))
            }
            val snapshot = repo.store.snapshot()
            assertEquals(planOrder, snapshot.plans)
            assertEquals(listOf("深蹲", "暖身"), snapshot.items(plan.id).map { it.name })
            assertEquals("video", File(repo.mediaDir, snapshot.items(plan.id)[0].attachments.single().fileName).readText())
            assertEquals("image", File(repo.mediaDir, snapshot.items(plan.id)[1].attachments.single().fileName).readText())
        }
    }

    @Test fun repeatedMergeKeepsEntireExistingPlanIncludingLocalEdits() {
        NoteRepository(TestSupport.context).use { repo ->
            val plan = WorkoutPlan(name = "原菜單"); repo.store.savePlan(plan)
            val item = repo.store.upsert(step(plan, "原動作"))
            val manager = BackupManager(TestSupport.context, repo)
            val bytes = ByteArrayOutputStream().also { manager.export(it) }.toByteArray()
            repo.store.savePlan(plan.copy(name = "本機修改")); repo.store.upsert(item.copy(note = "本機組數"))
            val added = repo.store.upsert(step(plan, "本機新動作"))
            repeat(2) { manager.prepare(bytes.inputStream()).use { assertEquals(ImportResult(0, 0, 0, 1, 0), manager.apply(it, ImportMode.MERGE)) } }
            assertEquals("本機修改", repo.store.findPlan(plan.id)!!.name)
            assertEquals("本機組數", repo.store.find(item.id)!!.note)
            assertNotNull(repo.store.find(added.id)); assertEquals(2, repo.store.snapshot().items(plan.id).size)
        }
    }

    @Test fun versionOneBackupMergesWithoutRemovingPlansAndCanReplaceThem() {
        NoteRepository(TestSupport.context).use { repo ->
            val plan = WorkoutPlan(name = "保留的菜單"); repo.store.savePlan(plan); repo.store.upsert(step(plan, "伸展"))
            val old = Record(date = LocalDate.now(), type = RecordType.FOOD, name = "舊備份午餐", calories = 200)
            val file = File(TestSupport.context.cacheDir, "legacy-backup.sqlite"); legacyDatabase(file, old)
            val manager = BackupManager(TestSupport.context, repo)
            val bytes = archive(file, listOf(old), repo.mediaDir)
            manager.prepare(bytes.inputStream()).use { assertEquals(ImportResult(1, 0), manager.apply(it, ImportMode.MERGE)) }
            assertEquals(plan, repo.store.findPlan(plan.id)); assertEquals(listOf(old), repo.store.load())
            manager.prepare(bytes.inputStream()).use { manager.apply(it, ImportMode.REPLACE) }
            assertTrue(repo.store.snapshot().plans.isEmpty()); assertEquals(listOf(old), repo.store.load())
        }
    }

    @Test fun orphanedMenuInBackupIsRejectedBeforeChangingAnyData() {
        NoteRepository(TestSupport.context).use { repo ->
            val plan = WorkoutPlan(name = "菜單"); repo.store.savePlan(plan); repo.store.upsert(step(plan, "深蹲"))
            val before = repo.store.snapshot()
            val file = File(TestSupport.context.cacheDir, "orphan.sqlite"); repo.store.exportDatabase(file)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.setForeignKeyConstraintsEnabled(false); db.execSQL("DELETE FROM workout_plans")
            }
            val bytes = archive(file, emptyList(), repo.mediaDir)
            assertThrows(Exception::class.java) { BackupManager(TestSupport.context, repo).prepare(bytes.inputStream()) }
            assertEquals(before, repo.store.snapshot())
        }
    }
}
