package com.moon.eatandsport

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class EditorActivity : ComponentActivity() {
    private lateinit var model: EditorModel
    private lateinit var ui: Ui
    private lateinit var name: EditText
    private lateinit var note: EditText
    private lateinit var calories: EditText
    private lateinit var minutes: EditText
    private lateinit var type: Spinner
    private lateinit var mediaList: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var dateButton: Button
    private lateinit var timeButton: Button
    private var date = LocalDate.now()
    private var time = LocalTime.now().withSecond(0).withNano(0)
    private var recordId = UUID.randomUUID().toString()
    private var original: Record? = null
    private var planId: String? = null
    private var stored = mutableListOf<Attachment>()
    private val pending = mutableListOf<String>()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (model.busy.value == true) return@registerForActivityResult
        val available = 10 - stored.size - pending.size
        if (uris.distinct().count { it.toString() !in pending } > available) toast("每筆筆記最多 10 個附件")
        uris.distinct().filter { it.toString() !in pending }.take(available).forEach { uri ->
            try {
                require(contentResolver.getType(uri)?.lowercase() in Attachment.extensions) { "不支援的圖片或影片格式" }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                pending += uri.toString()
            } catch (e: Exception) { toast("無法加入附件：${e.message}") }
        }
        renderMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[EditorModel::class.java]
        val editId = intent.getStringExtra("id")
        try {
            original = editId?.let { id -> RecordStore(this).use { it.find(id) } ?: error("找不到此筆紀錄") }
            planId = original?.planId ?: intent.getStringExtra("planId")
            planId?.let { id -> require(RecordStore(this).use { it.findPlan(id) } != null) { "找不到此份菜單" } }
        } catch (e: Exception) { toast(e.message.orEmpty()); finish(); return }
        recordId = savedInstanceState?.getString("recordId") ?: original?.id ?: recordId
        date = LocalDate.parse(savedInstanceState?.getString("date") ?: original?.date?.toString() ?: intent.getStringExtra("date") ?: date.toString())
        time = LocalTime.parse(savedInstanceState?.getString("time") ?: original?.time?.toString() ?: time.toString())
        stored = savedInstanceState?.getString("attachments")?.let(::decodeAttachments)?.toMutableList() ?: original?.attachments?.toMutableList() ?: mutableListOf()
        pending += savedInstanceState?.getStringArrayList("pending").orEmpty()
        val form = ui.column()
        form.addView(ui.gap(20))
        form.addView(ui.text(if (planId != null) { if (original == null) "新增菜單動作" else "編輯菜單動作" }
            else if (original == null) "新增筆記" else "編輯筆記", 28, bold = true))
        form.addView(ui.text(if (planId != null) "設定動作、組數與休息方式，可加入示範圖片及影片。" else "記下時間、內容，留住每次日常。", 14, ui.muted))
        if (planId == null && original?.timeKnown == false) form.addView(ui.text("這筆舊版紀錄未記錄時間，儲存前請確認時刻。", 14, ui.muted))
        form.addView(ui.gap())
        dateButton = ui.button(date.toString(), id = R.id.editor_date) {
            if (!busy()) DatePickerDialog(this, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); updateDateTime() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        timeButton = ui.button(time.toString(), id = R.id.editor_time) {
            if (!busy()) TimePickerDialog(this, { _, h, m -> time = LocalTime.of(h, m); updateDateTime() }, time.hour, time.minute, true).show()
        }
        form.addView(ui.row().apply {
            if (planId != null) visibility = View.GONE
            addView(dateButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timeButton, LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (planId == null) form.addView(ui.text("類型", 14, ui.muted))
        type = Spinner(this).apply {
            id = R.id.record_type; contentDescription = "筆記類型"
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("飲食", "運動"))
            val initial = if (planId != null) 1 else savedInstanceState?.getInt("type") ?: if ((original?.type?.name ?: intent.getStringExtra("type")) == "EXERCISE") 1 else 0
            setSelection(initial)
            if (planId != null) visibility = View.GONE
        }
        form.addView(type, LinearLayout.LayoutParams(-1, ui.dp(48)))
        name = ui.input(form, if (planId != null) "動作名稱" else "名稱／動作",
            if (planId != null) "例如：深蹲、伏地挺身、伸展" else "例如：午餐、深蹲、慢跑", R.id.record_name).apply {
            filters = arrayOf(InputFilter.LengthFilter(80)); setText(savedInstanceState?.getString("name") ?: original?.name.orEmpty())
        }
        note = ui.input(form, if (planId != null) "動作內容" else "筆記內容",
            if (planId != null) "例如：深蹲 3 組 × 12 下，組間休息 60 秒" else "飲食內容、運動組數、感受…", R.id.record_note, multiline = true).apply {
            filters = arrayOf(InputFilter.LengthFilter(20_000)); setText(savedInstanceState?.getString("note") ?: original?.note.orEmpty())
        }
        calories = ui.input(form, "熱量 kcal（選填）", "未填則不計入熱量", R.id.record_calories, number = true).apply {
            setText(savedInstanceState?.getString("calories") ?: original?.calories?.takeIf { it != 0 }?.toString().orEmpty())
        }
        val duration = ui.column()
        minutes = ui.input(duration, "運動分鐘數（選填）", "例如：30", R.id.record_minutes, number = true).apply {
            setText(savedInstanceState?.getString("minutes") ?: original?.minutes?.takeIf { it != 0 }?.toString().orEmpty())
        }
        form.addView(duration)
        duration.visibility = if (type.selectedItemPosition == 1) View.VISIBLE else View.GONE
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { duration.visibility = if (position == 1) View.VISIBLE else View.GONE }
        }
        form.addView(ui.gap(20))
        form.addView(ui.text("圖片與影片", 20, bold = true))
        form.addView(ui.text("最多 10 個附件，每個上限 500 MB；儲存後會複製至 App。", 13, ui.muted))
        form.addView(ui.button("＋ 插入圖片或影片", id = R.id.add_media) { if (!busy()) pickMedia.launch(arrayOf("image/*", "video/*")) })
        mediaList = ui.column().apply { id = R.id.media_list }
        form.addView(mediaList)
        form.addView(ui.gap(24))
        val footer = ui.row()
        footer.addView(ui.button("取消", id = R.id.editor_cancel) { discard() }, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        saveButton = ui.button(if (planId != null) "儲存動作" else "儲存筆記", true, R.id.editor_save) { save() }
        footer.addView(saveButton, LinearLayout.LayoutParams(0, ui.dp(56), 2f))
        setContentView(ui.page(form, footer))
        renderMedia()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { discard() } })
        model.busy.observe(this) { busy ->
            saveButton.isEnabled = !busy
            saveButton.text = if (busy) "正在儲存附件…" else if (planId != null) "儲存動作" else "儲存筆記"
            // The retained ViewModel continues copying on rotation; freeze draft controls while saving.
            fun enable(view: View) {
                view.isEnabled = !busy
                if (view is android.view.ViewGroup) (0 until view.childCount).forEach { enable(view.getChildAt(it)) }
            }
            enable(form)
        }
        model.saved.observe(this) { saved -> if (saved) {
            setResult(RESULT_OK, Intent().putExtra("date", date.toString()).putExtra("type", if (type.selectedItemPosition == 1) "EXERCISE" else "FOOD").putExtra("planId", planId))
            finish()
        } }
        model.error.observe(this) { toast(it) }
    }

    private fun busy() = model.busy.value == true
    private fun updateDateTime() { dateButton.text = date.toString(); timeButton.text = time.format(DateTimeFormatter.ofPattern("HH:mm")) }
    private fun save() {
        if (busy()) return
        val title = name.text.toString().trim()
        val kcal = calories.text.toString().trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() }
        val duration = if (type.selectedItemPosition == 1) minutes.text.toString().trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() } else 0
        name.error = if (title.isBlank()) "請輸入名稱或動作" else null
        calories.error = if (kcal == null || kcal !in 0..50_000) "請輸入 0–50,000 之間的整數" else null
        minutes.error = if (duration == null || duration !in 0..1_440) "請輸入 0–1,440 之間的整數" else null
        if (name.error != null || calories.error != null || minutes.error != null) return
        model.save(Record(recordId, date, if (type.selectedItemPosition == 1) RecordType.EXERCISE else RecordType.FOOD,
            title, requireNotNull(kcal), requireNotNull(duration), time, note.text.toString(), stored.toList(),
            timeKnown = planId == null, planId = planId, planPosition = original?.planPosition ?: 0), pending.map { it.toUri() })
    }
    private fun renderMedia() {
        mediaList.removeAllViews()
        stored.forEach { a -> MediaViews.add(this, mediaList, a) { if (!busy()) { stored.remove(a); renderMedia() } } }
        pending.toList().forEach { value ->
            val uri = value.toUri()
            var display = uri.lastPathSegment.orEmpty().takeLast(40)
            runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) display = c.getString(0).orEmpty().take(255)
            } }
            MediaViews.addUri(this, mediaList, uri, contentResolver.getType(uri).orEmpty(), "待儲存附件 · $display") {
                if (!busy()) { pending.remove(value); release(value); renderMedia() }
            }
        }
    }
    private fun discard() {
        if (busy()) { toast("正在儲存，請稍候"); return }
        val changed = name.text.toString() != original?.name.orEmpty() || note.text.toString() != original?.note.orEmpty() ||
            pending.isNotEmpty() || stored != original?.attachments.orEmpty() ||
            calories.text.toString() != original?.calories?.takeIf { it != 0 }?.toString().orEmpty() ||
            minutes.text.toString() != original?.minutes?.takeIf { it != 0 }?.toString().orEmpty() ||
            (original != null && (date != original?.date || time != original?.time || type.selectedItemPosition != if (original?.type == RecordType.EXERCISE) 1 else 0))
        fun close() { pending.forEach(::release); finish() }
        if (!changed) close() else AlertDialog.Builder(this).setTitle("捨棄尚未儲存的修改？")
            .setMessage("現有紀錄不會改變。").setNegativeButton("繼續編輯", null).setPositiveButton("捨棄修改") { _, _ -> close() }.show()
    }
    private fun release(value: String) { runCatching { contentResolver.releasePersistableUriPermission(value.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("recordId", recordId); outState.putString("date", date.toString()); outState.putString("time", time.toString())
        outState.putString("name", name.text.toString()); outState.putString("note", note.text.toString())
        outState.putString("calories", calories.text.toString()); outState.putString("minutes", minutes.text.toString())
        outState.putInt("type", type.selectedItemPosition); outState.putStringArrayList("pending", ArrayList(pending))
        outState.putString("attachments", JSONArray().apply { stored.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("fileName", a.fileName); put("mime", a.mime); put("name", a.displayName); put("size", a.size)
        }) } }.toString())
        super.onSaveInstanceState(outState)
    }
    private fun decodeAttachments(value: String): List<Attachment> {
        val array = JSONArray(value)
        return List(array.length()) { i -> array.getJSONObject(i).let {
            Attachment(it.getString("id"), it.getString("fileName"), it.getString("mime"), it.getString("name"), it.getLong("size"))
        } }
    }
}
