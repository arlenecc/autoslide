package com.autoslide.app

/**
 * 任务调度抽象：隔离 Android Handler，便于用 Fake 验证调度时序。
 */
interface TaskRunner {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/**
 * 纯逻辑层：自动上划的调度循环。
 *
 * 状态流转：start -> (等待随机间隔 -> 执行滑动 -> 滑动完成回调) 循环，直到 pause/stop。
 * - start/resume 只在"既无定时任务也无在飞手势"时才调度，避免并发重复手势（TC-11）。
 * - stop/pause 取消等待中的任务；迟到的完成回调不会再次调度（TC-13）。
 */
class SwipeLoop(
    private val planner: SwipePlanner,
    private val runner: TaskRunner,
    private val swipeAction: () -> Unit,
) {
    private var state = STATE_IDLE

    /** 定时器等待中 */
    private var pending = false
    /** 手势已派发、等待完成回调 */
    private var inFlight = false

    private val tick = Runnable {
        pending = false
        if (state == STATE_RUNNING) {
            inFlight = true
            swipeAction()
        }
    }

    /** 是否有未完成的调度或手势（测试与上层诊断用）。 */
    val isBusy: Boolean get() = pending || inFlight

    /** 开始自动滑动；已在运行中则幂等（TC-11）。 */
    fun start() {
        if (state == STATE_RUNNING) return
        state = STATE_RUNNING
        scheduleIfIdle()
    }

    /** 暂停：取消待执行任务；在飞手势完成后不会续期（onGestureDone 判断状态）。 */
    fun pause() {
        if (state != STATE_RUNNING) return
        state = STATE_PAUSED
        cancelPending()
    }

    /** 恢复：仅从 PAUSED 有效（TC-14）。 */
    fun resume() {
        if (state != STATE_PAUSED) return
        state = STATE_RUNNING
        scheduleIfIdle()
    }

    /** 停止：取消一切调度（TC-13）。 */
    fun stop() {
        state = STATE_IDLE
        cancelPending()
        inFlight = false
    }

    /** 手势完成（成功或取消均调用）后由服务回调，驱动下一轮调度（TC-12）。 */
    fun onGestureDone() {
        inFlight = false
        if (state == STATE_RUNNING) {
            scheduleIfIdle()
        }
    }

    private fun scheduleIfIdle() {
        if (pending || inFlight) return
        pending = true
        runner.postDelayed(tick, planner.nextDelayMs())
    }

    private fun cancelPending() {
        if (pending) {
            runner.cancel(tick)
            pending = false
        }
    }

    companion object {
        const val STATE_IDLE = 0
        const val STATE_RUNNING = 1
        const val STATE_PAUSED = 2
    }
}
