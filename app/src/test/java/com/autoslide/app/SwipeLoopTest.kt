package com.autoslide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake 调度器：记录 post/cancel 调用，支持手动触发任务与查询待执行数量。 */
private class FakeRunner : TaskRunner {
    val posted = mutableListOf<Pair<Runnable, Long>>()
    val cancelled = mutableListOf<Runnable>()
    private val active = ArrayDeque<Runnable>()

    override fun postDelayed(task: Runnable, delayMs: Long) {
        posted += task to delayMs
        active.addLast(task)
    }

    override fun cancel(task: Runnable) {
        cancelled += task
        active.remove(task)
    }

    /** 触发最早的待执行任务（模拟定时器到点）。 */
    fun fireNext() {
        val task = active.removeFirst()
        task.run()
    }

    val pendingCount: Int get() = active.size
}

class SwipeLoopTest {

    private fun makeLoop(runner: FakeRunner, counter: MutableList<Int>): SwipeLoop =
        SwipeLoop(SwipePlanner(RandomSource.default(kotlin.random.Random(42))), runner) {
            counter.add(1)
        }

    // ---------- TC-10: start 后立即调度 ----------
    @Test
    fun `TC10 start schedules exactly one task within delay bounds`() {
        val runner = FakeRunner()
        val loop = makeLoop(runner, mutableListOf())
        loop.start()
        assertEquals(1, runner.posted.size)
        assertEquals(1, runner.pendingCount)
        val delay = runner.posted[0].second
        assertTrue(delay in SwipePlanner.MIN_DELAY_MS..SwipePlanner.MAX_DELAY_MS)
    }

    // ---------- TC-11: start 幂等 ----------
    @Test
    fun `TC11 start is idempotent while running`() {
        val runner = FakeRunner()
        val loop = makeLoop(runner, mutableListOf())
        loop.start()
        loop.start()
        loop.start()
        assertEquals(1, runner.posted.size)
    }

    // ---------- TC-12: 完成回调后连续调度 ----------
    @Test
    fun `TC12 gesture done reschedules next cycle`() {
        val runner = FakeRunner()
        val calls = mutableListOf<Int>()
        val loop = SwipeLoop(
            SwipePlanner(RandomSource.default(kotlin.random.Random(1))),
            runner,
        ) { calls.add(1) }
        loop.start()

        runner.fireNext() // 定时到点 -> 执行滑动
        assertEquals(1, calls.size)
        loop.onGestureDone() // 滑动完成 -> 调度下一次

        assertEquals(2, runner.posted.size)
        assertEquals(1, runner.pendingCount)
        assertTrue(runner.posted[1].second in SwipePlanner.MIN_DELAY_MS..SwipePlanner.MAX_DELAY_MS)

        // 再来一轮验证可持续循环
        runner.fireNext()
        assertEquals(2, calls.size)
        loop.onGestureDone()
        assertEquals(3, runner.posted.size)
    }

    // ---------- TC-13: stop 后取消且迟到回调不再调度 ----------
    @Test
    fun `TC13 stop cancels pending and late callback does not reschedule`() {
        val runner = FakeRunner()
        val calls = mutableListOf<Int>()
        val loop = SwipeLoop(
            SwipePlanner(RandomSource.default(kotlin.random.Random(2))),
            runner,
        ) { calls.add(1) }
        loop.start()

        loop.stop()
        assertEquals(1, runner.cancelled.size)
        assertEquals(0, runner.pendingCount)

        // 迟到的手势完成回调（手势在 stop 前发出）
        loop.onGestureDone()
        assertEquals("stop 后不应再调度", 1, runner.posted.size)
    }

    // ---------- TC-13b: 手势在飞时 stop，迟到回调不调度 ----------
    @Test
    fun `TC13b stop while gesture in flight then late done callback ignored`() {
        val runner = FakeRunner()
        val calls = mutableListOf<Int>()
        val loop = SwipeLoop(
            SwipePlanner(RandomSource.default(kotlin.random.Random(3))),
            runner,
        ) { calls.add(1) }
        loop.start()
        runner.fireNext() // 滑动开始（在飞）
        assertEquals(1, calls.size)
        loop.stop()
        loop.onGestureDone() // 迟到回调
        assertEquals(1, runner.posted.size)
        assertFalse(loop.isBusy)
    }

    // ---------- TC-14: pause/resume/stop 语义 ----------
    @Test
    fun `TC14 pause cancels resume reschedules stop invalidates resume`() {
        val runner = FakeRunner()
        val loop = makeLoop(runner, mutableListOf())
        loop.start()
        assertEquals(1, runner.pendingCount)

        loop.pause()
        assertEquals(1, runner.cancelled.size)
        assertEquals(0, runner.pendingCount)

        loop.resume()
        assertEquals(2, runner.posted.size)
        assertEquals(1, runner.pendingCount)

        loop.stop()
        assertEquals(2, runner.cancelled.size)

        loop.resume() // IDLE 状态下 resume 无效
        assertEquals("stop 后 resume 不应调度", 2, runner.posted.size)

        loop.pause() // IDLE 状态下 pause 无效
        assertEquals(2, runner.cancelled.size)
    }

    // ---------- TC-11b: 在飞手势期间重复 start 不产生并发手势 ----------
    @Test
    fun `TC11b no double schedule during in-flight gesture`() {
        val runner = FakeRunner()
        val loop = makeLoop(runner, mutableListOf())
        loop.start()
        runner.fireNext() // 手势在飞，此时 pending=0
        loop.start() // 引擎若误触发 start，不应造成第二个定时任务
        assertEquals("在飞期间不应重复调度", 1, runner.posted.size)
        loop.onGestureDone()
        assertEquals(2, runner.posted.size)
    }

    // ---------- TC-14b: 暂停期间手势完成，不续期 ----------
    @Test
    fun `TC14b gesture done while paused does not reschedule`() {
        val runner = FakeRunner()
        val loop = makeLoop(runner, mutableListOf())
        loop.start()
        runner.fireNext() // 在飞
        loop.pause() // 用户在滑动过程中暂停
        loop.onGestureDone()
        assertEquals("暂停后不应调度", 1, runner.posted.size)
        assertEquals(SwipeLoop.STATE_PAUSED, loop.stateForTest())
    }
}

/** 暴露内部状态仅供测试断言。 */
private fun SwipeLoop.stateForTest(): Int {
    val field = SwipeLoop::class.java.getDeclaredField("state")
    field.isAccessible = true
    return field.getInt(this)
}
