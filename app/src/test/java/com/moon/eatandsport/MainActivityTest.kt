package com.moon.eatandsport

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class MainActivityTest {
    @Before fun reset() = TestSupport.reset()
    private fun meal() = Record(date = LocalDate.now(), type = RecordType.FOOD, name = "午餐", calories = 650, note = "雞肉與蔬菜", time = LocalTime.of(12, 30))
    private fun start() = Robolectric.buildActivity(MainActivity::class.java).setup().also { c ->
        TestSupport.await { androidx.lifecycle.ViewModelProvider(c.get())[MainModel::class.java].records.value != null }
    }

    @Test fun homeShowsStatisticsAndOpensFoodAndExerciseTimelines() {
        RecordStore(TestSupport.context).use { it.upsert(meal()) }
        val controller = start(); val activity = controller.get()
        assertEquals("650", activity.findViewById<TextView>(R.id.summary_intake).text.toString())
        activity.findViewById<View>(R.id.home_food).performClick()
        assertEquals(7, activity.findViewById<ViewGroup>(R.id.week_strip).childCount)
        assertTrue(activity.findViewById<View>(R.id.filter_food).isSelected)
        assertTrue(TestSupport.texts(activity.findViewById(R.id.records_list)).containsAll(listOf("午餐", "雞肉與蔬菜")))
        activity.findViewById<View>(R.id.filter_exercise).performClick()
        assertFalse(TestSupport.texts(activity.findViewById(R.id.records_list)).contains("午餐"))
        activity.findViewById<View>(R.id.add_record).performClick()
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(EditorActivity::class.java.name, intent.component!!.className)
        assertEquals("EXERCISE", intent.getStringExtra("type"))
        controller.pause().stop().destroy()
    }

    @Test fun searchingFindsChineseNotesAcrossDatesAndSurvivesRecreation() {
        val exercise = Record(date = LocalDate.now().minusDays(2), type = RecordType.EXERCISE, name = "深蹲", calories = 0, note = "保持膝蓋方向", time = LocalTime.of(9, 15))
        RecordStore(TestSupport.context).use { it.save(listOf(meal(), exercise)) }
        val controller = start(); var activity = controller.get()
        activity.findViewById<View>(R.id.nav_search).performClick()
        activity.findViewById<EditText>(R.id.search_query).setText("膝蓋")
        TestSupport.await { TestSupport.texts(activity.findViewById(R.id.search_results)).contains("深蹲") }
        assertFalse(TestSupport.texts(activity.findViewById(R.id.search_results)).contains("午餐"))
        controller.recreate(); activity = controller.get()
        assertEquals("膝蓋", activity.findViewById<EditText>(R.id.search_query).text.toString())
        TestSupport.await { TestSupport.texts(activity.findViewById(R.id.search_results)).contains("深蹲") }
        controller.pause().stop().destroy()
    }

    @Test fun dayNavigationAndRecreationKeepTheSelectedFilterAndDate() {
        RecordStore(TestSupport.context).use { it.upsert(meal()) }
        val controller = start(); var activity = controller.get()
        activity.findViewById<View>(R.id.home_food).performClick()
        activity.findViewById<View>(R.id.previous_day).performClick()
        assertEquals("0", activity.findViewById<TextView>(R.id.summary_intake).text.toString())
        controller.recreate(); activity = controller.get()
        assertEquals(LocalDate.now().minusDays(1).toString(), activity.findViewById<TextView>(R.id.selected_date).text.toString())
        assertTrue(activity.findViewById<View>(R.id.filter_food).isSelected)
        activity.findViewById<View>(R.id.next_day).performClick()
        TestSupport.await { activity.findViewById<TextView>(R.id.summary_intake).text.toString() == "650" }
        controller.pause().stop().destroy()
    }

    @Test fun deletionRequiresConfirmationAndPersists() {
        RecordStore(TestSupport.context).use { it.upsert(meal()) }
        val controller = start(); val activity = controller.get()
        activity.findViewById<View>(R.id.home_food).performClick()
        fun clickDelete() { TestSupport.views(activity.findViewById(R.id.records_list)).first { it.contentDescription == "刪除 午餐" }.performClick(); shadowOf(Looper.getMainLooper()).idle() }
        clickDelete()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        RecordStore(TestSupport.context).use { assertEquals(1, it.load().size) }
        clickDelete()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        TestSupport.await { activity.findViewById<TextView>(R.id.summary_intake).text.toString() == "0" }
        RecordStore(TestSupport.context).use { assertTrue(it.load().isEmpty()) }
        controller.pause().stop().destroy()
    }

    @Test fun dataPageExportsAndImportsThroughSystemDocumentPicker() {
        val original = meal()
        RecordStore(TestSupport.context).use { it.upsert(original) }
        val (uri, exportedFile) = TestSupport.source(byteArrayOf(), "application/zip", "backup.zip")
        val controller = start(); val activity = controller.get()
        activity.findViewById<View>(R.id.nav_data).performClick()
        activity.findViewById<View>(R.id.export_backup).performClick()
        val exportRequest = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, exportRequest.intent.action)
        assertEquals("application/zip", exportRequest.intent.type)
        activity.activityResultRegistry.dispatchResult(exportRequest.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        val model = androidx.lifecycle.ViewModelProvider(activity)[MainModel::class.java]
        TestSupport.await { model.status.value?.busy == false && exportedFile.length() > 0 }
        RecordStore(TestSupport.context).use { it.delete(original.id) }
        model.refresh()
        TestSupport.await { model.records.value?.isEmpty() == true }
        activity.findViewById<View>(R.id.import_backup).performClick()
        val importRequest = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, importRequest.intent.action)
        activity.activityResultRegistry.dispatchResult(importRequest.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        TestSupport.await { model.status.value?.prepared != null }
        shadowOf(Looper.getMainLooper()).idle()
        val confirm = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("合併新增", confirm.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        TestSupport.await { model.status.value?.busy == false && model.records.value?.size == 1 }
        RecordStore(TestSupport.context).use { assertEquals(original, it.load().single()) }
        controller.pause().stop().destroy()
    }

    @Test fun replacementRequiresASecondExplicitConfirmation() {
        RecordStore(TestSupport.context).use { it.upsert(meal()) }
        val controller = start(); val activity = controller.get()
        val model = androidx.lifecycle.ViewModelProvider(activity)[MainModel::class.java]
        val prepared = NoteRepository(TestSupport.context).use { repo ->
            val backup = BackupManager(TestSupport.context, repo)
            val bytes = ByteArrayOutputStream().also { backup.export(it) }.toByteArray()
            backup.prepare(bytes.inputStream())
        }
        model.status.value = WorkStatus(prepared = prepared)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val second = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("確認取代", second.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        second.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        RecordStore(TestSupport.context).use { assertEquals(1, it.load().size) }
        assertFalse(prepared.directory.exists())
        controller.pause().stop().destroy()
    }
}
