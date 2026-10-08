package com.autoslide.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * 悬浮控制球：运行中显示于屏幕右下角。
 * 点击 = 暂停/继续切换；长按 = 停止。让用户无需退出目标应用即可控制。
 */
class FloatingControlOverlay(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: ImageView? = null
    private var added = false

    private val sizePx: Int by lazy { (56 * context.resources.displayMetrics.density).toInt() }
    private val strokePx: Int by lazy { (2 * context.resources.displayMetrics.density).toInt() }

    private val clickListener = View.OnClickListener {
        when (SwipeEngine.state) {
            SwipeEngine.State.RUNNING -> SwipeEngine.pause()
            SwipeEngine.State.PAUSED -> SwipeEngine.resume()
            SwipeEngine.State.IDLE -> Unit
        }
    }

    private val longClickListener = View.OnLongClickListener {
        SwipeEngine.stop()
        true
    }

    /** 引擎状态变化时由无障碍服务调用：IDLE 隐藏，其余显示并同步图标。 */
    fun onEngineState(state: SwipeEngine.State) {
        when (state) {
            SwipeEngine.State.IDLE -> hide()
            else -> show(state)
        }
    }

    private fun show(state: SwipeEngine.State) {
        if (!Settings.canDrawOverlays(context)) return
        val wm = windowManager
            ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return
        windowManager = wm
        val view = rootView ?: createView().also { rootView = it }
        val icon = if (state == SwipeEngine.State.RUNNING) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        view.setImageResource(icon)
        view.contentDescription = if (state == SwipeEngine.State.RUNNING) "暂停自动滑动" else "继续自动滑动"
        if (!added) {
            try {
                wm.addView(view, buildLayoutParams())
                added = true
            } catch (_: Exception) {
                // 悬浮窗添加失败（权限被撤销等）不应导致崩溃
                added = false
            }
        }
    }

    fun hide() {
        val view = rootView ?: return
        if (added) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        added = false
    }

    private fun createView(): ImageView {
        val view = ImageView(context)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1B1B1B.toInt())
            setStroke(strokePx, 0x66FFFFFF)
        }
        view.setPadding(sizePx / 5, sizePx / 5, sizePx / 5, sizePx / 5)
        view.isClickable = true
        view.isLongClickable = true
        view.setOnClickListener(clickListener)
        view.setOnLongClickListener(longClickListener)
        return view
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val margin = (16 * context.resources.displayMetrics.density).toInt()
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = margin
            y = margin
        }
    }
}
