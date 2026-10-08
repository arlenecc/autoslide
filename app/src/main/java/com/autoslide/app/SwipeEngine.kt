package com.autoslide.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局引擎状态机（单例）：持有运行状态并被 UI / 无障碍服务 / 悬浮球共同观察。
 *
 * 状态流转（TC-15）：IDLE ->(start) RUNNING <->(pause/resume) ->(stop) IDLE
 * TC-16：无障碍服务未连接（dispatcherReady=false）时 start 返回 false 且状态不变。
 * TC-17：非法转换为 no-op；重复 start 幂等。
 * TC-18：每次状态变化通知全部监听器，回调携带 (oldState, newState)。
 */
object SwipeEngine {

    enum class State { IDLE, RUNNING, PAUSED }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 无障碍服务是否已连接（只有连接后才能派发手势）。 */
    @Volatile
    var dispatcherReady: Boolean = false
        private set

    /** 当前目标应用。 */
    @Volatile
    var target: TargetApp = TargetApp.DOUYIN

    private val listeners = CopyOnWriteArrayList<(State, State) -> Unit>()

    fun addListener(listener: (State, State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State, State) -> Unit) {
        listeners -= listener
    }

    fun markDispatcherReady(ready: Boolean) {
        dispatcherReady = ready
    }

    /** 启动自动滑动；无障碍未就绪返回 false（TC-16），已在运行返回 false（幂等）。 */
    fun start(): Boolean {
        if (!dispatcherReady) return false
        return to(State.RUNNING) { it != State.RUNNING }
    }

    fun pause(): Boolean = to(State.PAUSED) { it == State.RUNNING }

    fun resume(): Boolean = to(State.RUNNING) { it == State.PAUSED }

    fun stop(): Boolean = to(State.IDLE) { it != State.IDLE }

    @Synchronized
    private fun to(newState: State, allowedFrom: (State) -> Boolean): Boolean {
        val old = state
        if (old == newState || !allowedFrom(old)) return false
        state = newState
        listeners.forEach { it(old, newState) }
        return true
    }

    /** 仅供单元测试复位单例。 */
    fun resetForTest() {
        state = State.IDLE
        dispatcherReady = false
        target = TargetApp.DOUYIN
        listeners.clear()
    }
}
