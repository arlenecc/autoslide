package com.autoslide.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：唯一拥有全局手势派发能力的组件，负责真正执行上划。
 * 调度策略全部委托给纯逻辑层 SwipeLoop / SwipePlanner，本类只做薄封装。
 *
 * 拟人手势执行：把 SwipePlanner 生成的三段弧线用 StrokeDescription.continueStroke
 * 链式派发（第一段 willContinue），各段时长不同以实现“起手慢-中段发力-收尾减速”。
 */
class AutoSwipeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val planner = SwipePlanner()

    private val runner = object : TaskRunner {
        override fun postDelayed(task: Runnable, delayMs: Long) {
            mainHandler.postDelayed(task, delayMs)
        }

        override fun cancel(task: Runnable) {
            mainHandler.removeCallbacks(task)
        }
    }

    private val engineListener: (SwipeEngine.State, SwipeEngine.State) -> Unit = { _, new ->
        mainHandler.post { applyState(new) }
    }

    private var loop: SwipeLoop? = null
    private var overlay: FloatingControlOverlay? = null

    /** 手势代际号：新手势开始时自增，旧链路回调据此失效，防止串扰与重复回调。 */
    private var gestureSeq = 0
    private var currentGestureId = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        loop = SwipeLoop(planner, runner, ::performSwipe)
        overlay = FloatingControlOverlay(this)
        SwipeEngine.markDispatcherReady(true)
        SwipeEngine.addListener(engineListener)
        applyState(SwipeEngine.state)
    }

    private fun applyState(state: SwipeEngine.State) {
        val l = loop ?: return
        when (state) {
            SwipeEngine.State.RUNNING -> l.start()
            SwipeEngine.State.PAUSED -> l.pause()
            SwipeEngine.State.IDLE -> l.stop()
        }
        overlay?.onEngineState(state)
    }

    /** 派发一次随机拟人上划手势；全部段完成后通过 loop.onGestureDone 驱动下一轮。 */
    private fun performSwipe() {
        val metrics = resources.displayMetrics
        val gesture = planner.nextSwipe(metrics.widthPixels, metrics.heightPixels)
        if (gesture == null) {
            loop?.onGestureDone()
            return
        }
        currentGestureId = ++gestureSeq
        dispatchSegment(currentGestureId, gesture, segmentIndex = 0, previousStroke = null)
    }

    private fun dispatchSegment(
        id: Int,
        gesture: SwipeGesture,
        segmentIndex: Int,
        previousStroke: GestureDescription.StrokeDescription?,
    ) {
        val l = loop
        if (l == null || id != currentGestureId ||
            SwipeEngine.state != SwipeEngine.State.RUNNING
        ) {
            // 引擎已停止/暂停或手势已被新一代替代：终止链路并回报完成
            if (id == currentGestureId) l?.onGestureDone()
            return
        }
        val segment = gesture.segments[segmentIndex]
        val path = Path().apply {
            moveTo(segment.p0x, segment.p0y)
            quadTo(segment.cx, segment.cy, segment.p2x, segment.p2y)
        }
        val isLast = segmentIndex == gesture.segments.lastIndex
        val stroke = if (segmentIndex == 0) {
            // 首段：携带触屏预压延迟，非末段标记 willContinue 以便续接
            GestureDescription.StrokeDescription(path, gesture.startDelayMs, segment.durationMs, !isLast)
        } else {
            previousStroke!!.continueStroke(path, 0, segment.durationMs, !isLast)
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (id != currentGestureId) return
                if (!isLast) {
                    dispatchSegment(id, gesture, segmentIndex + 1, stroke)
                } else {
                    l.onGestureDone()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (id != currentGestureId) return
                l.onGestureDone()
            }
        }
        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            callback,
            mainHandler,
        )
        if (!dispatched) {
            l.onGestureDone()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // 服务被用户关闭或系统解绑时必须停止引擎，防止"幽灵滑动"
        SwipeEngine.stop()
        SwipeEngine.removeListener(engineListener)
        SwipeEngine.markDispatcherReady(false)
        overlay?.hide()
        overlay = null
        loop?.stop()
        loop = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SwipeEngine.stop()
        SwipeEngine.removeListener(engineListener)
        SwipeEngine.markDispatcherReady(false)
        overlay?.hide()
        super.onDestroy()
    }
}
