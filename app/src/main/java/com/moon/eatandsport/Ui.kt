package com.moon.eatandsport

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class Ui(private val activity: Activity) {
    val green = Color.rgb(33, 106, 80)
    val ink = Color.rgb(30, 48, 39)
    val muted = Color.rgb(92, 109, 99)
    val canvas = Color.rgb(247, 248, 242)
    val mint = Color.rgb(233, 241, 224)
    fun dp(n: Int) = (n * activity.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun gap(n: Int = 12) = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(n)) }
    fun text(value: String, size: Int = 16, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    fun button(value: String, active: Boolean = false, id: Int = View.NO_ID, click: () -> Unit) = Button(activity).apply {
        text = value; this.id = id; isAllCaps = false; textSize = 14f
        minWidth = 0; minimumWidth = 0; minHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(if (active) Color.WHITE else green)
        backgroundTintList = ColorStateList.valueOf(if (active) green else mint)
        setOnClickListener { click() }
    }
    fun input(parent: LinearLayout, title: String, hint: String, id: Int, number: Boolean = false, multiline: Boolean = false): EditText {
        parent.addView(gap())
        parent.addView(text(title, 14, muted))
        return EditText(activity).apply {
            this.id = id; this.hint = hint; contentDescription = title
            textSize = 16f; setTextColor(ink)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = !multiline
            if (multiline) { minLines = 5; gravity = Gravity.TOP or Gravity.START }
            parent.addView(this, LinearLayout.LayoutParams(-1, if (multiline) -2 else dp(56)))
        }
    }
    fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
    fun card(color: Int = Color.WHITE) = column().apply {
        background = rounded(color); setPadding(dp(18), dp(18), dp(18), dp(18))
    }
    fun page(content: LinearLayout, footer: View? = null): View = column().apply {
        setBackgroundColor(canvas)
        setPadding(dp(18), 0, dp(18), 0)
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(18), insets.systemWindowInsetTop, dp(18), insets.systemWindowInsetBottom)
            insets
        }
        addView(ScrollView(activity).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        footer?.let { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        requestApplyInsets()
    }
}
