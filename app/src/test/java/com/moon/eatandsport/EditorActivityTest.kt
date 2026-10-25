package com.moon.eatandsport

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class EditorActivityTest {
    @Before fun reset() = TestSupport.reset()
    private fun controller(type: String = "FOOD", id: String? = null) = Robolectric.buildActivity(EditorActivity::class.java,
        Intent(TestSupport.context, EditorActivity::class.java).putExtra("date", "2026-10-26").putExtra("type", type).apply { id?.let { putExtra("id", it) } }).setup()

    @Test fun savesFoodAndExerciseNotesWithTimeToSQLite() {
        val controller = controller("EXERCISE")
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.record_name).setText(" 深蹲 ")
        activity.findViewById<EditText>(R.id.record_note).setText("3 組 × 12 下，注意膝蓋方向。")
        activity.findViewById<EditText>(R.id.record_calories).setText("120")
        activity.findViewById<EditText>(R.id.record_minutes).setText("30")
        activity.findViewById<View>(R.id.editor_time).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val picker = ShadowAlertDialog.getLatestAlertDialog() as TimePickerDialog
        picker.updateTime(9, 15)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<View>(R.id.editor_save).performClick()
        TestSupport.await { activity.isFinishing }
        RecordStore(TestSupport.context).use { store ->
            val saved = store.load().single()
            assertEquals("深蹲", saved.name); assertEquals("3 組 × 12 下，注意膝蓋方向。", saved.note)
            assertEquals(LocalTime.of(9, 15), saved.time); assertEquals(LocalDate.of(2026, 10, 26), saved.date)
            assertEquals(RecordType.EXERCISE, saved.type); assertEquals(30, saved.minutes)
        }
        controller.pause().stop().destroy()
    }

    @Test fun permitsNotesWithoutCalorieEstimatesAndRejectsInvalidInput() {
        val controller = controller()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.record_name).setText(" ")
        activity.findViewById<EditText>(R.id.record_calories).setText("99999999999999999")
        activity.findViewById<View>(R.id.editor_save).performClick()
        assertNotNull(activity.findViewById<EditText>(R.id.record_name).error)
        assertNotNull(activity.findViewById<EditText>(R.id.record_calories).error)
        assertFalse(activity.isFinishing)
        RecordStore(TestSupport.context).use { assertTrue(it.load().isEmpty()) }
        activity.findViewById<EditText>(R.id.record_name).setText("一餐的筆記")
        activity.findViewById<EditText>(R.id.record_calories).setText("")
        activity.findViewById<View>(R.id.editor_save).performClick()
        TestSupport.await { activity.isFinishing }
        RecordStore(TestSupport.context).use { assertEquals(0, it.load().single().calories) }
        controller.pause().stop().destroy()
    }

    @Test fun pickerCopiesAnActualImageAndVideoIntoPrivateStorage() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==")
        val (photo, _) = TestSupport.source(png, "image/png", "午餐.png")
        val videoBytes = byteArrayOf(0, 0, 0, 20) + "ftypmp42test-video".toByteArray()
        val (video, _) = TestSupport.source(videoBytes, "video/mp4", "動作.mp4")
        val controller = controller()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.record_name).setText("照片與影片")
        activity.findViewById<View>(R.id.add_media).performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertTrue(request.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val data = Intent().apply {
            clipData = android.content.ClipData.newRawUri("media", photo).apply { addItem(android.content.ClipData.Item(video)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, data)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(TestSupport.texts(activity.findViewById(R.id.media_list)).any { "待儲存附件" in it })
        activity.findViewById<View>(R.id.editor_save).performClick()
        TestSupport.await { activity.isFinishing }
        RecordStore(TestSupport.context).use { store ->
            val attachments = store.load().single().attachments
            assertEquals(2, attachments.size)
            assertArrayEquals(png, File(File(TestSupport.context.filesDir, "media"), attachments[0].fileName).readBytes())
            assertArrayEquals(videoBytes, File(File(TestSupport.context.filesDir, "media"), attachments[1].fileName).readBytes())
            assertEquals("午餐.png", attachments[0].displayName)
        }
        controller.pause().stop().destroy()
    }

    @Test fun editingChangesExistingRecordRatherThanAddingDuplicate() {
        val original = Record(date = LocalDate.of(2026, 10, 26), type = RecordType.FOOD, name = "午餐", calories = 500, note = "原內容")
        RecordStore(TestSupport.context).use { it.upsert(original) }
        val controller = controller(id = original.id)
        val activity = controller.get()
        assertEquals("原內容", activity.findViewById<EditText>(R.id.record_note).text.toString())
        activity.findViewById<EditText>(R.id.record_note).setText("補上水果")
        activity.findViewById<View>(R.id.editor_save).performClick()
        TestSupport.await { activity.isFinishing }
        RecordStore(TestSupport.context).use { store ->
            assertEquals(1, store.load().size); assertEquals("補上水果", store.find(original.id)!!.note)
        }
        controller.pause().stop().destroy()
    }

    @Test fun draftSurvivesActivityRecreationAndCancelPreservesExistingRecord() {
        val original = Record(date = LocalDate.now(), type = RecordType.EXERCISE, name = "慢跑", calories = 100, minutes = 10)
        RecordStore(TestSupport.context).use { it.upsert(original) }
        val controller = controller(id = original.id)
        controller.get().findViewById<EditText>(R.id.record_note).setText("未儲存的感受")
        controller.recreate()
        val activity = controller.get()
        assertEquals("未儲存的感受", activity.findViewById<EditText>(R.id.record_note).text.toString())
        assertEquals(1, activity.findViewById<Spinner>(R.id.record_type).selectedItemPosition)
        activity.findViewById<View>(R.id.editor_cancel).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        RecordStore(TestSupport.context).use { assertEquals(original, it.load().single()) }
        controller.pause().stop().destroy()
    }
}
