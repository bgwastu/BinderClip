package net.wastu.clipboard.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Button

/** Small opt-in always-on-top progress surface for media transfers. */
class MediaTransferOverlay(context: Context) {
    var onCancel: (() -> Unit)? = null
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val container = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(36, 24, 36, 24)
        setBackgroundColor(0xEE102A2E.toInt())
    }
    private val label = TextView(appContext).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 14f
    }
    private val progress = ProgressBar(appContext, null, android.R.attr.progressBarStyleHorizontal)
    private var attached = false

    init {
        val cancel = Button(appContext).apply {
            text = "Cancel"
            setOnClickListener { onCancel?.invoke() }
        }
        container.addView(label, LinearLayout.LayoutParams(-1, -2))
        container.addView(progress, LinearLayout.LayoutParams(420, 12).apply { topMargin = 12 })
        container.addView(cancel, LinearLayout.LayoutParams(-1, -2))
    }

    fun update(fileName: String?, transferred: Long, total: Long) {
        if (!Settings.canDrawOverlays(appContext)) return
        if (!attached) {
            val params = WindowManager.LayoutParams(
                500, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 96
            }
            runCatching { windowManager.addView(container, params) }.onFailure { return }
            attached = true
        }
        val percent = if (total > 0) (transferred * 100 / total).coerceIn(0, 100) else 0
        label.text = "${fileName ?: "Media sync"}  $percent%"
        progress.max = 100
        progress.progress = percent.toInt()
    }

    fun dismiss() {
        if (!attached) return
        runCatching { windowManager.removeView(container) }
        attached = false
    }
}
