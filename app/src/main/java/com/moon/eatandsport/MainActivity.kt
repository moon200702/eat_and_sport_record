package com.moon.eatandsport

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var model: MainModel
    private lateinit var ui: Ui
    private var page = "home"
    private var date = LocalDate.now()
    private var filter: RecordType? = null
    private var query = ""
    private var searchType: RecordType? = null
    private var searchDate: LocalDate? = null
    private var searchTime: LocalTime? = null
    private var resultsView: LinearLayout? = null
    private var workDialog: AlertDialog? = null
    private var shownImport: PreparedBackup? = null
    private var lastMessage = ""
    private var selectedPlanId: String? = null
    private val records get() = model.records.value.orEmpty()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedPlanId = result.data?.getStringExtra("planId")
            if (selectedPlanId == null) {
                result.data?.getStringExtra("date")?.let { date = LocalDate.parse(it) }
                result.data?.getStringExtra("type")?.let { filter = RecordType.valueOf(it) }
            }
            page = if (selectedPlanId == null) "records" else "plans"
            model.refresh()
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { model.export(it) } }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.prepare(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[MainModel::class.java]
        savedInstanceState?.let { state ->
            page = state.getString("page", "home")
            selectedPlanId = state.getString("selectedPlanId")
            date = LocalDate.parse(state.getString("date", date.toString()))
            filter = state.getString("filter")?.let(RecordType::valueOf)
            query = state.getString("query", "")
            searchType = state.getString("searchType")?.let(RecordType::valueOf)
            searchDate = state.getString("searchDate")?.let(LocalDate::parse)
            searchTime = state.getString("searchTime")?.let(LocalTime::parse)
        }
        render()
        model.records.observe(this) { render() }
        model.snapshot.observe(this) { if (page == "plans" || page == "data") render() }
        model.results.observe(this) { if (page == "search") renderResults(it) }
        model.status.observe(this) { state ->
            if (state.busy) {
                if (workDialog?.isShowing != true) workDialog = AlertDialog.Builder(this)
                    .setMessage(state.message).setCancelable(false).show()
                else workDialog?.setMessage(state.message)
            } else {
                workDialog?.dismiss(); workDialog = null
                if (state.prepared != null && shownImport !== state.prepared) {
                    shownImport = state.prepared
                    confirmImport(state.prepared)
                } else if (state.message != null) {
                    lastMessage = state.message
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    model.clearMessage()
                    if (page == "data") render()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == "plans" && selectedPlanId != null) { selectedPlanId = null; render() }
                else if (page != "home") navigate("home") else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.refresh() }
    override fun onDestroy() { workDialog?.dismiss(); super.onDestroy() }

    private fun navigate(destination: String) { page = destination; render() }
    private fun render() {
        val content = ui.column().apply { addView(ui.gap(20)) }
        resultsView = null
        when (page) {
            "records" -> recordsPage(content)
            "search" -> searchPage(content)
            "stats" -> statsPage(content)
            "data" -> dataPage(content)
            "plans" -> plansPage(content)
            else -> homePage(content)
        }
        content.addView(ui.gap(24))
        val footer = ui.column()
        if (page == "records") footer.addView(ui.button("＋ 新增筆記", true, R.id.add_record) { edit() }, LinearLayout.LayoutParams(-1, ui.dp(56)))
        if (page == "plans" && selectedPlanId != null && model.plans.any { it.id == selectedPlanId })
            footer.addView(ui.button("＋ 新增菜單動作", true, R.id.add_plan_item) { editPlanItem() }, LinearLayout.LayoutParams(-1, ui.dp(56)))
        footer.addView(ui.row().apply {
            listOf(Triple("home", "首頁", R.id.nav_home), Triple("search", "找筆記", R.id.nav_search),
                Triple("plans", "菜單", R.id.nav_plans),
                Triple("stats", "統計", R.id.nav_stats), Triple("data", "資料", R.id.nav_data)).forEach { (key, title, id) ->
                addView(ui.button(title, page == key, id) { navigate(key) }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
            }
        })
        setContentView(ui.page(content, footer))
    }
    private fun heading(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(ui.text(title, 28, bold = true)); parent.addView(ui.gap(4))
        parent.addView(ui.text(subtitle, 14, ui.muted)); parent.addView(ui.gap(20))
    }
    private fun homePage(parent: LinearLayout) {
        heading(parent, "日常食動", "好好吃飯，好好動一動。")
        parent.addView(ui.card(ui.green).apply {
            addView(ui.text("今天的日常", 14, Color.WHITE))
            addView(ui.text(LocalDate.now().toString(), 25, Color.WHITE, true))
            addView(ui.gap())
            addView(ui.button("查看統計  ›") { date = LocalDate.now(); navigate("stats") })
        })
        parent.addView(ui.gap())
        summary(parent, LocalDate.now())
        parent.addView(ui.gap(24))
        parent.addView(ui.button("運動紀錄  ›", true, R.id.home_exercise) {
            date = LocalDate.now(); filter = RecordType.EXERCISE; navigate("records")
        }, LinearLayout.LayoutParams(-1, ui.dp(100)))
        parent.addView(ui.text("記下動作、組數與身體的感受", 14, ui.muted))
        parent.addView(ui.gap(18))
        parent.addView(ui.button("飲食紀錄  ›", false, R.id.home_food) {
            date = LocalDate.now(); filter = RecordType.FOOD; navigate("records")
        }, LinearLayout.LayoutParams(-1, ui.dp(100)))
        parent.addView(ui.text("用文字、圖片與影片留下每一餐", 14, ui.muted))
        parent.addView(ui.gap(18))
        parent.addView(ui.button("運動菜單  ›", false, R.id.home_plans) { selectedPlanId = null; navigate("plans") }, LinearLayout.LayoutParams(-1, ui.dp(80)))
        parent.addView(ui.text("設計不同訓練菜單，保存動作內容與示範影音", 14, ui.muted))
        parent.addView(ui.gap(24))
        parent.addView(ui.text("資料保存在手機，可至「資料」完整匯入或匯出。", 13, ui.muted))
    }
    private fun dateHeader(parent: LinearLayout) {
        parent.addView(ui.row().apply {
            addView(ui.button("‹", id = R.id.previous_day) { date = date.minusDays(1); render() }.apply { contentDescription = "前一天" }, LinearLayout.LayoutParams(ui.dp(48), -2))
            addView(ui.button(date.toString(), id = R.id.selected_date) { pickDate { date = it; render() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("›", id = R.id.next_day) { date = date.plusDays(1); render() }.apply { contentDescription = "後一天" }, LinearLayout.LayoutParams(ui.dp(48), -2))
        })
    }
    private fun recordsPage(parent: LinearLayout) {
        heading(parent, "日常筆記", "依日期查看，按時間留下生活的內容。")
        dateHeader(parent)
        val start = date.minusDays((date.dayOfWeek.value % 7).toLong())
        val week = ui.row().apply { id = R.id.week_strip }
        val weekdays = listOf("日", "一", "二", "三", "四", "五", "六")
        repeat(7) { offset ->
            val day = start.plusDays(offset.toLong())
            week.addView(ui.button("${weekdays[offset]}\n${day.dayOfMonth}", day == date) { date = day; render() }.apply {
                contentDescription = day.toString(); isSelected = day == date
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(64)))
        }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(week) })
        parent.addView(ui.row().apply {
            listOf(Triple(null, "全部", R.id.filter_all), Triple(RecordType.FOOD, "飲食", R.id.filter_food),
                Triple(RecordType.EXERCISE, "運動", R.id.filter_exercise)).forEach { (value, title, id) ->
                addView(ui.button(title, filter == value, id) { filter = value; render() }.apply { isSelected = filter == value }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
            }
        })
        parent.addView(ui.gap())
        summary(parent, date)
        val visible = records.filter { it.date == date && (filter == null || it.type == filter) }.sortedByDescending { it.time }
        parent.addView(ui.gap(20))
        parent.addView(ui.text("共 ${visible.size} 筆紀錄", 14, ui.muted))
        parent.addView(ui.column().apply {
            id = R.id.records_list
            if (visible.isEmpty()) { addView(ui.gap(24)); addView(ui.text("這一天還沒有紀錄", 20, bold = true)); addView(ui.text("按下方按鈕，新增文字、圖片或影片。", 14, ui.muted)) }
            else visible.take(200).forEach { addView(ui.gap()); addView(recordCard(it)) }
            if (visible.size > 200) addView(ui.text("顯示前 200 筆，請使用搜尋縮小範圍。", 14, ui.muted))
        })
    }
    private fun summary(parent: LinearLayout, day: LocalDate) {
        val data = summarize(records, day)
        parent.addView(ui.row().apply {
            listOf(Triple("飲食攝取\nkcal", data.intake, R.id.summary_intake), Triple("運動消耗\nkcal", data.burned, R.id.summary_burned),
                Triple("運動時間\n分鐘", data.minutes, R.id.summary_minutes)).forEach { (title, value, id) ->
                addView(ui.card().apply {
                    addView(ui.text(value.toString(), 23, ui.green, true).apply { this.id = id })
                    addView(ui.text(title, 12, ui.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
            }
        })
    }
    private fun recordCard(record: Record, showDate: Boolean = false, step: Int? = null): View = ui.card().apply {
        val caption = if (step != null) "動作 $step" else (if (showDate) "${record.date} · " else "") +
            (if (record.timeKnown) record.time.format(timeFormat) else "未記錄時間") + " · " + if (record.type == RecordType.FOOD) "飲食" else "運動"
        addView(ui.text(caption, 13, ui.green, true))
        addView(ui.gap(5)); addView(ui.text(record.name, 21, bold = true))
        if (record.note.isNotEmpty()) { addView(ui.gap(10)); addView(ui.text(record.note)) }
        addView(ui.gap(10))
        addView(ui.text(if (record.type == RecordType.FOOD) "${record.calories} kcal" else "${record.calories} kcal · ${record.minutes} 分鐘", 13, ui.muted))
        var previewShown = false
        record.attachments.forEach { a ->
            addView(ui.gap())
            val preview = !previewShown && a.mime.startsWith("image/")
            MediaViews.add(this@MainActivity, this, a, showPreview = preview)
            if (preview) previewShown = true
        }
        addView(ui.row().apply {
            addView(ui.button(if (step == null) "編輯筆記" else "編輯動作") { edit(record) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("刪除") {
                AlertDialog.Builder(this@MainActivity).setTitle(if (step == null) "刪除這筆紀錄？" else "刪除這個菜單動作？")
                    .setMessage("「${record.name}」及其附件刪除後無法復原。")
                    .setNegativeButton("取消", null).setPositiveButton("刪除") { _, _ -> model.delete(record.id) }.show()
            }.apply { contentDescription = "刪除 ${record.name}" })
        })
        if (step != null) {
            val count = model.allRecords.count { it.planId == record.planId }
            addView(ui.row().apply {
                addView(ui.button("↑ 上移") { model.movePlanItem(record.id, -1) }.apply {
                    contentDescription = "上移 ${record.name}"; isEnabled = step > 1
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button("↓ 下移") { model.movePlanItem(record.id, 1) }.apply {
                    contentDescription = "下移 ${record.name}"; isEnabled = step < count
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }
    private fun plansPage(parent: LinearLayout) {
        if (selectedPlanId != null && model.snapshot.value == null) {
            heading(parent, "運動菜單", "正在載入菜單…")
            return
        }
        val plan = model.plans.find { it.id == selectedPlanId }
        if (plan == null) {
            selectedPlanId = null
            heading(parent, "運動菜單", "設計多份菜單，依照自己的安排練習。")
            parent.addView(ui.button("＋ 新增運動菜單", true, R.id.new_plan) { editPlan() })
            parent.addView(ui.column().apply {
                id = R.id.plan_list
                if (model.plans.isEmpty()) {
                    addView(ui.gap(24)); addView(ui.text("建立你的第一份菜單", 21, bold = true))
                    addView(ui.text("例如全身訓練、腿部日、居家伸展；每份可加入多個動作與示範影音。", 14, ui.muted))
                }
                model.plans.forEach { item ->
                    addView(ui.gap())
                    addView(ui.card().apply {
                        addView(ui.text(item.name, 22, bold = true))
                        if (item.description.isNotEmpty()) addView(ui.text(item.description, 14, ui.muted))
                        addView(ui.text("${model.allRecords.count { it.planId == item.id }} 個動作", 14, ui.green))
                        addView(ui.button("開啟菜單") { selectedPlanId = item.id; render() }.apply { contentDescription = "開啟菜單 ${item.name}" })
                    })
                }
            })
        } else {
            parent.addView(ui.button("‹ 所有菜單", id = R.id.back_to_plans) { selectedPlanId = null; render() })
            heading(parent, plan.name, plan.description.ifBlank { "依序查看動作，隨時調整訓練內容。" })
            parent.addView(ui.row().apply {
                addView(ui.button("編輯菜單", id = R.id.edit_plan) { editPlan(plan) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button("刪除菜單", id = R.id.delete_plan) {
                    AlertDialog.Builder(this@MainActivity).setTitle("刪除「${plan.name}」？")
                        .setMessage("這份菜單的所有動作與附件也會刪除，無法復原。")
                        .setNegativeButton("取消", null).setPositiveButton("刪除菜單") { _, _ -> model.deletePlan(plan.id) }.show()
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            val items = model.snapshot.value?.items(plan.id).orEmpty()
            parent.addView(ui.text("共 ${items.size} 個動作", 14, ui.muted))
            parent.addView(ui.column().apply {
                id = R.id.plan_items
                if (items.isEmpty()) { addView(ui.gap(24)); addView(ui.text("還沒有動作", 21, bold = true)); addView(ui.text("按下方按鈕，加入動作、組數、休息方式及圖片或影片。", 14, ui.muted)) }
                items.forEachIndexed { index, record -> addView(ui.gap()); addView(recordCard(record, step = index + 1)) }
            })
        }
    }
    private fun editPlan(plan: WorkoutPlan? = null) {
        if (!ready()) return
        editor.launch(Intent(this, PlanEditorActivity::class.java).apply { plan?.let { putExtra("id", it.id) } })
    }
    private fun editPlanItem() {
        if (!ready()) return
        editor.launch(Intent(this, EditorActivity::class.java).putExtra("planId", selectedPlanId).putExtra("type", "EXERCISE"))
    }
    private fun searchPage(parent: LinearLayout) {
        heading(parent, "找筆記", "搜尋動作、餐點、筆記內容或附件名稱。")
        val input = ui.input(parent, "關鍵字", "例如：深蹲、午餐、今天的感受", R.id.search_query).apply { setText(query) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); search() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        parent.addView(Spinner(this).apply {
            id = R.id.search_type; contentDescription = "搜尋類型"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("全部類型", "飲食", "運動"))
            setSelection(when (searchType) { RecordType.FOOD -> 1; RecordType.EXERCISE -> 2; null -> 0 })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    searchType = when (position) { 1 -> RecordType.FOOD; 2 -> RecordType.EXERCISE; else -> null }; search()
                }
            }
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        parent.addView(ui.row().apply {
            addView(ui.button(searchDate?.toString() ?: "不限日期", id = R.id.search_date) {
                pickDate(searchDate ?: LocalDate.now()) { searchDate = it; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button(searchTime?.format(timeFormat) ?: "不限時間", id = R.id.search_time) {
                val initial = searchTime ?: LocalTime.NOON
                TimePickerDialog(this@MainActivity, { _, h, m -> searchTime = LocalTime.of(h, m); render() }, initial.hour, initial.minute, true).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        parent.addView(ui.button("清除搜尋條件") { query = ""; searchType = null; searchDate = null; searchTime = null; render() })
        parent.addView(ui.gap())
        resultsView = ui.column().apply { id = R.id.search_results; addView(ui.text("正在搜尋…", 14, ui.muted)) }
        parent.addView(resultsView)
        search()
    }
    private fun search() { model.search(query, searchType, searchDate, searchTime) }
    private fun renderResults(results: List<Record>) {
        val container = resultsView ?: return
        container.removeAllViews()
        container.addView(ui.text("找到 ${results.size} 筆筆記", 16, bold = true))
        results.take(200).forEach { container.addView(ui.gap()); container.addView(recordCard(it, true)) }
        if (results.isEmpty()) container.addView(ui.text("沒有符合條件的筆記，試試其他關鍵字或時間。", 14, ui.muted))
        if (results.size > 200) container.addView(ui.text("顯示前 200 筆，請縮小搜尋範圍。", 14, ui.muted))
    }
    private fun statsPage(parent: LinearLayout) {
        heading(parent, "統計", "依紀錄加總；未填熱量或時間的筆記不計入數值。")
        dateHeader(parent); summary(parent, date)
        parent.addView(ui.gap(24)); parent.addView(ui.text("一週紀錄", 21, bold = true))
        val start = date.minusDays((date.dayOfWeek.value % 7).toLong())
        val weekRecords = records.filter { it.date >= start && it.date <= start.plusDays(6) }
        parent.addView(ui.text("${start} — ${start.plusDays(6)} · 共 ${weekRecords.size} 筆", 13, ui.muted))
        repeat(7) { offset ->
            val day = start.plusDays(offset.toLong()); val data = summarize(records, day)
            parent.addView(ui.gap())
            parent.addView(ui.card().apply {
                addView(ui.text("${day.monthValue}/${day.dayOfMonth}", 16, ui.green, true))
                addView(ui.text("飲食 ${data.intake} kcal · 運動 ${data.burned} kcal", 14))
                addView(ui.text("運動 ${data.minutes} 分鐘 · ${records.count { it.date == day }} 筆筆記", 13, ui.muted))
            })
        }
    }
    private fun dataPage(parent: LinearLayout) {
        heading(parent, "資料與備份", "你的紀錄，隨時帶著走。")
        parent.addView(ui.card().apply {
            addView(ui.text("SQLite 本機資料庫", 21, bold = true))
            addView(ui.text("目前 ${records.size} 筆筆記、${model.plans.size} 份運動菜單、${model.allRecords.sumOf { it.attachments.size }} 個附件", 15, ui.green))
            addView(ui.gap()); addView(ui.text("完整備份包含 records.sqlite 資料庫，以及實際的圖片與影片檔案。匯出後請自行保管；解除安裝會清除 App 內的資料。", 14, ui.muted))
        })
        parent.addView(ui.gap(24))
        parent.addView(ui.button("匯出完整備份（ZIP）", true, R.id.export_backup) {
            if (ready()) export.launch("日常食動-${LocalDate.now()}.zip")
        }, LinearLayout.LayoutParams(-1, ui.dp(60)))
        parent.addView(ui.gap())
        parent.addView(ui.button("匯入備份", false, R.id.import_backup) {
            if (ready()) importBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }, LinearLayout.LayoutParams(-1, ui.dp(60)))
        parent.addView(ui.gap(18))
        parent.addView(ui.text("匯入前會檢查資料庫與附件。可以合併新增紀錄，或再次確認後取代全部。合併時保留既有相同識別碼的紀錄。", 14, ui.muted))
        parent.addView(ui.gap())
        parent.addView(ui.text("備份上限 4 GB、資料庫上限 128 MB。附件每個上限 500 MB。", 13, ui.muted))
        parent.addView(ui.text(lastMessage, 14, ui.green).apply { id = R.id.work_status })
    }
    private fun confirmImport(prepared: PreparedBackup) {
        val duplicate = prepared.records.count { incoming -> records.any { it.id == incoming.id } }
        AlertDialog.Builder(this).setTitle("匯入備份")
            .setMessage("包含 ${prepared.records.size} 筆筆記、${prepared.plans.size} 份運動菜單、${prepared.snapshot.records.sumOf { it.attachments.size }} 個附件。\n\n合併會保留既有的 $duplicate 筆重複紀錄；相同識別碼的菜單會完整保留手機上的版本。")
            .setNegativeButton("取消") { _, _ -> model.cancelImport() }
            .setPositiveButton("合併新增") { _, _ -> model.applyImport(ImportMode.MERGE) }
            .setNeutralButton("取代全部…") { _, _ ->
                AlertDialog.Builder(this).setTitle("取代手機內全部紀錄？")
                    .setMessage("現有筆記、運動菜單與附件將被此備份取代，無法復原。建議先匯出備份。")
                    .setNegativeButton("取消") { _, _ -> model.cancelImport() }
                    .setPositiveButton("確認取代") { _, _ -> model.applyImport(ImportMode.REPLACE) }
                    .setOnCancelListener { model.cancelImport() }.show()
            }.setOnCancelListener { model.cancelImport() }.show()
    }
    private fun ready(): Boolean {
        if (model.records.value == null || model.status.value?.busy == true) {
            Toast.makeText(this, "資料尚未載入或操作進行中，請稍候。", Toast.LENGTH_SHORT).show(); return false
        }
        return true
    }
    private fun edit(record: Record? = null) {
        if (!ready()) return
        editor.launch(Intent(this, EditorActivity::class.java).apply {
            if (record != null) putExtra("id", record.id)
            else { putExtra("date", date.toString()); putExtra("type", (filter ?: RecordType.FOOD).name) }
        })
    }
    private fun pickDate(initial: LocalDate = date, result: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, y, m, d -> result(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("date", date.toString()); outState.putString("filter", filter?.name)
        outState.putString("selectedPlanId", selectedPlanId)
        outState.putString("query", query); outState.putString("searchType", searchType?.name)
        outState.putString("searchDate", searchDate?.toString()); outState.putString("searchTime", searchTime?.toString())
        super.onSaveInstanceState(outState)
    }
}
