package com.moon.eatandsport

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Executors

object MediaViews {
    private val decoder = Executors.newFixedThreadPool(2)
    fun add(activity: Activity, parent: LinearLayout, attachment: Attachment, showPreview: Boolean = true, removable: (() -> Unit)? = null) {
        val file = File(File(activity.filesDir, "media"), attachment.fileName)
        addUri(activity, parent, Uri.fromFile(file), attachment.mime, attachment.displayName, showPreview, removable)
    }
    fun addUri(activity: Activity, parent: LinearLayout, uri: Uri, mime: String, name: String, showPreview: Boolean = true, removable: (() -> Unit)? = null) {
        val ui = Ui(activity)
        if (showPreview && mime.startsWith("image/")) {
            val image = ImageView(activity).apply {
                contentDescription = "查看圖片：$name"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ui.mint)
                isFocusable = true
                setOnClickListener { open(activity, uri, mime, name) }
            }
            parent.addView(image, LinearLayout.LayoutParams(-1, ui.dp(180)))
            load(activity, image, uri, 400)
        }
        parent.addView(ui.row().apply {
            addView(ui.button((if (mime.startsWith("video/")) "▶ " else "圖片 · ") + name) {
                open(activity, uri, mime, name)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            removable?.let { addView(ui.button("移除") { it() }) }
        })
        parent.addView(ui.gap(8))
    }

    fun open(activity: Activity, uri: Uri, mime: String, name: String) {
        activity.startActivity(Intent(activity, MediaViewerActivity::class.java)
            .putExtra("uri", uri.toString()).putExtra("mime", mime).putExtra("name", name))
    }

    fun load(activity: Activity, image: ImageView, uri: Uri, maximum: Int = 1600) {
        decoder.execute {
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                var sample = 1
                while (options.outWidth / sample > maximum || options.outHeight / sample > maximum) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                options.inPreferredConfig = Bitmap.Config.RGB_565
                activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    if (bitmap != null) image.setImageBitmap(bitmap)
                    else image.contentDescription = "無法預覽此圖片，點選以開啟"
                } else bitmap?.recycle()
            }
        }
    }
}
