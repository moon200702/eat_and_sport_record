package com.moon.eatandsport

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

class PlanEditorActivity : ComponentActivity() {
    private lateinit var model: EditorModel
    private lateinit var name: EditText
    private lateinit var description: EditText
    private var planId = UUID.randomUUID().toString()
    private var original: WorkoutPlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[EditorModel::class.java]
        try {
            original = intent.getStringExtra("id")?.let { id -> RecordStore(this).use { it.findPlan(id) } ?: error("找不到此份菜單") }
        } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); finish(); return }
        planId = savedInstanceState?.getString("planId") ?: original?.id ?: planId
        val form = ui.column().apply {
            addView(ui.gap(20)); addView(ui.text(if (original == null) "新增運動菜單" else "編輯運動菜單", 28, bold = true))
            addView(ui.text("先為菜單命名，儲存後即可加入多個動作。", 14, ui.muted))
        }
        name = ui.input(form, "菜單名稱", "例如：全身訓練、腿部日、居家伸展", R.id.plan_name).apply {
            filters = arrayOf(InputFilter.LengthFilter(80)); setText(savedInstanceState?.getString("name") ?: original?.name.orEmpty())
        }
        description = ui.input(form, "菜單說明（選填）", "訓練目標、注意事項、每週安排…", R.id.plan_description, multiline = true).apply {
            filters = arrayOf(InputFilter.LengthFilter(20_000)); setText(savedInstanceState?.getString("description") ?: original?.description.orEmpty())
        }
        val footer = ui.row()
        footer.addView(ui.button("取消", id = R.id.plan_cancel) { discard() }, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        val save = ui.button("儲存菜單", true, R.id.plan_save) {
            val title = name.text.toString().trim()
            name.error = if (title.isBlank()) "請輸入菜單名稱" else null
            if (name.error == null) model.savePlan(WorkoutPlan(planId, title, description.text.toString()))
        }
        footer.addView(save, LinearLayout.LayoutParams(0, ui.dp(56), 2f))
        setContentView(ui.page(form, footer))
        model.busy.observe(this) { busy -> name.isEnabled = !busy; description.isEnabled = !busy; save.isEnabled = !busy; save.text = if (busy) "正在儲存…" else "儲存菜單" }
        model.saved.observe(this) { saved -> if (saved) { setResult(RESULT_OK, Intent().putExtra("planId", planId)); finish() } }
        model.error.observe(this) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { discard() } })
    }
    private fun discard() {
        if (model.busy.value == true) return
        if (name.text.toString() == original?.name.orEmpty() && description.text.toString() == original?.description.orEmpty()) finish()
        else AlertDialog.Builder(this).setTitle("捨棄尚未儲存的菜單修改？").setNegativeButton("繼續編輯", null)
            .setPositiveButton("捨棄修改") { _, _ -> finish() }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("planId", planId); outState.putString("name", name.text.toString()); outState.putString("description", description.text.toString())
        super.onSaveInstanceState(outState)
    }
}
