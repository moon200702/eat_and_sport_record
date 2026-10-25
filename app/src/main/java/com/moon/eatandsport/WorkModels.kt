package com.moon.eatandsport

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class WorkStatus(val busy: Boolean = false, val message: String? = null, val prepared: PreparedBackup? = null)

class MainModel(application: Application) : AndroidViewModel(application) {
    val repository = NoteRepository(application)
    private val backups = BackupManager(application, repository)
    private val worker = Executors.newSingleThreadExecutor()
    private val searchVersion = AtomicInteger()
    @Volatile private var cleared = false
    val records = MutableLiveData<List<Record>>()
    val results = MutableLiveData<List<Record>>()
    val status = MutableLiveData(WorkStatus())

    fun refresh() { worker.execute { runCatching { repository.store.load() }.onSuccess { records.postValue(it) }
        .onFailure { status.postValue(WorkStatus(message = "讀取失敗，原資料保持不變：${it.message}")) } } }

    fun search(query: String, type: RecordType?, date: LocalDate?, time: LocalTime?) {
        val version = searchVersion.incrementAndGet()
        worker.execute {
            if (version != searchVersion.get()) return@execute
            runCatching { repository.store.search(query, type, date, time) }
                .onSuccess { if (version == searchVersion.get()) results.postValue(it) }
                .onFailure { status.postValue(WorkStatus(message = "搜尋失敗：${it.message}")) }
        }
    }

    fun delete(id: String) = work("正在刪除…") { repository.delete(id); "已刪除紀錄" }
    fun export(uri: Uri) = work("正在匯出 SQLite 與附件，請稍候…") {
        requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { backups.export(it) }
        "完整備份已匯出"
    }
    fun prepare(uri: Uri) {
        status.value = WorkStatus(busy = true, message = "正在檢查備份…")
        worker.execute {
            try {
                val prepared = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { backups.prepare(it) }
                if (cleared) prepared.close() else status.postValue(WorkStatus(prepared = prepared))
            } catch (e: Exception) { status.postValue(WorkStatus(message = "匯入失敗，原資料保持不變：${e.message}")) }
        }
    }
    fun applyImport(mode: ImportMode) {
        val prepared = status.value?.prepared ?: return
        work("正在匯入資料與附件…") {
            try {
                val result = backups.apply(prepared, mode)
                "已匯入 ${result.added} 筆，保留既有重複紀錄 ${result.skipped} 筆"
            } finally { prepared.close() }
        }
    }
    fun cancelImport() { status.value?.prepared?.close(); status.value = WorkStatus() }
    fun clearMessage() { if (status.value?.busy == false && status.value?.prepared == null) status.value = WorkStatus() }

    private fun work(message: String, action: () -> String) {
        status.value = WorkStatus(busy = true, message = message)
        worker.execute {
            try { val result = action(); records.postValue(repository.store.load()); status.postValue(WorkStatus(message = result)) }
            catch (e: Exception) { status.postValue(WorkStatus(message = "操作失敗：${e.message}")) }
        }
    }
    override fun onCleared() {
        cleared = true
        val prepared = status.value?.prepared
        worker.execute { repository.close(); prepared?.close() }
        worker.shutdown()
    }
}

class EditorModel(application: Application) : AndroidViewModel(application) {
    private val worker = Executors.newSingleThreadExecutor()
    val busy = MutableLiveData(false)
    val saved = MutableLiveData(false)
    val error = MutableLiveData<String>()
    fun save(record: Record, pendingUris: List<Uri>) {
        if (busy.value == true) return
        busy.value = true
        worker.execute {
            try {
                NoteRepository(getApplication<Application>()).use { it.save(record, pendingUris) }
                pendingUris.forEach { uri -> runCatching {
                    getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } }
                saved.postValue(true)
            } catch (e: Exception) { error.postValue("儲存失敗，請確認附件仍可讀取及手機空間：${e.message}"); busy.postValue(false) }
        }
    }
    override fun onCleared() { worker.shutdown() }
}
