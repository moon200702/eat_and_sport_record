package com.moon.eatandsport

import android.app.Activity
import android.graphics.Color
import androidx.core.net.toUri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import java.io.File

class MediaViewerActivity : Activity() {
    private var video: VideoView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val uri = (intent.getStringExtra("uri") ?: run { finish(); return }).toUri()
        val mime = intent.getStringExtra("mime").orEmpty()
        val privateDir = File(filesDir, "media").canonicalFile
        if (mime !in Attachment.extensions || (uri.scheme != "content" &&
                !(uri.scheme == "file" && File(uri.path.orEmpty()).canonicalFile.parentFile == privateDir))) {
            finish(); return
        }
        val content = ui.column().apply {
            addView(ui.gap())
            addView(ui.button("‹ 返回筆記") { finish() })
            addView(ui.text(intent.getStringExtra("name").orEmpty(), 18, bold = true))
            addView(ui.gap())
        }
        if (mime.startsWith("image/")) {
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "筆記圖片" }
            content.addView(image, LinearLayout.LayoutParams(-1, ui.dp(500)))
            setContentView(ui.page(content))
            MediaViews.load(this, image, uri)
        } else {
            val player = VideoView(this).apply {
                contentDescription = "筆記影片播放器"
                setBackgroundColor(Color.BLACK)
                setMediaController(MediaController(this@MediaViewerActivity).also { it.setAnchorView(this) })
                setOnPreparedListener { seekTo(savedInstanceState?.getInt("position") ?: 0); start() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@MediaViewerActivity, "無法播放：此裝置可能不支援影片編碼，或來源已無法讀取。", Toast.LENGTH_LONG).show()
                    true
                }
                setVideoURI(uri)
            }
            video = player
            content.addView(player, LinearLayout.LayoutParams(-1, ui.dp(400)))
            setContentView(ui.page(content))
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("position", video?.currentPosition ?: 0); super.onSaveInstanceState(outState) }
    override fun onPause() { video?.pause(); super.onPause() }
    override fun onDestroy() { video?.stopPlayback(); super.onDestroy() }
}
