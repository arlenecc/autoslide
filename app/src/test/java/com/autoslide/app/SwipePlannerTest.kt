package com.autoslide.app

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 受控随机源：按入队顺序返回值，队列为空时返回 0。 */
private class FakeRandomSource(
    longs: MutableList<Long> = mutableListOf(),
    floats: MutableList<Float> = mutableListOf(),
) : RandomSource {
    val longQueue = longs
    val floatQueue = floats
    override fun nextLong(until: Long): Long =
        if (longQueue.isEmpty()) 0 else longQueue.removeAt(0).coerceIn(0, until - 1)
    override fun nextFloat(): Float =
        if (floatQueue.isEmpty()) 0f else floatQueue.removeAt(0).coerceIn(0f, 0.999999f)
}

class SwipePlannerTest {

    // ---------- TC-01: 间隔范围 ----------
    @Test
    fun `TC01 nextDelay always within 5s to 30s inclusive`() {
        val planner = SwipePlanner(RandomSource.default(Random(42)))
        repeat(1000) {
            val d = planner.nextDelayMs()
            assertTrue("delay=$d 低于下界", d >= SwipePlanner.MIN_DELAY_MS)
            assertTrue("delay=$d 高于上界", d <= SwipePlanner.MAX_DELAY_MS)
        }
    }

    // ---------- TC-02: 非常数、分布性 ----------
    @Test
    fun `TC02 nextDelay is random with good spread`() {
        val planner = SwipePlanner(RandomSource.default(Random(7)))
        val samples = MutableList(1000) { planner.nextDelayMs() }
        val distinct = samples.toSet()
        assertTrue("随机性不足: distinct=${distinct.size}", distinct.size >= 100)
        val low = samples.count { it < SwipePlanner.MIN_DELAY_MS + 5000 }
        val high = samples.count { it > SwipePlanner.MAX_DELAY_MS - 5000 }
        assertTrue("低区间未命中", low > 0)
        assertTrue("高区间未命中", high > 0)
    }

    // ---------- TC-03: 固定种子可复现 ----------
    @Test
    fun `TC03 nextDelay deterministic with same seed`() {
        val a = SwipePlanner(RandomSource.default(Random(123)))
        val b = SwipePlanner(RandomSource.default(Random(123)))
        val sa = List(50) { a.nextDelayMs() }
        val sb = List(50) { b.nextDelayMs() }
        assertEquals(sa, sb)
    }

    // ---------- TC-04: 边界可达 ----------
    @Test
    fun `TC04 delay bounds 5000 and 30000 reachable`() {
        val minSource = FakeRandomSource(longs = mutableListOf(0L))
        assertEquals(SwipePlanner.MIN_DELAY_MS, SwipePlanner(minSource).nextDelayMs())

        val maxSource = FakeRandomSource(longs = mutableListOf(SwipePlanner.SPAN_MS - 1))
        assertEquals(SwipePlanner.MAX_DELAY_MS, SwipePlanner(maxSource).nextDelayMs())
    }

    // ---------- TC-05: 纵向范围，保证向上滑（拟人化起止区域） ----------
    @Test
    fun `TC05 swipe vertical range and upward direction`() {
        val planner = SwipePlanner(RandomSource.default(Random(1)))
        val w = 1080
        val h = 2400
        repeat(1000) {
            val g = planner.nextSwipe(w, h)!!
            assertTrue("startY=${g.startY} 越界", g.startY in (h * 0.65f)..(h * 0.85f))
            assertTrue("endY=${g.endY} 越界", g.endY in (h * 0.20f)..(h * 0.40f))
            assertTrue("不是向上滑动", g.endY < g.startY)
        }
    }

    // ---------- TC-06: 横向范围与越界保护（避开系统边缘手势） ----------
    @Test
    fun `TC06 swipe horizontal range within screen`() {
        val planner = SwipePlanner(RandomSource.default(Random(2)))
        val w = 1080
        val h = 2400
        repeat(1000) {
            val g = planner.nextSwipe(w, h)!!
            assertTrue("startX=${g.startX} 越界", g.startX in (w * 0.30f)..(w * 0.70f))
            assertTrue(
                "endX=${g.endX} 触及屏幕边缘",
                g.endX in (w * SwipePlanner.END_X_MARGIN_RATIO)..(w * (1f - SwipePlanner.END_X_MARGIN_RATIO)),
            )
        }
    }

    // ---------- TC-07: 时长双模式（快速轻扫 / 慢速拖动） ----------
    @Test
    fun `TC07 duration covers fast flick and slow drag modes`() {
        val planner = SwipePlanner(RandomSource.default(Random(3)))
        val fast = SwipePlanner.FAST_DURATION_MIN_MS + SwipePlanner.FAST_DURATION_SPAN_MS - 1
        val slowMin = SwipePlanner.SLOW_DURATION_MIN_MS
        var fastCount = 0
        var slowCount = 0
        repeat(500) {
            val g = planner.nextSwipe(1080, 2400)!!
            assertTrue("duration=${g.durationMs} 越界", g.durationMs in 180L..700L)
            if (g.durationMs <= fast) fastCount++ else if (g.durationMs >= slowMin) slowCount++
        }
        assertTrue("缺少快速轻扫样本", fastCount > 0)
        assertTrue("缺少慢速拖动样本", slowCount > 0)
    }

    // ---------- TC-08: 连续手势不完全相同 ----------
    @Test
    fun `TC08 consecutive swipes are not identical`() {
        val planner = SwipePlanner(RandomSource.default(Random(9)))
        val w = 1080
        val h = 2400
        var different = 0
        var prev = planner.nextSwipe(w, h)!!
        repeat(100) {
            val cur = planner.nextSwipe(w, h)!!
            if (cur != prev) different++
            prev = cur
        }
        assertTrue("连续手势完全相同，拟人随机失效", different >= 95)
    }

    // ---------- TC-09: 异常尺寸 ----------
    @Test
    fun `TC09 invalid screen size returns null`() {
        val planner = SwipePlanner()
        assertNull(planner.nextSwipe(0, 2400))
        assertNull(planner.nextSwipe(1080, 0))
        assertNull(planner.nextSwipe(-1, -1))
        assertNotNull(planner.nextSwipe(1080, 2400))
    }

    // ---------- TC-05b: 小屏幕下所有点仍合法 ----------
    @Test
    fun `TC05b small screen keeps all points valid`() {
        val planner = SwipePlanner(RandomSource.default(Random(11)))
        val w = 320
        val h = 640
        repeat(500) {
            val g = planner.nextSwipe(w, h)!!
            assertTrue(g.endX in 0f..w.toFloat())
            assertTrue(g.startX in 0f..w.toFloat())
        }
    }

    // ---------- TC-21: 弧线轨迹（随机弯向、自然幅度） ----------
    @Test
    fun `TC21 curved trajectory with random bow direction and natural magnitude`() {
        val planner = SwipePlanner(RandomSource.default(Random(21)))
        val w = 1080f
        var leftBow = 0
        var rightBow = 0
        repeat(200) {
            val g = planner.nextSwipe(1080, 2400)!!
            val midX = (g.startX + g.endX) / 2f
            val midY = (g.startY + g.endY) / 2f
            val chordX = g.endX - g.startX
            val chordY = g.endY - g.startY
            val chordLen = sqrt(chordX * chordX + chordY * chordY)
            // 控制点偏离弦中点的有符号距离（法线方向）
            val nx = -chordY / chordLen
            val ny = chordX / chordLen
            val signed = (g.controlX - midX) * nx + (g.controlY - midY) * ny
            val offset = abs(signed)
            assertTrue(
                "弧度过小/过大 offset=$offset",
                offset in (w * 0.029f)..(w * 0.081f),
            )
            if (signed < 0) leftBow++ else rightBow++
        }
        assertTrue("缺少左弯轨迹", leftBow > 0)
        assertTrue("缺少右弯轨迹", rightBow > 0)
    }

    // ---------- TC-22: 三段变速（中段恒最快、时长守恒） ----------
    @Test
    fun `TC22 three segments with middle fastest and durations conserved`() {
        val planner = SwipePlanner(RandomSource.default(Random(22)))
        repeat(500) {
            val g = planner.nextSwipe(1080, 2400)!!
            assertEquals("必须为三段", 3, g.segments.size)
            val sum = g.segments.sumOf { it.durationMs }
            assertEquals("分段时长之和必须等于总时长", g.durationMs, sum)
            val d1 = g.segments[0].durationMs
            val d2 = g.segments[1].durationMs
            val d3 = g.segments[2].durationMs
            assertTrue("中段必须最快", d2 > d1 && d2 > d3)
            assertTrue("每段时长必须为正", d1 >= 1 && d2 >= 1 && d3 >= 1)
        }
    }

    // ---------- TC-23: 触屏预压延迟 ----------
    @Test
    fun `TC23 touch press delay within human range`() {
        val planner = SwipePlanner(RandomSource.default(Random(23)))
        repeat(500) {
            val g = planner.nextSwipe(1080, 2400)!!
            assertTrue(
                "startDelay=${g.startDelayMs} 越界",
                g.startDelayMs in SwipePlanner.START_DELAY_MIN_MS..90L,
            )
        }
    }

    // ---------- TC-24: 分段连续性（de Casteljau 精确切分） ----------
    @Test
    fun `TC24 segments connect smoothly at joints`() {
        val planner = SwipePlanner(RandomSource.default(Random(24)))
        val eps = 0.01f
        repeat(200) {
            val g = planner.nextSwipe(1080, 2400)!!
            val s = g.segments
            assertEquals(g.startX, s[0].p0x, eps)
            assertEquals(g.startY, s[0].p0y, eps)
            assertEquals(g.endX, s[2].p2x, eps)
            assertEquals(g.endY, s[2].p2y, eps)
            for (i in 0..1) {
                assertEquals("段 $i 终点与段 ${i + 1} 起点断开", s[i].p2x, s[i + 1].p0x, eps)
                assertEquals("段 $i 终点与段 ${i + 1} 起点断开", s[i].p2y, s[i + 1].p0y, eps)
            }
        }
    }

    // ---------- TC-21b: 小概率慢速拖动 + 轨迹整体在屏幕内 ----------
    @Test
    fun `TC21b control point stays inside screen`() {
        val planner = SwipePlanner(RandomSource.default(Random(31)))
        val w = 1080
        repeat(500) {
            val g = planner.nextSwipe(w, 2400)!!
            assertTrue("控制点 x 越界", g.controlX in 0f..w.toFloat())
            assertTrue("控制点 y 越界", g.controlY in 0f..2400f)
        }
    }
}
