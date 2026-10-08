package com.autoslide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SwipeEngineTest {

    @Before
    fun reset() {
        SwipeEngine.resetForTest()
    }

    // ---------- TC-15: 完整状态流转 ----------
    @Test
    fun `TC15 full lifecycle idle running paused running idle`() {
        SwipeEngine.markDispatcherReady(true)
        assertEquals(SwipeEngine.State.IDLE, SwipeEngine.state)

        assertTrue(SwipeEngine.start())
        assertEquals(SwipeEngine.State.RUNNING, SwipeEngine.state)

        assertTrue(SwipeEngine.pause())
        assertEquals(SwipeEngine.State.PAUSED, SwipeEngine.state)

        assertTrue(SwipeEngine.resume())
        assertEquals(SwipeEngine.State.RUNNING, SwipeEngine.state)

        assertTrue(SwipeEngine.stop())
        assertEquals(SwipeEngine.State.IDLE, SwipeEngine.state)
    }

    // ---------- TC-16: 无障碍未就绪时禁止启动 ----------
    @Test
    fun `TC16 start rejected when dispatcher not ready`() {
        assertFalse(SwipeEngine.dispatcherReady)
        val before = SwipeEngine.state
        assertFalse(SwipeEngine.start())
        assertEquals("start 失败后状态必须保持不变", before, SwipeEngine.state)

        // 连接后即可启动
        SwipeEngine.markDispatcherReady(true)
        assertTrue(SwipeEngine.start())
        assertEquals(SwipeEngine.State.RUNNING, SwipeEngine.state)
    }

    // ---------- TC-17: 非法转换为 no-op ----------
    @Test
    fun `TC17 invalid transitions are no-ops`() {
        SwipeEngine.markDispatcherReady(true)

        // IDLE 下 pause/resume 无效
        assertFalse(SwipeEngine.pause())
        assertFalse(SwipeEngine.resume())
        assertEquals(SwipeEngine.State.IDLE, SwipeEngine.state)

        // RUNNING 下重复 start 幂等
        assertTrue(SwipeEngine.start())
        assertFalse(SwipeEngine.start())
        assertEquals(SwipeEngine.State.RUNNING, SwipeEngine.state)

        // PAUSED 下重复 pause 幂等
        assertTrue(SwipeEngine.pause())
        assertFalse(SwipeEngine.pause())
        assertEquals(SwipeEngine.State.PAUSED, SwipeEngine.state)

        // IDLE 下 stop 幂等
        assertTrue(SwipeEngine.resume())
        assertTrue(SwipeEngine.stop())
        assertFalse(SwipeEngine.stop())
        assertEquals(SwipeEngine.State.IDLE, SwipeEngine.state)
    }

    // ---------- TC-18: 监听器通知 ----------
    @Test
    fun `TC18 listeners notified on every transition with old and new state`() {
        SwipeEngine.markDispatcherReady(true)
        val events = mutableListOf<Pair<SwipeEngine.State, SwipeEngine.State>>()
        val l1: (SwipeEngine.State, SwipeEngine.State) -> Unit = { o, n -> events.add(o to n) }
        val l2: (SwipeEngine.State, SwipeEngine.State) -> Unit = { o, n -> events.add(o to n) }
        SwipeEngine.addListener(l1)
        SwipeEngine.addListener(l2)

        SwipeEngine.start()
        SwipeEngine.pause()
        SwipeEngine.stop()

        // 每次状态转换会依次通知 l1、l2，事件按转换顺序交错排列
        val expected = listOf(
            SwipeEngine.State.IDLE to SwipeEngine.State.RUNNING,
            SwipeEngine.State.RUNNING to SwipeEngine.State.PAUSED,
            SwipeEngine.State.PAUSED to SwipeEngine.State.IDLE,
        ).flatMap { listOf(it, it) }
        assertEquals("每个监听器都应按序收到全部事件", expected, events)

        // 无变化时不通知
        events.clear()
        SwipeEngine.stop()
        assertTrue(events.isEmpty())

        SwipeEngine.removeListener(l1)
        SwipeEngine.removeListener(l2)
    }

    // ---------- TC-18b: 目标应用可设置 ----------
    @Test
    fun `TC18b target app can be switched`() {
        assertEquals(TargetApp.DOUYIN, SwipeEngine.target)
        SwipeEngine.target = TargetApp.KUAISHOU
        assertEquals(TargetApp.KUAISHOU, SwipeEngine.target)
    }
}
