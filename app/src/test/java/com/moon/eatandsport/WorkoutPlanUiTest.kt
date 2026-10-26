package com.moon.eatandsport

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.LocalDate
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class WorkoutPlanUiTest {
    @Before fun reset() = TestSupport.reset()
    private fun start() = Robolectric.buildActivity(MainActivity::class.java).setup().also { c ->
        TestSupport.await { ViewModelProvider(c.get())[MainModel::class.java].records.value != null }
    }
    private fun addPlan(name: String): WorkoutPlan = WorkoutPlan(name = name, description = "依照自己的節奏訓練").also {
        RecordStore(TestSupport.context).use { store -> store.savePlan(it) }
    }
    private fun addStep(plan: WorkoutPlan, name: String): Record = RecordStore(TestSupport.context).use {
        it.upsert(Record(date = LocalDate.now(), type = RecordType.EXERCISE, name = name, calories = 0, note = "3 組 × 12 下", planId = plan.id, timeKnown = false))
    }
    private fun open(activity: MainActivity, plan: WorkoutPlan) {
        activity.findViewById<View>(R.id.nav_plans).performClick()
        TestSupport.views(activity.findViewById(R.id.plan_list)).first { it.contentDescription == "開啟菜單 ${plan.name}" }.performClick()
    }

    @Test fun creatingPlanValidatesNameAndRetainsDraftOnRecreation() {
        val controller = Robolectric.buildActivity(PlanEditorActivity::class.java).setup()
        var activity = controller.get()
        activity.findViewById<View>(R.id.plan_save).performClick()
        assertNotNull(activity.findViewById<EditText>(R.id.plan_name).error)
        assertFalse(activity.isFinishing)
        activity.findViewById<EditText>(R.id.plan_name).setText(" 腿部訓練 ")
        activity.findViewById<EditText>(R.id.plan_description).setText("週一與週四，逐步增加強度")
        controller.recreate(); activity = controller.get()
        assertEquals(" 腿部訓練 ", activity.findViewById<EditText>(R.id.plan_name).text.toString())
        assertEquals("週一與週四，逐步增加強度", activity.findViewById<EditText>(R.id.plan_description).text.toString())
        activity.findViewById<View>(R.id.plan_save).performClick()
        TestSupport.await { activity.isFinishing }
        RecordStore(TestSupport.context).use { store ->
            val plan = store.snapshot().plans.single()
            assertEquals("腿部訓練", plan.name); assertEquals("週一與週四，逐步增加強度", plan.description)
        }
        assertNotNull(shadowOf(activity).resultIntent.getStringExtra("planId"))
        controller.pause().stop().destroy()
    }

    @Test fun multiplePlansOpenSeparatelyAndLaunchTheFamiliarExerciseEditor() {
        val legs = addPlan("腿部日"); val upper = addPlan("上肢日")
        addStep(legs, "深蹲"); addStep(upper, "伏地挺身")
        val controller = start(); val activity = controller.get()
        assertNotNull(activity.findViewById<View>(R.id.home_plans))
        open(activity, legs)
        val titles = TestSupport.texts(activity.findViewById(R.id.plan_items))
        assertTrue(titles.contains("深蹲")); assertFalse(titles.contains("伏地挺身"))
        activity.findViewById<View>(R.id.add_plan_item).performClick()
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(EditorActivity::class.java.name, intent.component!!.className)
        assertEquals(legs.id, intent.getStringExtra("planId")); assertEquals("EXERCISE", intent.getStringExtra("type"))
        activity.findViewById<View>(R.id.back_to_plans).performClick()
        assertTrue(TestSupport.texts(activity.findViewById(R.id.plan_list)).containsAll(listOf("腿部日", "上肢日")))
        val existing = RecordStore(TestSupport.context).use { it.snapshot().items(legs.id).single() }
        val editing = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(TestSupport.context, EditorActivity::class.java).putExtra("id", existing.id)).setup()
        assertFalse(TestSupport.texts(editing.get().window.decorView).any { "舊版紀錄" in it })
        editing.pause().stop().destroy()
        controller.pause().stop().destroy()
    }

    @Test fun addingPlanStepAcceptsNotesPhotosAndVideosWithoutCreatingDailyRecord() {
        val plan = addPlan("居家菜單")
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==")
        val (photo, _) = TestSupport.source(png, "image/png", "姿勢.png")
        val videoBytes = "video demonstration bytes".toByteArray()
        val (video, _) = TestSupport.source(videoBytes, "video/mp4", "示範.mp4")
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(TestSupport.context, EditorActivity::class.java).putExtra("planId", plan.id)).setup()
        val activity = controller.get()
        assertEquals(View.GONE, (activity.findViewById<View>(R.id.editor_date).parent as ViewGroup).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.record_type).visibility)
        activity.findViewById<EditText>(R.id.record_name).setText("深蹲")
        activity.findViewById<EditText>(R.id.record_note).setText("3 組 × 12 下，組間休息 60 秒")
        activity.findViewById<EditText>(R.id.record_minutes).setText("15")
        activity.findViewById<View>(R.id.add_media).performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        val result = Intent().apply {
            clipData = android.content.ClipData.newRawUri("media", photo).apply { addItem(android.content.ClipData.Item(video)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, result)
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<View>(R.id.editor_save).performClick()
        TestSupport.await { activity.isFinishing }
        RecordStore(TestSupport.context).use { store ->
            assertTrue(store.load().isEmpty())
            val step = store.snapshot().items(plan.id).single()
            assertEquals(RecordType.EXERCISE, step.type); assertEquals("深蹲", step.name)
            assertEquals("3 組 × 12 下，組間休息 60 秒", step.note); assertEquals(15, step.minutes)
            assertArrayEquals(png, File(File(TestSupport.context.filesDir, "media"), step.attachments[0].fileName).readBytes())
            assertArrayEquals(videoBytes, File(File(TestSupport.context.filesDir, "media"), step.attachments[1].fileName).readBytes())
        }
        assertEquals(plan.id, shadowOf(activity).resultIntent.getStringExtra("planId"))
        controller.pause().stop().destroy()
    }

    @Test fun reorderingRefreshesPlanEvenWhenDailyRecordsAreUnchanged() {
        val plan = addPlan("全身訓練"); val first = addStep(plan, "暖身"); val second = addStep(plan, "深蹲")
        val controller = start(); var activity = controller.get(); open(activity, plan)
        TestSupport.views(activity.findViewById(R.id.plan_items)).first { it.contentDescription == "上移 深蹲" }.performClick()
        val model = ViewModelProvider(activity)[MainModel::class.java]
        TestSupport.await { model.snapshot.value?.items(plan.id)?.firstOrNull()?.id == second.id }
        controller.recreate(); activity = controller.get()
        val titles = TestSupport.texts(activity.findViewById(R.id.plan_items)).filter { it == first.name || it == second.name }
        assertEquals(listOf("深蹲", "暖身"), titles)
        assertTrue(model.records.value!!.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun editingAndCancellingMenuChangesPreserveItsSteps() {
        val plan = addPlan("舊菜單"); val step = addStep(plan, "伸展")
        val intent = Intent(TestSupport.context, PlanEditorActivity::class.java).putExtra("id", plan.id)
        val controller = Robolectric.buildActivity(PlanEditorActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.plan_name).setText("新版伸展菜單")
        activity.findViewById<View>(R.id.plan_save).performClick()
        TestSupport.await { activity.isFinishing }
        controller.pause().stop().destroy()
        val cancelled = Robolectric.buildActivity(PlanEditorActivity::class.java, intent).setup()
        cancelled.get().findViewById<EditText>(R.id.plan_name).setText("捨棄的名稱")
        cancelled.get().findViewById<View>(R.id.plan_cancel).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        RecordStore(TestSupport.context).use { store ->
            assertEquals("新版伸展菜單", store.findPlan(plan.id)!!.name); assertEquals(step, store.find(step.id))
        }
        cancelled.pause().stop().destroy()
    }

    @Test fun deletingPlanRequiresConfirmationAndReturnsToThePlanList() {
        val first = addPlan("刪除的菜單"); val other = addPlan("保留的菜單"); addStep(first, "深蹲")
        val controller = start(); val activity = controller.get(); open(activity, first)
        activity.findViewById<View>(R.id.delete_plan).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity.findViewById<View>(R.id.plan_items))
        activity.findViewById<View>(R.id.delete_plan).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        TestSupport.await { activity.findViewById<View>(R.id.plan_list) != null }
        RecordStore(TestSupport.context).use { store ->
            assertNull(store.findPlan(first.id)); assertTrue(store.snapshot().items(first.id).isEmpty()); assertEquals(other, store.findPlan(other.id))
        }
        controller.pause().stop().destroy()
    }
}
