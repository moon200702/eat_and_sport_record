package com.moon.eatandsport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.Cursor
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** SQLite owns saved records; media references use portable relative names. */
class RecordStore(private val context: Context) : AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) { createSchema(db) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported database version: $oldVersion → $newVersion")
        }
    }

    private fun database(): SQLiteDatabase = synchronized(AppData.lock) {
        val db = helper.writableDatabase
        val migrated = db.rawQuery("SELECT value FROM metadata WHERE key = ?", arrayOf("legacy_migrated"))
            .use { it.moveToFirst() }
        if (!migrated) {
            // Validate the entire old store before writing; retain the original preferences.
            val legacy = JSONArray(context.getSharedPreferences("records", Context.MODE_PRIVATE).getString("entries", "[]"))
            val records = List(legacy.length()) { index ->
                val item = legacy.getJSONObject(index)
                Record(id = item.getString("id"), date = LocalDate.parse(item.getString("date")),
                    type = RecordType.valueOf(item.getString("type")), name = item.getString("name"),
                    calories = item.getInt("calories"), minutes = item.getInt("minutes"), time = LocalTime.NOON, timeKnown = false)
            }
            validate(records)
            db.transaction {
                records.forEach { insert(db, it) }
                db.execSQL("INSERT INTO metadata(key, value) VALUES (?, ?)", arrayOf("legacy_migrated", "1"))
            }
        }
        db
    }

    fun load(): List<Record> = synchronized(AppData.lock) { read(database()) }
    fun find(id: String): Record? = synchronized(AppData.lock) {
        read(database(), "id = ?", listOf(id)).singleOrNull()
    }

    fun search(query: String = "", type: RecordType? = null, date: LocalDate? = null, time: LocalTime? = null): List<Record> =
        synchronized(AppData.lock) {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (query.isNotBlank()) {
                clauses += "(name LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\' OR id IN (SELECT record_id FROM attachments WHERE display_name LIKE ? ESCAPE '\\'))"
                val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                repeat(3) { args += "%$escaped%" }
            }
            type?.let { clauses += "type = ?"; args += it.name }
            date?.let { clauses += "date = ?"; args += it.toString() }
            time?.let { clauses += "time = ? AND time_known = 1"; args += it.withSecond(0).withNano(0).toString() }
            read(database(), clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args)
        }

    fun upsert(record: Record) = synchronized(AppData.lock) {
        val db = database()
        db.transaction {
            db.delete("records", "id = ?", arrayOf(record.id))
            insert(db, record)
        }
    }
    fun delete(id: String) = synchronized(AppData.lock) { database().delete("records", "id = ?", arrayOf(id)); Unit }

    fun save(records: List<Record>): Boolean = synchronized(AppData.lock) {
        validate(records)
        val db = database()
        db.transaction {
            db.delete("records", null, null)
            records.forEach { insert(db, it) }
        }
        true
    }

    fun exportDatabase(target: File, records: List<Record> = load()) {
        validate(records)
        check(!target.exists())
        SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            createSchema(db)
            db.version = 1
            db.transaction {
                records.forEach { insert(db, it) }
                db.execSQL("INSERT INTO metadata(key, value) VALUES ('legacy_migrated', '1')")
            }
        }
    }
    override fun close() { helper.close() }

    companion object {
        const val DATABASE_NAME = "records.sqlite"
        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL("""CREATE TABLE records (
                id TEXT PRIMARY KEY NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL,
                type TEXT NOT NULL CHECK(type IN ('FOOD','EXERCISE')), name TEXT NOT NULL,
                calories INTEGER NOT NULL CHECK(calories BETWEEN 0 AND 50000),
                minutes INTEGER NOT NULL CHECK(minutes BETWEEN 0 AND 1440), note TEXT NOT NULL,
                time_known INTEGER NOT NULL CHECK(time_known IN (0,1)))""")
            db.execSQL("""CREATE TABLE attachments (
                id TEXT PRIMARY KEY NOT NULL, record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
                file_name TEXT UNIQUE NOT NULL, mime TEXT NOT NULL, display_name TEXT NOT NULL, size INTEGER NOT NULL)""")
            db.execSQL("CREATE INDEX records_date_time ON records(date, time)")
            db.execSQL("CREATE INDEX records_type_date ON records(type, date)")
            db.execSQL("CREATE INDEX attachments_record ON attachments(record_id)")
        }
        private fun validate(records: List<Record>) {
            require(records.size <= 100_000)
            require(records.map { it.id }.distinct().size == records.size)
            val attachments = records.flatMap { it.attachments }
            require(attachments.size <= 100_000)
            require(attachments.map { it.id }.distinct().size == attachments.size)
            require(attachments.map { it.fileName }.distinct().size == attachments.size)
        }
        private fun insert(db: SQLiteDatabase, record: Record) {
            db.insertOrThrow("records", null, ContentValues().apply {
                put("id", record.id); put("date", record.date.toString())
                put("time", record.time.withSecond(0).withNano(0).toString()); put("type", record.type.name)
                put("name", record.name); put("calories", record.calories); put("minutes", record.minutes); put("note", record.note)
                put("time_known", if (record.timeKnown) 1 else 0)
            })
            record.attachments.forEach { a ->
                db.insertOrThrow("attachments", null, ContentValues().apply {
                    put("id", a.id); put("record_id", record.id); put("file_name", a.fileName)
                    put("mime", a.mime); put("display_name", a.displayName); put("size", a.size)
                })
            }
        }
        private fun read(db: SQLiteDatabase, selection: String? = null, args: List<String> = emptyList()): List<Record> {
            val media = mutableMapOf<String, MutableList<Attachment>>()
            val mediaSelection = selection?.let { "record_id IN (SELECT id FROM records WHERE $it)" }
            var attachmentCount = 0
            db.query("attachments", null, mediaSelection, if (selection == null) null else args.toTypedArray(), null, null, "rowid").use { c ->
                while (c.moveToNext()) {
                    require(++attachmentCount <= 100_000)
                    fun text(name: String) = c.getString(c.getColumnIndexOrThrow(name))
                    media.getOrPut(text("record_id")) { mutableListOf() }.add(Attachment(text("id"), text("file_name"),
                        text("mime"), text("display_name"), c.getLong(c.getColumnIndexOrThrow("size"))))
                }
            }
            val records = mutableListOf<Record>()
            db.query("records", null, selection, args.toTypedArray(), null, null, "date DESC, time DESC, rowid DESC").use { c ->
                while (c.moveToNext()) {
                    require(records.size < 100_000)
                    fun text(name: String) = c.getString(c.getColumnIndexOrThrow(name))
                    fun number(name: String, maximum: Long): Int {
                        val index = c.getColumnIndexOrThrow(name)
                        require(c.getType(index) == Cursor.FIELD_TYPE_INTEGER)
                        val value = c.getLong(index)
                        require(value in 0..maximum)
                        return value.toInt()
                    }
                    records += Record(id = text("id"), date = LocalDate.parse(text("date")), time = LocalTime.parse(text("time")),
                        type = RecordType.valueOf(text("type")), name = text("name"), calories = number("calories", 50_000),
                        minutes = number("minutes", 1_440), note = text("note"), attachments = media[text("id")].orEmpty(),
                        timeKnown = number("time_known", 1) == 1)
                }
            }
            return records
        }
        fun readBackup(file: File): List<Record> {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                require(db.version == 1) { "不支援的備份版本" }
                db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "資料庫已損壞" } }
                db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "附件關聯不完整" } }
                listOf("records", "attachments").forEach { table ->
                    db.rawQuery("SELECT type FROM sqlite_master WHERE name = ?", arrayOf(table)).use {
                        require(it.moveToFirst() && it.getString(0) == "table") { "無效的資料表" }
                    }
                }
                val records = read(db)
                validate(records)
                val count = db.rawQuery("SELECT count(*) FROM attachments", null).use { it.moveToFirst(); it.getLong(0) }
                require(count == records.sumOf { it.attachments.size }.toLong()) { "有遺失的附件關聯" }
                return records
            }
        }
    }
}
